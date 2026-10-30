package dev.binwatch.standalone;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.binwatch.api.HttpTransport;
import dev.binwatch.log.BwLog;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Keeps the one "AH Flipper" message in Discord current. Changes only ask for a publish; a single worker thread renders
 * the newest board at send time, so any number of changes during a slow or rate-limited request become one edit and
 * no backlog of outdated embeds can build up. Scanning never waits for Discord.
 *
 * <p>The first publish creates the message and stores its id; later ones edit it (PATCH), so a restart keeps editing
 * the same message. A deleted message is re-created. HTTP 429 waits the time Discord asks for, other failures back off
 * from 2 s up to 60 s, and a rejected webhook (401/403/404) is retried every 5 minutes; nothing retries in a tight loop.
 */
final class BoardPublisher implements AutoCloseable {
	/** Sends to the webhook (the URL stays inside the implementation). */
	interface Sender {
		HttpTransport.Response post(JsonObject payload, Duration timeout) throws IOException, InterruptedException;

		HttpTransport.Response edit(String messageId, JsonObject payload, Duration timeout) throws IOException, InterruptedException;
	}

	/** Where the message id is remembered between runs (keyed to the webhook, so a new webhook starts a new message). */
	interface MessageIds {
		String load();

		void save(String id);
	}

	private static final long MIN_BACKOFF_MS = 2_000;
	private static final long MAX_BACKOFF_MS = 60_000;
	private static final long REJECTED_RETRY_MS = 300_000;

	private final Supplier<Sender> sender;
	private final MessageIds ids;
	private final Supplier<JsonObject> view;
	private final LongSupplier minGapMs;
	private final Duration timeout;
	private final Metrics metrics;
	private final ConsoleOut console;
	private final boolean dryRun;
	private final LongSupplier wallMs;
	private final ScheduledExecutorService worker;

	// Guarded by this.
	private boolean pending;
	private boolean scheduled;
	/** Wall time the oldest unpublished change was detected (a page received); 0 when unknown. */
	private long oldestChangeWallMs;
	/** No publish before this: the edit gap after a success, or Discord's bucket reset. */
	private long gapUntilNanos;
	/** No publish before this either, not even {@link #publishNow}: a 429 or a failure backoff. */
	private long blockedUntilNanos;
	private long backoffMs = MIN_BACKOFF_MS;
	// Worker thread only.
	private String lastContent;
	private String messageId;
	private boolean idLoaded;
	private volatile String lastResult = "nothing published yet";

