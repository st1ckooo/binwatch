package dev.binwatch.api;

import dev.binwatch.config.BinWatchConfig;
import dev.binwatch.item.ItemNormalizer;
import dev.binwatch.item.NormalizedItem;
import dev.binwatch.log.BwLog;
import dev.binwatch.nbt.Nbt;
import dev.binwatch.store.ObservationStore;
import dev.binwatch.store.Records;
import dev.binwatch.store.Records.ApiObservation;
import dev.binwatch.util.TimeFmt;
import dev.binwatch.value.PriceIndex;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Data source A: polls the public auction endpoint, follows pagination, and records the first time each auction UUID
 * appears in a successfully retrieved page. Timestamps are captured when the page body is received, before any
 * valuation or Discord work.
 */
public final class AuctionCollector {
	public interface Listener {
		/** Auctions this collector observed for the first time (rows already persisted). Called on a worker thread. */
		void onFirstSeen(List<FirstSeen> firstSeen, PriceIndex indexAtObservation);

		/** Called after each poll that retrieved a changed snapshot. */
		void onSnapshot(SnapshotSummary summary);
	}

	public record FirstSeen(ApiAuction auction, NormalizedItem item, ApiObservation row) {
	}

	/**
	 * @param detectLagMs  server-time gap between the snapshot's lastUpdated and receipt of its page 0 (null without
	 *                     a clock offset estimate)
	 * @param burstProbes  page-0 probes it took to see this snapshot (0 when found by a plain poll)
	 * @param staleRetries pages re-fetched because they still showed an older snapshot
	 * @param newListings  first-seen auctions created while the collector was running
	 */
	public record SnapshotSummary(long pollId, String snapshotId, long lastUpdatedMs, SnapshotStatus status,
			int totalPages, int pagesFetched, List<Integer> failedPages, int auctionsSeen, int firstSeen,
			long startedMs, long finishedMs, String note, Long detectLagMs, int burstProbes, int staleRetries,
			int newListings) {
	}

	public enum PollOutcome {
		NEW_SNAPSHOT, UNCHANGED, FAILED
	}

	public record PollResult(PollOutcome outcome, SnapshotSummary summary, String error) {
	}

	private record Active(String key, long unitPrice, boolean bin) {
	}

	private record Decoded(ApiAuction auction, NormalizedItem item) {
	}

	/** How a cycle's page 0 was found: by a burst of probes (tight timing) or by a plain poll. */
	private record Detection(int burstProbes, boolean tight, PublishEstimate publish, long requestStartedMs,
			long headersAtMs, HeadStart head) {
		static final Detection POLL = new Detection(0, false, null, 0, 0, null);
	}

	/**
	 * A new snapshot whose first (newest) auctions were processed while page 0 was still downloading; the rest of the
	 * cycle continues with the same state so nothing is reported twice.
	 */
	private record HeadStart(long lastUpdatedMs, long pollId, PriceIndex index, Set<UUID> seen, AtomicInteger newCount,
			int firstSeen, int auctions, long doneMs) {
	}

	/**
	 * When Hypixel (its CDN) started serving a snapshot, bracketed by the last probe that still got the old one and the
	 * first that got the new one.
	 *
	 * @param publishedLocalMs local time at the middle of that bracket
	 * @param uncertaintyMs    half the bracket: the true moment is within this of {@code publishedLocalMs}
	 * @param hypixelLagMs     server-time gap between the snapshot's lastUpdated and its publication (null without a
	 *                         clock offset)
	 */
	public record PublishEstimate(long lastUpdatedMs, long publishedLocalMs, long uncertaintyMs, Long hypixelLagMs) {
	}

	/** Brackets wider than this (slow probes, errors in between) are too loose to call a publish time. */
	static final long MAX_PUBLISH_BRACKET_MS = 1000;

	/** Network-error backoff for the snapshot-timed loop. */
	static final long[] ERROR_BACKOFF_MS = {1000, 2000, 5000};

	private final AuctionApiClient client;
	private final ObservationStore store;
	private final Clock clock;
	private final Listener listener;
	private final SalesHistory sales;
	private volatile BinWatchConfig config;
	private volatile ItemNormalizer normalizer;