	BoardPublisher(Supplier<Sender> sender, MessageIds ids, Supplier<JsonObject> view, LongSupplier minGapMs,
			Duration timeout, Metrics metrics, ConsoleOut console, boolean dryRun, LongSupplier wallMs) {
		this.sender = sender;
		this.ids = ids;
		this.view = view;
		this.minGapMs = minGapMs;
		this.timeout = timeout;
		this.metrics = metrics;
		this.console = console;
		this.dryRun = dryRun;
		this.wallMs = wallMs;
		this.worker = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "BINWatch-board");
			t.setDaemon(true);
			return t;
		});
	}

	String lastResult() {
		return lastResult;
	}

	/**
	 * Asks for the board to be published. Returns at once.
	 *
	 * @param changeWallMs when the change was detected (page received, wall clock), or 0 for status-only changes
	 */
	void request(long changeWallMs) {
		synchronized (this) {
			pending = true;
			if (changeWallMs > 0 && (oldestChangeWallMs == 0 || changeWallMs < oldestChangeWallMs)) {
				oldestChangeWallMs = changeWallMs;
			}
			if (scheduled || worker.isShutdown()) {
				return;
			}
			scheduled = true;
			worker.schedule(() -> run(false), Math.max(0, waitNanos(false)), TimeUnit.NANOSECONDS);
		}
	}

	private long waitNanos(boolean urgent) {
		return (urgent ? blockedUntilNanos : Math.max(gapUntilNanos, blockedUntilNanos)) - System.nanoTime();
	}

	/**
	 * Publishes the current board now, skipping the edit gap but never a rate limit or backoff, and waits up to
	 * {@code maxWait}. Used on shutdown so the message shows it stopped.
	 */
	boolean publishNow(Duration maxWait) {
		synchronized (this) {
			pending = true;
		}
		CountDownLatch done = new CountDownLatch(1);
		try {
			worker.execute(() -> {
				try {
					run(true);
				} finally {
					done.countDown();
				}
			});
			return done.await(maxWait.toMillis(), TimeUnit.MILLISECONDS);
		} catch (java.util.concurrent.RejectedExecutionException e) {
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	@Override
	public void close() {
		worker.shutdownNow();
	}

	private void run(boolean urgent) {
		long changeWallMs;
		synchronized (this) {
			if (!urgent) {
				scheduled = false;
			}
			if (!pending) {
				return;
			}
			long wait = waitNanos(urgent);
			if (wait > 0) {
				if (!scheduled) {
					scheduled = true;
					worker.schedule(() -> run(false), wait, TimeUnit.NANOSECONDS);
				}
				return;
			}
			pending = false;
			changeWallMs = oldestChangeWallMs;
			oldestChangeWallMs = 0;
		}
		try {
			publish(changeWallMs);
		} catch (RuntimeException e) {
			BwLog.error("Board publish failed unexpectedly", e);
			retryLater(changeWallMs, nextBackoff());
		}
	}

	private void publish(long changeWallMs) {
		long t0 = System.nanoTime();
		JsonObject payload = view.get();
		String content = BoardEmbed.contentKey(payload);
		metrics.embedBuildMicros.add((System.nanoTime() - t0) / 1_000);
		if (content.equals(lastContent)) {
			metrics.discordUnchangedSkips.incrementAndGet();
			return;
		}
		if (dryRun) {
			lastContent = content;
			metrics.dryRunRenders.incrementAndGet();
			int fields = payload.getAsJsonArray("embeds").get(0).getAsJsonObject().getAsJsonArray("fields").size();
			lastResult = "dry run: embed rendered (" + fields + " flips), not sent";
			console.line("DISCORD", "Dry run: embed rendered with " + fields + " flip(s) in "
					+ metrics.embedBuildMicros.last() + " µs (not sent)");
			return;
		}
		Sender s = sender.get();
		if (s == null) {
			lastResult = "no webhook configured";
			return;
		}
		if (!idLoaded) {
			messageId = ids.load();
			idLoaded = true;
		}
		try {
			boolean created = false;
			long r0 = System.nanoTime();
			HttpTransport.Response res;
			if (messageId != null) {
				res = s.edit(messageId, payload, timeout);
				if (res.status() == 404 && discordCode(res.body()) == 10008) {
					console.line("DISCORD", "The board message was deleted in Discord; posting a new one");
					messageId = null;
				}
			} else {
				res = null;
			}
			if (messageId == null) {
				r0 = System.nanoTime();
				res = s.post(payload, timeout);
				created = true;
			}
			long reqMs = (System.nanoTime() - r0) / 1_000_000;
			metrics.discordRequestMs.add(reqMs);
			int st = res.status();
			if (st >= 200 && st < 300) {
				if (created) {
					String id = messageId(res.body());
					if (id != null) {
						messageId = id;
						ids.save(id);
					}
					metrics.discordPosts.incrementAndGet();
				} else {
					metrics.discordEdits.incrementAndGet();
				}
				lastContent = content;
				backoffMs = MIN_BACKOFF_MS;
				long gap = minGapMs.getAsLong();
				long bucketWait = bucketResetMs(res);
				synchronized (this) {
					gapUntilNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(gap, bucketWait));
				}
				String e2e = "";
				if (changeWallMs > 0) {
					long ms = Math.max(0, wallMs.getAsLong() - changeWallMs);
					metrics.detectionToDiscordMs.add(ms);
					e2e = " (" + ms + " ms after the page with the change was received)";
				}
				lastResult = (created ? "posted" : "updated") + " in " + reqMs + " ms";
				console.line("DISCORD", "Embed " + (created ? "posted as a new message" : "updated") + " in " + reqMs + " ms"
						+ e2e);
				BwLog.resetThrottle("board-http");
				return;
			}
			metrics.discordErrors.incrementAndGet();
			if (st == 429) {
				metrics.discordRateLimits.incrementAndGet();
				long wait = retryAfterMs(res);
				lastResult = "rate limited by Discord, waiting " + wait + " ms";
				BwLog.warn("Discord rate limit on the board message; next try in " + wait + " ms");
				retryLater(changeWallMs, wait);
				return;
			}
			String why = "HTTP " + st + discordMessage(res.body());
			if (st == 401 || st == 403 || st == 404) {
				lastResult = "webhook rejected (" + why + ")";
				BwLog.throttled(BwLog.Level.ERROR, "board-http", "Discord rejected the board message: " + why
						+ ". Check the webhook (run with --setup to enter a new one); retrying in 5 minutes", null);
				retryLater(changeWallMs, REJECTED_RETRY_MS);
				return;
			}
			lastResult = "Discord error " + why;
			BwLog.throttled(BwLog.Level.WARN, "board-http", "Board message not published: " + why, null);
			retryLater(changeWallMs, nextBackoff());
		} catch (HttpTimeoutException e) {
			metrics.discordErrors.incrementAndGet();
			lastResult = "Discord request timed out";
			BwLog.throttled(BwLog.Level.WARN, "board-io", "Board message not published: request timed out", null);
			retryLater(changeWallMs, nextBackoff());
		} catch (IOException e) {
			metrics.discordErrors.incrementAndGet();
			lastResult = "network error: " + e.getClass().getSimpleName();
			BwLog.throttled(BwLog.Level.WARN, "board-io", "Board message not published: network error "
					+ e.getClass().getSimpleName(), null);
			retryLater(changeWallMs, nextBackoff());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (IllegalArgumentException e) {
			lastResult = "webhook URL is invalid";
			BwLog.throttled(BwLog.Level.ERROR, "board-url", "The webhook URL is invalid; run with --setup", null);
		}
	}

	private long nextBackoff() {
		long b = backoffMs;
		backoffMs = Math.min(MAX_BACKOFF_MS, backoffMs * 2);
		return b;
	}

	/** Keeps the change pending and tries again after {@code waitMs}; newer changes are merged into that try. */
	private void retryLater(long changeWallMs, long waitMs) {
		synchronized (this) {
			pending = true;
			if (changeWallMs > 0 && (oldestChangeWallMs == 0 || changeWallMs < oldestChangeWallMs)) {
				oldestChangeWallMs = changeWallMs;
			}
			blockedUntilNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, waitMs));
			if (!scheduled && !worker.isShutdown()) {
				scheduled = true;
				worker.schedule(() -> run(false), Math.max(1, waitMs), TimeUnit.MILLISECONDS);
			}
		}
	}

	/** Discord's {@code retry_after} (seconds) from the body, or the Retry-After header; at least 1 s. */
	static long retryAfterMs(HttpTransport.Response res) {
		double secs = 0;
		try {
			JsonElement el = JsonParser.parseString(res.body() == null ? "" : res.body());
			if (el.isJsonObject() && el.getAsJsonObject().has("retry_after")) {
				secs = el.getAsJsonObject().get("retry_after").getAsDouble();
			}
		} catch (RuntimeException ignored) {
			// Fall back to the header.
		}
		if (secs <= 0) {
			try {
				String h = res.header("Retry-After");
				secs = h == null ? 0 : Double.parseDouble(h.trim());
			} catch (NumberFormatException ignored) {
				// Use the minimum.
			}
		}
		return Math.max(1000, (long) Math.ceil(secs * 1000));
	}

	/** When Discord says the webhook's bucket is empty, wait for its reset instead of hitting a 429. */
	static long bucketResetMs(HttpTransport.Response res) {
		if (!"0".equals(res.header("X-RateLimit-Remaining"))) {
			return 0;
		}
		try {
			String h = res.header("X-RateLimit-Reset-After");
			return h == null ? 0 : (long) Math.ceil(Double.parseDouble(h.trim()) * 1000);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	static String messageId(String body) {
		try {
			JsonElement el = JsonParser.parseString(body == null ? "" : body);
			if (el.isJsonObject() && el.getAsJsonObject().has("id")) {
				String id = el.getAsJsonObject().get("id").getAsString();
				return id.matches("\\d{1,30}") ? id : null;
			}
		} catch (RuntimeException ignored) {
			// No id: the next publish posts again.
		}
		return null;
	}

	/** Discord's JSON error code (10008 = unknown message, 10015 = unknown webhook), or 0. */
	static int discordCode(String body) {
		try {
			JsonElement el = JsonParser.parseString(body == null ? "" : body);
			if (el.isJsonObject() && el.getAsJsonObject().has("code")) {
				return el.getAsJsonObject().get("code").getAsInt();
			}
		} catch (RuntimeException ignored) {
			// Not JSON.
		}
		return 0;
	}

	private static String discordMessage(String body) {
		try {
			JsonElement el = JsonParser.parseString(body == null ? "" : body);
			if (el.isJsonObject() && el.getAsJsonObject().has("message")) {
				String m = el.getAsJsonObject().get("message").getAsString();
				return ": " + (m.length() > 120 ? m.substring(0, 120) : m);
			}
		} catch (RuntimeException ignored) {
			// Not JSON.
		}
		return "";
	}
}