	private final Map<UUID, Active> active = new ConcurrentHashMap<>();
	private final Set<UUID> seenEndedIds = ConcurrentHashMap.newKeySet();
	private volatile PriceIndex index;
	private volatile Long lastFullSnapshotLastUpdated;
	/** Observed interval between successive lastUpdated values; 60 s until measured. */
	private volatile long snapshotCadenceMs = 60_000;
	private volatile long sessionStartMs;
	private volatile SnapshotClock snapClock = new SnapshotClock();
	/** Last-Modified of the newest fully processed page 0, sent as If-Modified-Since by probes. */
	private volatile String snapshotLastModified;
	/** After a burst gave up: probe slowly until the next snapshot instead of bursting again. */
	private volatile boolean slowProbe;
	private int consecutiveErrors;
	/** Start time and round trip of the latest probe that still saw the previous snapshot (null after a change). */
	private Long lastUnchangedProbeStartMs;
	private long lastUnchangedProbeRttMs;
	private final Map<Long, PublishEstimate> publishEstimates = new LinkedHashMap<>() {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, PublishEstimate> eldest) {
			return size() > 32;
		}
	};

	private ScheduledExecutorService scheduler;
	private ExecutorService pagePool;
	private volatile boolean running;

	// Status, read by commands and the GUI.
	private volatile long lastPollStartedMs;
	private volatile long lastSuccessfulPollMs;
	private volatile long nextPollAtMs;
	private volatile SnapshotSummary lastSummary;
	private volatile String lastError;
	private volatile Integer lastCdnAgeSeconds;
	public final AtomicLong polls = new AtomicLong();
	public final AtomicLong probes = new AtomicLong();
	public final AtomicLong burstGiveUps = new AtomicLong();
	public final AtomicLong staleRetriesTotal = new AtomicLong();
	public final AtomicLong firstSeenTotal = new AtomicLong();
	public final AtomicLong decodeFailures = new AtomicLong();
	public final AtomicLong salesRecorded = new AtomicLong();

	public AuctionCollector(AuctionApiClient client, ObservationStore store, Clock clock, BinWatchConfig config,
			SalesHistory sales, Listener listener) {
		this.client = client;
		this.store = store;
		this.clock = clock;
		this.sales = sales;
		this.listener = listener;
		applyConfig(config);
		this.sessionStartMs = clock.millis();
	}

	public void applyConfig(BinWatchConfig cfg) {
		this.config = cfg;
		this.normalizer = new ItemNormalizer(cfg.valuation.includeReforgeInKey, cfg.valuation.valueAttributes);
		client.setPolicy(policyFrom(cfg.api));
	}

	public static AuctionApiClient.RetryPolicy policyFrom(BinWatchConfig.Api api) {
		return new AuctionApiClient.RetryPolicy(api.maxRetries, api.retryBaseDelayMs, api.retryMaxDelayMs,
				java.time.Duration.ofSeconds(api.requestTimeoutSeconds));
	}

	// ---- scheduling --------------------------------------------------------------------------------------------------

	public synchronized void start() {
		if (running) {
			return;
		}
		running = true;
		sessionStartMs = clock.millis();
		lastFullSnapshotLastUpdated = null;
		snapClock = new SnapshotClock();
		snapshotLastModified = null;
		slowProbe = false;
		consecutiveErrors = 0;
		scheduler = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "BINWatch-API-collector"));
		pagePool = Executors.newFixedThreadPool(Math.max(1, config.api.maxConcurrentPageRequests), r -> daemon(r, "BINWatch-API-page"));
		BwLog.info("API collector started (" + (config.api.snapshotTimedPolling
				? "snapshot-timed polling, " + config.api.burstIntervalMs + " ms probes"
				: "poll interval " + config.api.pollIntervalSeconds + " s") + ")");
		schedule(0);
	}

	public synchronized void stop() {
		if (!running) {
			return;
		}
		running = false;
		scheduler.shutdownNow();
		pagePool.shutdownNow();
		nextPollAtMs = 0;
		BwLog.info("API collector stopped");
	}

	public boolean running() {
		return running;
	}

	private static Thread daemon(Runnable r, String name) {
		Thread t = new Thread(r, name);
		t.setDaemon(true);
		return t;
	}

	private void schedule(long delayMs) {
		if (!running) {
			return;
		}
		nextPollAtMs = clock.millis() + delayMs;
		scheduler.schedule(this::tick, delayMs, TimeUnit.MILLISECONDS);
	}

	private void tick() {
		if (!running) {
			return;
		}
		long delay;
		try {
			delay = config.api.snapshotTimedPolling ? timedStep() : intervalStep();
		} catch (RuntimeException e) {
			BwLog.throttled(BwLog.Level.ERROR, "collector-tick", "Unexpected collector error", e);
			lastError = e.toString();
			delay = config.api.pollIntervalSeconds * 1000L;
		}
		long rl = client.rateLimitedUntilMs() - clock.millis();
		schedule(Math.max(delay, rl));
	}

	/** Fixed-interval polling (snapshotTimedPolling = false), optionally aligned to the snapshot cadence. */
	private long intervalStep() {
		long started = clock.millis();
		PollResult r = pollOnce(pagePool);
		BinWatchConfig.Api api = config.api;
		long interval = api.pollIntervalSeconds * 1000L;
		long elapsed = clock.millis() - started;
		return switch (r.outcome()) {
			case UNCHANGED -> api.unchangedRecheckSeconds > 0 ? api.unchangedRecheckSeconds * 1000L : interval;
			case FAILED -> interval;
			case NEW_SNAPSHOT -> nextDelayAfterSnapshot(interval, elapsed, r.summary().lastUpdatedMs(),
					snapshotCadenceMs, clock.millis(), api.alignToSnapshotCadence);
		};
	}

	/**
	 * One step of snapshot-timed polling; returns the delay until the next step.
	 * <ul>
	 * <li>No snapshot yet, or more than one interval behind (computer slept, long outage): full poll to re-sync.</li>
	 * <li>Before the next snapshot is due: sleep until {@code preWakeMs} before it.</li>
	 * <li>Due: probe page 0 every {@code burstIntervalMs} with If-Modified-Since until it changes, then fetch the
	 * rest. After {@code maxBurstMs} without a change, log it and probe slowly until the next snapshot.</li>
	 * </ul>
	 */
	public long timedStep() {
		BinWatchConfig.Api api = config.api;
		SnapshotClock sc = snapClock;
		long now = clock.millis();
		long recheck = Math.max(1000, api.unchangedRecheckSeconds * 1000L);
		if (sc.behind(now)) {
			if (sc.currentSnapshot() != null) {
				BwLog.info("Snapshot clock is more than one interval behind; re-syncing with a full poll");
			}
			PollResult r = pollOnce(pagePool);
			if (r.outcome() == PollOutcome.FAILED) {
				return errorBackoff();
			}
			consecutiveErrors = 0;
			// A failed page leaves the snapshot unrecorded; retry it soon rather than waiting a whole interval.
			return r.outcome() == PollOutcome.NEW_SNAPSHOT && !sc.behind(clock.millis()) ? untilWake() : recheck;
		}
		long wake = sc.wakeAtLocalMs(api.preWakeMs);
		if (slowProbe && wake > now) {
			// Between a skipped (or very late) snapshot and the next expected one: catch a late one within a recheck.
			int result = probeOnce(0);
			if (result < 0) {
				return errorBackoff();
			}
			return result > 0 ? untilWake() : Math.min(recheck, Math.max(0, wake - clock.millis()));
		}
		slowProbe = false;
		if (wake > now) {
			return wake - now;
		}
		return burst();
	}

	private long untilWake() {
		return Math.max(0, snapClock.wakeAtLocalMs(config.api.preWakeMs) - clock.millis());
	}

	private long errorBackoff() {
		long d = ERROR_BACKOFF_MS[Math.min(consecutiveErrors, ERROR_BACKOFF_MS.length - 1)];
		consecutiveErrors++;
		return d;
	}

	private long burst() {
		BinWatchConfig.Api api = config.api;
		long started = clock.millis();
		for (int n = 1; ; n++) {
			long probeStarted = clock.millis();
			int result = probeOnce(n);
			if (result > 0) {
				return untilWake();
			}
			if (clock.millis() - started >= api.maxBurstMs) {
				burstGiveUps.incrementAndGet();
				slowProbe = true;
				SnapshotClock sc = snapClock;
				sc.onMissed();
				BwLog.info("No new snapshot after " + n + " probes over " + (clock.millis() - started)
						+ " ms (expected one after lastUpdated " + sc.currentSnapshot() + " + " + sc.avgIntervalMs()
						+ " ms); probing every " + Math.max(1, api.unchangedRecheckSeconds)
						+ " s and bursting again when the following one is due");
				return Math.max(1000, api.unchangedRecheckSeconds * 1000L);
			}
			try {
				// Probes start every burstIntervalMs; the request's own round trip counts towards the gap.
				client.pause(result < 0 ? errorBackoff()
						: Math.max(0, api.burstIntervalMs - (clock.millis() - probeStarted)));
			} catch (ApiException e) {
				return api.burstIntervalMs;
			}
		}
	}

	/**
	 * One conditional page-0 request. Returns 1 if it revealed a new snapshot (which has then been processed), 0 if
	 * unchanged and -1 on a network/API error. {@code burstProbe} is the probe's 1-based index within a burst, 0 for a
	 * slow probe; a burst that saw at least one unchanged answer first pins the publish moment closely.
	 */
	private int probeOnce(int burstProbe) {
		AuctionApiClient.Probe pr;
		probes.incrementAndGet();
		HeadStart[] head = {null};
		try {
			pr = client.probePage0(snapshotLastModified,
					(lu, auctions, atMs, requestStartedMs) -> head[0] = processHead(lu, auctions, atMs, requestStartedMs));
		} catch (ApiException e) {
			lastError = "page 0 probe: " + e.kind() + " " + e.getMessage();
			BwLog.throttled(BwLog.Level.WARN, "collector-probe", "API probe failed: " + lastError, null);
			return -1;
		}
		consecutiveErrors = 0;
		if (pr.serverDateMs() != null) {
			snapClock.onDateSample(pr.serverDateMs(), pr.receivedAtMs());
		}
		lastSuccessfulPollMs = pr.receivedAtMs();
		Long current = snapClock.currentSnapshot();
		if (pr.notModified() || (current != null && pr.page().lastUpdatedMs() <= current)) {
			lastUnchangedProbeStartMs = pr.requestStartedMs();
			lastUnchangedProbeRttMs = Math.max(0, pr.headersAtMs() - pr.requestStartedMs());
			return 0;
		}
		slowProbe = false;
		lastPollStartedMs = pr.receivedAtMs();
		polls.incrementAndGet();
		PublishEstimate publish = estimatePublish(pr.page().lastUpdatedMs(), pr.requestStartedMs());
		lastUnchangedProbeStartMs = null;
		HeadStart h = head[0] != null && head[0].lastUpdatedMs() == pr.page().lastUpdatedMs() ? head[0] : null;
		runCycle(pr.page(), pagePool, pr.receivedAtMs(),
				new Detection(burstProbe, burstProbe > 1, publish, pr.requestStartedMs(), pr.headersAtMs(), h));
		return 1;
	}

	/**
	 * Values and alerts the newest auctions of a new snapshot while the rest of page 0 is still downloading. Returns
	 * null (and does nothing) when the snapshot is not new.
	 */
	private HeadStart processHead(long lastUpdatedMs, List<ApiAuction> auctions, long atMs, long requestStartedMs) {
		Long current = snapClock.currentSnapshot();
		if (current != null && lastUpdatedMs <= current) {
			return null;
		}
		try {
			estimatePublish(lastUpdatedMs, requestStartedMs);
			Set<UUID> seen = ConcurrentHashMap.newKeySet();
			AtomicInteger newCount = new AtomicInteger();
			PriceIndex idx = index;
			AuctionPage page = new AuctionPage(0, -1, -1, lastUpdatedMs, auctions, atMs, null, null, 0, null);
			int n = processPage(page, atMs, "lu-" + lastUpdatedMs, lastUpdatedMs, seen, idx, newCount);
			return new HeadStart(lastUpdatedMs, atMs, idx, seen, newCount, n, auctions.size(), clock.millis());
		} catch (RuntimeException e) {
			BwLog.throttled(BwLog.Level.ERROR, "collector-head", "Processing the start of page 0 failed", e);
			return null;
		}
	}

	/**
	 * Both requests reached the CDN about half a round trip after they were sent; the snapshot went live between
	 * the two arrivals. Null when there was no recent unchanged probe to bracket it with.
	 */
	private PublishEstimate estimatePublish(long lu, long changedRequestStartedMs) {
		PublishEstimate known = publishEstimate(lu);
		if (known != null) {
			return known;
		}
		Long prev = lastUnchangedProbeStartMs;
		if (prev == null) {
			return null;
		}
		long gap = changedRequestStartedMs - prev;
		if (gap <= 0 || gap > MAX_PUBLISH_BRACKET_MS) {
			return null;
		}
		long published = prev + gap / 2 + lastUnchangedProbeRttMs / 2;
		SnapshotClock sc = snapClock;
		PublishEstimate e = new PublishEstimate(lu, published, (gap + 1) / 2,
				sc.hasOffset() ? sc.toServer(published) - lu : null);
		synchronized (publishEstimates) {
			publishEstimates.put(lu, e);
		}
		return e;
	}

	/** Publish estimate for the snapshot with this lastUpdated, or null when it was not bracketed by probes. */
	public PublishEstimate publishEstimate(long lastUpdatedMs) {
		synchronized (publishEstimates) {
			return publishEstimates.get(lastUpdatedMs);
		}
	}

	/**
	 * Delay before the next poll after a new snapshot. Without alignment this is the remaining poll interval. With
	 * alignment, the next poll is also no later than the time the next snapshot is due ({@code lastUpdated + cadence});
	 * if that copy is not available yet, the unchanged-recheck path picks it up shortly after it appears. A fixed
	 * interval otherwise locks onto a poor phase (observed live: polls landing ~60 s after each lastUpdated).
	 */
	public static long nextDelayAfterSnapshot(long intervalMs, long elapsedMs, long lastUpdatedMs, long cadenceMs,
			long nowMs, boolean align) {
		long delay = intervalMs - elapsedMs;
		if (align && cadenceMs > 0) {
			delay = Math.min(delay, lastUpdatedMs + cadenceMs - nowMs);
		}
		return Math.max(1000, delay);
	}

	// ---- polling -----------------------------------------------------------------------------------------------------

	/** Performs one poll synchronously. Pages after page 0 are fetched on {@code pool} (null = sequentially). */
	public PollResult pollOnce(ExecutorService pool) {
		long started = clock.millis();
		lastPollStartedMs = started;
		polls.incrementAndGet();
		AuctionPage p0;
		try {
			p0 = client.fetchPage(0);
		} catch (ApiException e) {
			lastError = "page 0: " + e.kind() + " " + e.getMessage();
			BwLog.throttled(BwLog.Level.WARN, "collector-page0", "API poll failed: " + lastError, null);
			return new PollResult(PollOutcome.FAILED, null, lastError);
		}
		lastCdnAgeSeconds = p0.cdnAgeSeconds();
		if (p0.serverDateMs() != null) {
			snapClock.onDateSample(p0.serverDateMs(), p0.receivedAtMs());
		}
		if (lastFullSnapshotLastUpdated != null && p0.lastUpdatedMs() == lastFullSnapshotLastUpdated) {
			lastSuccessfulPollMs = clock.millis();
			lastError = null;
			BwLog.debug("API snapshot unchanged (lastUpdated " + p0.lastUpdatedMs() + ")");
			return new PollResult(PollOutcome.UNCHANGED, lastSummary, null);
		}
		return runCycle(p0, pool, started, Detection.POLL);
	}

	/**
	 * Processes a snapshot whose page 0 is already in hand: page 0 first, then the remaining pages in parallel. Each
	 * page is diffed and handed to the listener (valuation, alerts) as soon as it arrives, not after the last page.
	 */
	private PollResult runCycle(AuctionPage p0, ExecutorService pool, long started, Detection detection) {
		HeadStart head = detection.head();
		long pollId = head != null ? head.pollId() : started;
		lastCdnAgeSeconds = p0.cdnAgeSeconds();
		String snapshotId = "lu-" + p0.lastUpdatedMs();
		PriceIndex indexAtStart = head != null ? head.index() : index;
		Set<UUID> seenThisPoll = head != null ? head.seen() : ConcurrentHashMap.newKeySet();
		int[] firstSeenCount = {head != null ? head.firstSeen() : 0};
		AtomicInteger newCount = head != null ? head.newCount() : new AtomicInteger();
		AtomicInteger staleRetries = new AtomicInteger();
		Set<Long> lastUpdatedValues = new TreeSet<>();
		Set<Integer> totalPagesValues = new TreeSet<>();
		lastUpdatedValues.add(p0.lastUpdatedMs());
		totalPagesValues.add(p0.totalPages());
		List<Integer> failed = new ArrayList<>();
		int fetched = 1;
		String note = null;

		firstSeenCount[0] += processPage(p0, pollId, snapshotId, p0.lastUpdatedMs(), seenThisPoll, indexAtStart, newCount);
		long page0DoneMs = clock.millis();

		int total = p0.totalPages();
		List<Future<AuctionPage>> futures = new ArrayList<>();
		List<Integer> futurePages = new ArrayList<>();
		for (int page = 1; page < total; page++) {
			final int pg = page;
			if (pool == null) {
				try {
					AuctionPage p = fetchFresh(pg, p0.lastUpdatedMs(), staleRetries);
					fetched++;
					lastUpdatedValues.add(p.lastUpdatedMs());
					totalPagesValues.add(p.totalPages());
					firstSeenCount[0] += processPage(p, pollId, snapshotId, p0.lastUpdatedMs(), seenThisPoll, indexAtStart, newCount);
				} catch (ApiException e) {
					failed.add(pg);
					note = "page " + pg + ": " + e.kind() + " " + e.getMessage();
				}
			} else {
				futurePages.add(pg);
				futures.add(pool.submit(() -> {
					AuctionPage p = fetchFresh(pg, p0.lastUpdatedMs(), staleRetries);
					synchronized (lastUpdatedValues) {
						lastUpdatedValues.add(p.lastUpdatedMs());
						totalPagesValues.add(p.totalPages());
					}
					int n = processPage(p, pollId, snapshotId, p0.lastUpdatedMs(), seenThisPoll, indexAtStart, newCount);
					synchronized (firstSeenCount) {
						firstSeenCount[0] += n;
					}
					return p;
				}));
			}
		}
		for (int i = 0; i < futures.size(); i++) {
			try {
				futures.get(i).get();
				fetched++;
			} catch (ExecutionException e) {
				failed.add(futurePages.get(i));
				Throwable c = e.getCause();
				note = "page " + futurePages.get(i) + ": " + (c instanceof ApiException ae ? ae.kind() + " " + ae.getMessage() : String.valueOf(c));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				failed.add(futurePages.get(i));
				note = "interrupted";
			}
		}

		SnapshotStatus status;
		if (!failed.isEmpty()) {
			status = SnapshotStatus.INCOMPLETE;
		} else if (lastUpdatedValues.size() > 1 || totalPagesValues.size() > 1) {
			status = SnapshotStatus.INCONSISTENT;
			note = "pages reported lastUpdated " + lastUpdatedValues + " and totalPages " + totalPagesValues;
		} else {
			status = SnapshotStatus.COMPLETE;
		}
		try {
			store.updatePollStatus(pollId, status.name());
		} catch (java.sql.SQLException e) {
			BwLog.throttled(BwLog.Level.ERROR, "db-poll-status", "Could not record snapshot status", e);
		}

		if (status == SnapshotStatus.COMPLETE) {
			active.keySet().retainAll(seenThisPoll);
		}
		if (status != SnapshotStatus.INCOMPLETE) {
			List<PriceIndex.Source> sources = new ArrayList<>(active.size());
			active.forEach((uuid, a) -> {
				if (a.bin()) {
					sources.add(new PriceIndex.Source(uuid, a.key(), a.unitPrice()));
				}
			});
			index = PriceIndex.build(sources, clock.millis(), p0.lastUpdatedMs(), status.name());
			Long previous = lastFullSnapshotLastUpdated;
			if (previous != null) {
				long delta = p0.lastUpdatedMs() - previous;
				// Only trust deltas that look like a single cadence step (missed updates give multiples).
				if (delta >= 20_000 && delta <= 120_000) {
					snapshotCadenceMs = delta;
				}
			}
			lastFullSnapshotLastUpdated = p0.lastUpdatedMs();
			snapClock.onSnapshot(p0.lastUpdatedMs(), p0.receivedAtMs(), detection.tight());
			if (p0.lastModified() != null) {
				snapshotLastModified = p0.lastModified();
			}
		}

		long finished = clock.millis();
		SnapshotClock sc = snapClock;
		Long detectLag = sc.hasOffset() ? sc.toServer(p0.receivedAtMs()) - p0.lastUpdatedMs() : null;
		staleRetriesTotal.addAndGet(staleRetries.get());
		SnapshotSummary summary = new SnapshotSummary(pollId, snapshotId, p0.lastUpdatedMs(), status, total, fetched,
				List.copyOf(failed), seenThisPoll.size(), firstSeenCount[0], started, finished, note, detectLag,
				detection.burstProbes(), staleRetries.get(), newCount.get());
		lastSummary = summary;
		lastSuccessfulPollMs = finished;
		lastError = status == SnapshotStatus.INCOMPLETE ? note : null;
		firstSeenTotal.addAndGet(firstSeenCount[0]);
		BwLog.info("API snapshot " + snapshotId + " " + status + ": " + fetched + "/" + total + " pages, "
				+ seenThisPoll.size() + " auctions, " + firstSeenCount[0] + " first seen (" + newCount.get() + " new), "
				+ (detectLag != null ? "page 0 " + secs(detectLag) + " after lastUpdated, " : "")
				+ (detection.burstProbes() > 0 ? detection.burstProbes() + " probes, " : "")
				+ "all pages in " + (finished - p0.receivedAtMs()) + " ms, " + staleRetries.get() + " stale retries"
				+ (p0.cdnAgeSeconds() != null ? ", CDN age " + p0.cdnAgeSeconds() + " s" : "")
				+ (note != null ? " (" + note + ")" : ""));
		PublishEstimate pub = detection.publish();
		if (pub != null) {
			BwLog.info("Snapshot " + snapshotId + " timing: Hypixel published it "
					+ (pub.hypixelLagMs() == null ? "" : secs(pub.hypixelLagMs()) + " after lastUpdated, ")
					+ "at " + TimeFmt.utc(pub.publishedLocalMs()) + " (+/-" + pub.uncertaintyMs() + " ms); "
					+ "since publish: request sent +" + (detection.requestStartedMs() - pub.publishedLocalMs()) + " ms, "
					+ "first byte +" + (detection.headersAtMs() - pub.publishedLocalMs()) + " ms, "
					+ (head == null ? "" : "first " + head.auctions() + " listings processed +"
							+ (head.doneMs() - pub.publishedLocalMs()) + " ms (" + head.newCount().get() + " new so far), ")
					+ "page 0 downloaded +" + (p0.receivedAtMs() - pub.publishedLocalMs()) + " ms, "
					+ "page 0 processed +" + (page0DoneMs - pub.publishedLocalMs()) + " ms");
		}

		if (config.api.fetchEndedAuctions) {
			fetchEndedAuctions();
		}
		try {
			listener.onSnapshot(summary);
		} catch (RuntimeException e) {
			BwLog.throttled(BwLog.Level.ERROR, "listener-snapshot", "Snapshot listener failed", e);
		}
		return new PollResult(PollOutcome.NEW_SNAPSHOT, summary, null);
	}

	/**
	 * Fetches a page, re-fetching it (after {@code staleRetryDelayMs}) while it still reports an older snapshot than
	 * page 0: right after a snapshot publishes, the CDN can briefly serve some pages from the previous one.
	 */
	private AuctionPage fetchFresh(int page, long snapshotLastUpdated, AtomicInteger staleRetries) throws ApiException {
		AuctionPage p = client.fetchPage(page);
		for (int i = 0; i < config.api.pageRetries && p.lastUpdatedMs() < snapshotLastUpdated; i++) {
			staleRetries.incrementAndGet();
			client.pause(config.api.staleRetryDelayMs);
			p = client.fetchPage(page);
		}
		if (p.serverDateMs() != null) {
			snapClock.onDateSample(p.serverDateMs(), p.receivedAtMs());
		}
		return p;
	}

	public static String secs(long ms) {
		return String.format(java.util.Locale.ROOT, "%.1f s", ms / 1000.0);
	}

	private int processPage(AuctionPage page, long pollId, String snapshotId, long snapshotLastUpdated,
			Set<UUID> seenThisPoll, PriceIndex indexAtStart, AtomicInteger newCount) {
		// Decode only auctions this session has not tracked yet (CPU heavy part, done outside any lock).
		List<Decoded> unknown = new ArrayList<>();
		for (ApiAuction a : page.auctions()) {
			seenThisPoll.add(a.uuid());
			if (!active.containsKey(a.uuid())) {
				unknown.add(new Decoded(a, decode(a)));
			}
		}
		if (unknown.isEmpty()) {
			return 0;
		}
		Set<UUID> known;
		try {
			known = store.existingApiUuids(unknown.stream().map(d -> d.auction().uuid()).toList());
		} catch (java.sql.SQLException e) {
			BwLog.throttled(BwLog.Level.ERROR, "db-existing", "Could not check known auctions; page skipped", e);
			return 0;
		}
		List<ApiObservation> rows = new ArrayList<>();
		List<FirstSeen> firstSeen = new ArrayList<>();
		for (Decoded d : unknown) {
			ApiAuction a = d.auction();
			NormalizedItem item = d.item();
			int count = item == null ? 1 : item.count();
			long unit = a.startingBid() > 0 ? a.startingBid() / Math.max(1, count) : -1;
			active.put(a.uuid(), new Active(item == null ? null : item.comparisonKey(), unit, a.bin()));
			if (known.contains(a.uuid())) {
				continue;
			}
			// Created after this collector session began, so it was listed while we were watching. Requiring the
			// previous snapshot to be COMPLETE would wrongly demote almost everything: the CDN often serves pages
			// from two snapshot versions in one poll (observed live), which is graded INCONSISTENT.
			String kind = a.startMs() != null && a.startMs() > sessionStartMs
					? Records.KIND_NEW : Records.KIND_PREEXISTING;
			String itemUuid = a.itemUuid() != null ? a.itemUuid() : item == null ? null : item.itemUuid();
			ApiObservation row = new ApiObservation(a.uuid(), normalizeItemUuid(itemUuid),
					item == null ? null : item.skyblockId(), a.itemName(), item == null ? null : item.comparisonKey(),
					a.tier(), a.bin(), a.startingBid(), count, a.startMs(), a.endMs(), page.receivedAtMs(),
					snapshotLastUpdated, snapshotId, pollId, page.page(), Records.PAGE_RECEIVED, kind);
			rows.add(row);
			firstSeen.add(new FirstSeen(a, item, row));
			if (Records.KIND_NEW.equals(kind)) {
				newCount.incrementAndGet();
			}
		}
		if (rows.isEmpty()) {
			return 0;
		}
		int inserted;
		try {
			inserted = store.insertApiObservations(rows);
		} catch (java.sql.SQLException e) {
			BwLog.throttled(BwLog.Level.ERROR, "db-insert-api", "Could not persist first observations", e);
			return 0;
		}
		if (config.logging.logEveryFirstSeen) {
			for (FirstSeen f : firstSeen) {
				BwLog.info("First seen " + f.row().auctionUuid() + " " + f.row().itemName() + " "
						+ f.row().startingBid() + " (" + f.row().observationKind() + ", page " + page.page() + ")");
			}
		}
		try {
			listener.onFirstSeen(firstSeen, indexAtStart);
		} catch (RuntimeException e) {
			BwLog.throttled(BwLog.Level.ERROR, "listener-first-seen", "First-seen listener failed", e);
		}
		return inserted;
	}

	/** Item UUIDs appear with and without dashes depending on the source; store them in one canonical form. */
	public static String normalizeItemUuid(String s) {
		if (s == null || s.isBlank()) {
			return null;
		}
		UUID u = AuctionApiClient.parseHexUuid(s);
		return u == null ? s.trim().toLowerCase(java.util.Locale.ROOT) : u.toString();
	}

	private NormalizedItem decode(ApiAuction a) {
		if (a.itemBytes() == null) {
			return null;
		}
		try {
			ItemNormalizer.ItemBytes ib = ItemNormalizer.firstItem(Nbt.decodeBase64Gzip(a.itemBytes()));
			if (ib == null) {
				decodeFailures.incrementAndGet();
				return null;
			}
			return normalizer.normalize(ItemNormalizer.extraAttributes(ib.tag()), a.itemName(), a.tier(), ib.count());
		} catch (IOException | RuntimeException e) {
			decodeFailures.incrementAndGet();
			BwLog.throttled(BwLog.Level.WARN, "decode-item", "Could not decode item_bytes: " + e.getMessage(), null);
			return null;
		}
	}

	private void fetchEndedAuctions() {
		AuctionApiClient.EndedResult ended;
		try {
			ended = client.fetchEnded();
		} catch (ApiException e) {
			BwLog.throttled(BwLog.Level.WARN, "ended-fetch", "auctions_ended fetch failed: " + e.kind() + " " + e.getMessage(), null);
			return;
		}
		List<Records.Sale> rows = new ArrayList<>();
		for (EndedAuction e : ended.auctions()) {
			if (!e.bin() || !seenEndedIds.add(e.auctionId())) {
				continue;
			}
			try {
				ItemNormalizer.ItemBytes ib = ItemNormalizer.firstItem(Nbt.decodeBase64Gzip(e.itemBytes()));
				if (ib == null) {
					continue;
				}
				Map<String, Object> extra = ItemNormalizer.extraAttributes(ib.tag());
				Map<String, Object> display = ib.tag() == null ? null : Nbt.compound(ib.tag(), "display");
				String name = Nbt.string(display, "Name");
				String tier = ib.tooltipTier() != null ? ib.tooltipTier() : tierFromLore(display);
				NormalizedItem item = normalizer.normalize(extra, name, tier, ib.count());
				if (item.comparisonKey() == null) {
					continue;
				}
				long unit = e.price() / Math.max(1, item.count());
				rows.add(new Records.Sale(e.auctionId(), item.comparisonKey(), unit, e.timestampMs()));
				sales.add(item.comparisonKey(), e.timestampMs(), unit);
			} catch (IOException | RuntimeException ex) {
				decodeFailures.incrementAndGet();
			}
		}
		if (seenEndedIds.size() > 50_000) {
			seenEndedIds.clear();
		}
		try {
			int n = store.insertSales(rows);
			salesRecorded.addAndGet(n);
			BwLog.debug("Recorded " + n + " ended BIN sales");
		} catch (java.sql.SQLException ex) {
			BwLog.throttled(BwLog.Level.ERROR, "db-sales", "Could not persist sales", ex);
		}
	}

	/**
	 * auctions_ended has no tier field; the rarity is the first word of the last lore line (e.g. "LEGENDARY SWORD").
	 * Lowest-BIN keys include the API tier, so sales keys must derive it the same way to be comparable.
	 */
	static String tierFromLore(Map<String, Object> display) {
		List<Object> lore = Nbt.list(display, "Lore");
		if (lore == null || lore.isEmpty()) {
			return null;
		}
		String last = ItemNormalizer.stripFormatting(String.valueOf(lore.get(lore.size() - 1))).trim();
		// Recombobulated items render as "a MYTHIC DUNGEON BOOTS a" where "a" is an obfuscated marker.
		if (last.startsWith("a ")) {
			last = last.substring(2);
		}
		for (String t : List.of("VERY SPECIAL", "SPECIAL", "DIVINE", "MYTHIC", "LEGENDARY", "EPIC", "RARE", "UNCOMMON", "COMMON", "SUPREME", "ULTIMATE")) {
			if (last.startsWith(t)) {
				return t.replace(' ', '_');
			}
		}
		return null;
	}

	// ---- status ------------------------------------------------------------------------------------------------------

	public PriceIndex index() {
		return index;
	}

	public int activeAuctions() {
		return active.size();
	}

	/** Whether the auction was present in the latest complete view of the API (i.e. not known to have ended). */
	public boolean isActive(UUID uuid) {
		return active.containsKey(uuid);
	}

	public long lastPollStartedMs() {
		return lastPollStartedMs;
	}

	public long lastSuccessfulPollMs() {
		return lastSuccessfulPollMs;
	}

	public long nextPollAtMs() {
		return nextPollAtMs;
	}

	public SnapshotSummary lastSummary() {
		return lastSummary;
	}

	public String lastError() {
		return lastError;
	}

	public Integer lastCdnAgeSeconds() {
		return lastCdnAgeSeconds;
	}

	/** Estimated Hypixel server time minus local time, or null before the first Date header. */
	public Long clockOffsetEstimateMs() {
		SnapshotClock sc = snapClock;
		return sc.hasOffset() ? sc.offsetMs() : null;
	}

	public SnapshotClock snapshotClock() {
		return snapClock;
	}

	public AuctionApiClient client() {
		return client;
	}

	/** Test hook: snapshot of tracked UUIDs. */
	Set<UUID> trackedUuids() {
		return new HashSet<>(active.keySet());
	}
}
