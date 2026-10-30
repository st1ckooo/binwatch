package dev.binwatch.service;

import com.google.gson.JsonObject;
import dev.binwatch.api.AuctionApiClient;
import dev.binwatch.api.AuctionCollector;
import dev.binwatch.api.HttpTransport;
import dev.binwatch.api.JdkHttpTransport;
import dev.binwatch.api.SalesHistory;
import dev.binwatch.api.ScriptedTransport;
import dev.binwatch.compare.TimingReport;
import dev.binwatch.config.BinWatchConfig;
import dev.binwatch.config.ConfigManager;
import dev.binwatch.config.Secrets;
import dev.binwatch.discord.AlertDispatcher;
import dev.binwatch.discord.DiscordBot;
import dev.binwatch.discord.DiscordFeed;
import dev.binwatch.discord.DiscordGateway;
import dev.binwatch.discord.SummaryPublisher;
import dev.binwatch.discord.WebhookClient;
import dev.binwatch.export.CsvExporter;
import dev.binwatch.log.BwLog;
import dev.binwatch.market.MarketCheck;
import dev.binwatch.market.MarketService;
import dev.binwatch.store.ObservationStore;
import dev.binwatch.store.Records;
import dev.binwatch.trade.ProfitSummary;
import dev.binwatch.trade.TradeChatParser;
import dev.binwatch.trade.TradeLedger;
import dev.binwatch.util.TimeFmt;
import dev.binwatch.value.FlipCandidate;
import dev.binwatch.value.FlipEvaluator;
import dev.binwatch.value.PriceIndex;
import dev.binwatch.value.Valuation;
import dev.binwatch.value.Valuator;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Owns every BIN Watch component and exposes the operations used by commands and the GUI. Contains no Minecraft
 * classes.
 */
public final class BinWatchService implements AutoCloseable {
	private static final int TOP_CANDIDATES = 50;

	private final Path dataDir;
	private final ConfigManager configManager;
	private final Clock clock;
	private volatile BinWatchConfig config;
	private volatile Secrets secrets;
	private final List<String> startupProblems = new ArrayList<>();

	private ObservationStore store;
	private String storeError;
	private AuctionCollector collector;
	private SalesHistory sales;
	private AlertDispatcher dispatcher;
	private MarketService marketService;
	private TradeLedger ledger;
	private SummaryPublisher summaryPublisher;
	private DiscordBot bot;
	private DiscordFeed feed;
	private DiscordGateway gateway;
	private final java.util.ArrayDeque<Long> logPosts = new java.util.ArrayDeque<>();
	private volatile Valuator valuator;
	private volatile FlipEvaluator evaluator;
	private ScheduledExecutorService maintenance;
	private volatile long lastPruneMs;
	private volatile long lastAutoExportMs;
	private final Map<UUID, FlipCandidate> topCandidates = new ConcurrentHashMap<>();
	// Updated from the parallel page threads, hence atomic.
	private final AtomicLong candidatesEvaluated = new AtomicLong();
	private final AtomicLong candidatesPassed = new AtomicLong();
	private final AtomicLong valuationsInvalid = new AtomicLong();
	private long passedAtLastSnapshot;

	private volatile Consumer<FlipCandidate> flipListener = c -> {
	};

	private volatile Consumer<FlipCandidate> autoViewListener = c -> {
	};
	private volatile Consumer<FlipCandidate> opportunityListener = c -> {
	};
	private volatile Consumer<AuctionCollector.SnapshotSummary> snapshotListener = s -> {
	};
	private volatile Consumer<List<dev.binwatch.api.EndedAuction>> endedListener = e -> {
	};
	private final AtomicLong evaluationNanos = new AtomicLong();
	/** Dry run: nothing is sent to Discord (no alerts, summary, bot or log channel), whatever the config says. */
	private volatile boolean discordOff;
	private WebhookClient webhook;
	private volatile long configFileModifiedMs;

	/** Receives the same alerted flips as the chat listener, regardless of chat settings; used by the speed check. */
	public void setAutoViewListener(Consumer<FlipCandidate> listener) {
		this.autoViewListener = listener == null ? c -> {
		} : listener;
	}

	/**
	 * Receives every live flip that passed the filters and the market check, before alert rate limits and cooldowns;
	 * used by the standalone app's board. Called on a collector or market worker thread.
	 */
	public void setOpportunityListener(Consumer<FlipCandidate> listener) {
		this.opportunityListener = listener == null ? c -> {
		} : listener;
	}

	/** Receives each BIN sale from {@code auctions_ended} once (auction id, sale time, price). */
	public void setEndedListener(Consumer<List<dev.binwatch.api.EndedAuction>> listener) {
		this.endedListener = listener == null ? e -> {
		} : listener;
	}

	/** Call before {@link #init()}: keeps every Discord feature silent for this run. */
	public void setDiscordOff(boolean off) {
		this.discordOff = off;
	}

	/** Receives the summary of every changed snapshot after all its pages were processed. */
	public void setSnapshotListener(Consumer<AuctionCollector.SnapshotSummary> listener) {
		this.snapshotListener = listener == null ? s -> {
		} : listener;
	}

	/** Receives flips that were alerted (or would be, if no webhook is set); used for in-game chat alerts. */
	public void setFlipListener(Consumer<FlipCandidate> listener) {
		this.flipListener = listener == null ? c -> {
		} : listener;
	}

	public BinWatchService(Path configDir, Path dataDir, Path logDir, Clock clock) {
		this.dataDir = dataDir;
		this.clock = clock;
		this.configManager = new ConfigManager(configDir);
		ConfigManager.LoadResult<BinWatchConfig> cfg = configManager.loadConfig();
		if (cfg.ok()) {
			config = cfg.value();
		} else {
			config = new BinWatchConfig();
			startupProblems.add("config.json invalid, using defaults: " + String.join("; ", cfg.errors()));
		}
		BwLog.configure(logDir, BwLog.parseLevel(config.logging.level), config.logging.maxFileSizeMb, config.logging.maxFiles);
		startupProblems.forEach(BwLog::warn);
		ConfigManager.LoadResult<Secrets> sec = configManager.loadSecrets();
		secrets = sec.value();
		if (!sec.ok()) {
			startupProblems.addAll(sec.errors());
			sec.errors().forEach(BwLog::warn);
		}
	}

	/** Opens storage and creates all components. API collection starts if {@code api.enabled} is true. */
	public void init() {
		init(new JdkHttpTransport(Duration.ofSeconds(config.api.connectTimeoutSeconds)), AuctionApiClient.DEFAULT_BASE);
	}

	/** {@link #init()} with a given transport and API base (tests use a scripted transport). */
	public void init(HttpTransport http, java.net.URI apiBase) {
		BwLog.info("BIN Watch starting (data directory " + dataDir + ")");
		try {
			store = ObservationStore.openFile(dataDir);
			int expired = store.expireQueuedAlerts(clock.millis());
			if (expired > 0) {
				BwLog.info(expired + " alert(s) queued in a previous session were marked EXPIRED and will not be resent");
			}
		} catch (SQLException | IOException e) {
			storeError = e.toString();
			BwLog.error("Persistent storage unavailable; BIN Watch cannot record observations", e);
			return;
		}
		build(store, http, apiBase);
		maintenance = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "BINWatch-maintenance");
			t.setDaemon(true);
			return t;
		});
		maintenance.scheduleWithFixedDelay(this::maintain, 1, 5, TimeUnit.MINUTES);
		configFileModifiedMs = configFileModifiedMs();
		maintenance.scheduleWithFixedDelay(this::reloadIfConfigChanged, 3, 3, TimeUnit.SECONDS);
		maintenance.scheduleWithFixedDelay(this::tickSummary, 5, 5, TimeUnit.SECONDS);
		dispatcher.start();
		gateway = new DiscordGateway(bot, () -> secrets.discordBotToken, () -> config.discordBot.slashCommands,
				this::discordCommand);
		if (config.discordBot.enabled && secrets.hasBotToken() && !discordOff) {
			gateway.start();
		}
		BwLog.setSink(this::toLogChannel);
		feed.post(channel(config.discordBot.logChannelId), DiscordFeed.embed("BIN Watch started",
				"Collector " + (config.api.enabled ? "running" : "stopped") + ", market check "
						+ (config.market.enabled ? "on" : "off"), 0x3498DB, clock.millis()));
		if (config.api.enabled) {
			collector.start();
		}
	}

	private void build(ObservationStore st, HttpTransport http, java.net.URI base) {
		sales = new SalesHistory(50, config.valuation.salesLookbackHours * 3_600_000L);
		try {
			st.forEachSale(clock.millis() - config.valuation.salesLookbackHours * 3_600_000L,
					s -> sales.add(s.normKey(), s.endedMs(), s.unitPrice()));
		} catch (SQLException e) {
			BwLog.throttled(BwLog.Level.WARN, "sales-load", "Could not load sales history: " + e.getMessage(), null);
		}
		AuctionApiClient client = new AuctionApiClient(http, base, clock, Thread::sleep, new Random(),
				AuctionCollector.policyFrom(config.api));
		valuator = new Valuator(config);
		evaluator = new FlipEvaluator(config);
		bot = new DiscordBot(http, () -> secrets.discordBotToken);
		WebhookClient discord = new WebhookClient(http, bot);
		webhook = discord;
		feed = new DiscordFeed(discord, clock::millis, Duration.ofSeconds(10));
		dispatcher = new AlertDispatcher(st, discord, clock, Thread::sleep, config, this::alertTarget);
		dispatcher.setServerClockOffset(() -> {
			Long offset = collector == null ? null : collector.clockOffsetEstimateMs();
			return offset == null ? 0 : offset;
		});
		dispatcher.setPublishEstimates(lu -> collector == null ? null : collector.publishEstimate(lu));
		marketService = new MarketService(http, clock, () -> config);
		ledger = new TradeLedger(st, clock, () -> config);
		summaryPublisher = new SummaryPublisher(discord, clock, () -> config, this::summaryTarget, st, ledger::trades,
				() -> config.market.enabled ? "Prices provided by SkyCofl (sky.coflnet.com)" : null);
		ledger.setOnChange(summaryPublisher::markDirty);
		ledger.setEventListener((e, lines) -> feed.post(channel(config.discordBot.tradesChannelId), tradeMessage(e, lines)));
		collector = new AuctionCollector(client, st, clock, config, sales, new AuctionCollector.Listener() {
			@Override
			public void onFirstSeen(List<AuctionCollector.FirstSeen> firstSeen, PriceIndex index) {
				evaluate(firstSeen, index, false);
			}

			@Override
			public void onEnded(List<dev.binwatch.api.EndedAuction> sold) {
				endedListener.accept(sold);
			}

			@Override
			public void onSnapshot(AuctionCollector.SnapshotSummary summary) {
				long passed = candidatesPassed.get();
				if (passed > passedAtLastSnapshot) {
					BwLog.info("Snapshot " + summary.snapshotId() + ": " + (passed - passedAtLastSnapshot)
							+ " flip(s) passed filters and were offered for alerting");
				}
				passedAtLastSnapshot = passed;
				if (summary.status() == dev.binwatch.api.SnapshotStatus.COMPLETE) {
					topCandidates.keySet().removeIf(u -> !collector.isActive(u));
				}
				try {
					snapshotListener.accept(summary);
				} catch (RuntimeException e) {
					BwLog.throttled(BwLog.Level.ERROR, "snapshot-listener", "Snapshot listener failed", e);
				}
			}
		});
	}

	private void evaluate(List<AuctionCollector.FirstSeen> firstSeen, PriceIndex index, boolean synthetic) {
		long started = System.nanoTime();
		try {
			evaluateAll(firstSeen, index, synthetic);
		} finally {
			evaluationNanos.addAndGet(System.nanoTime() - started);
		}
	}

	private void evaluateAll(List<AuctionCollector.FirstSeen> firstSeen, PriceIndex index, boolean synthetic) {
		long now = clock.millis();
		Valuator v = valuator;
		FlipEvaluator ev = evaluator;
		for (AuctionCollector.FirstSeen f : firstSeen) {
			if (!f.auction().bin()) {
				continue;
			}
			String key = f.item() == null ? null : f.item().comparisonKey();
			int count = f.item() == null ? 1 : f.item().count();
			long unit = f.auction().startingBid() / Math.max(1, count);
			Valuation val = v.value(key, f.auction().uuid(), unit, index, key == null ? List.of() : sales.recent(key, now),
					key == null ? null : sales.newest(key), now);
			candidatesEvaluated.incrementAndGet();
			if (!val.valid()) {
				valuationsInvalid.incrementAndGet();
				continue;
			}
			Records.ApiObservation row = f.row();
			FlipCandidate c = ev.evaluate(new FlipEvaluator.Listing(row.auctionUuid(), f.item(), row.itemName(),
					row.startingBid(), row.bin(), row.auctionStartMs(), row.auctionEndMs(), row.firstApiObservedMs(),
					row.snapshotLastUpdatedMs(), row.sourceStatus(), row.observationKind(), synthetic), val);
			if (c.estimatedNetProfit() > 0) {
				rememberCandidate(c);
			}
			if (c.passed()) {
				candidatesPassed.incrementAndGet();
				BwLog.info("Flip candidate passed filters: " + c.auctionUuid() + " " + c.itemName() + " price "
						+ c.price() + " est. net " + c.estimatedNetProfit() + " conf "
						+ String.format(Locale.ROOT, "%.2f", val.confidence()));
				String bytes = f.auction().itemBytes();
				if (!synthetic && config.market.enabled && bytes != null && marketService != null) {
					marketService.submit(c, bytes, checked -> deliver(checked, synthetic));
				} else {
					deliver(c, synthetic);
				}
			} else if (c.estimatedNetProfit() > 0) {
				BwLog.debug("Rejected " + c.auctionUuid() + " " + c.itemName() + ": " + String.join("; ", c.rejectReasons()));
			}
		}
	}

	/**
	 * Alerts a flip that passed the local filters, after the market check (when there was one) agreed. A flip the
	 * market check rejects is not alerted when {@code market.requireWorthIt} is on.
	 */
	private void deliver(FlipCandidate c, boolean synthetic) {
		MarketCheck m = c.market();
		BinWatchConfig cfg = config;
		if (m != null) {
			rememberCandidate(c);
			if (!m.available() && (!cfg.market.alertWhenUnavailable || !FlipEvaluator.meetsStrictFilters(c, cfg.filters))) {
				// Without market data only flips that clear the strict local filters are alerted.
				BwLog.info("Not alerted (no market data: " + m.error() + "): " + c.auctionUuid() + " " + c.itemName());
				return;
			}
			if (m.available() && !m.worthIt() && cfg.market.requireWorthIt) {
				BwLog.info("Not alerted (market check: " + String.join("; ", m.reasons()) + "): " + c.auctionUuid() + " "
						+ c.itemName());
				return;
			}
		}
		if (!synthetic) {
			try {
				opportunityListener.accept(c);
			} catch (RuntimeException e) {
				BwLog.throttled(BwLog.Level.WARN, "opportunity", "Opportunity listener failed: " + e, null);
			}
		}
		AlertDispatcher.OfferResult r = dispatcher.offer(c);
		BwLog.debug("Alert offer for " + c.auctionUuid() + ": " + r);
		boolean alerted = !synthetic && (r == AlertDispatcher.OfferResult.QUEUED
				|| r == AlertDispatcher.OfferResult.NO_WEBHOOK || r == AlertDispatcher.OfferResult.DISABLED);
		if (alerted && config.chat.alerts) {
			try {
				flipListener.accept(c);
			} catch (RuntimeException e) {
				BwLog.throttled(BwLog.Level.WARN, "chat-alert", "Chat alert failed: " + e, null);
			}
		}
		if (alerted) {
			try {
				autoViewListener.accept(c);
			} catch (RuntimeException e) {
				BwLog.throttled(BwLog.Level.WARN, "auto-view", "Auto-view hand-off failed: " + e, null);
			}
		}
		if (alerted && ledger != null) {
			ledger.rememberAlert(c);
		}
	}

	/** Alerts go to the bot's alerts channel when set up, otherwise to the webhook. */
	private String alertTarget() {
		if (discordOff) {
			return null;
		}
		String t = channel(config.discordBot.alertsChannelId);
		return t != null ? t : secrets.discordWebhookUrl;
	}

	private String summaryTarget() {
		if (discordOff) {
			return null;
		}
		String t = channel(config.discordBot.summaryChannelId);
		return t != null ? t : secrets.summaryWebhookUrl();
	}

	/** {@code channel:<id>} when the bot is enabled, has a token and the channel id is set; otherwise null. */
	private String channel(String channelId) {
		return !discordOff && bot != null && config.discordBot.enabled ? bot.target(channelId) : null;
	}

	private JsonObject tradeMessage(TradeChatParser.Event e, List<String> lines) {
		String title;
		int color;
		switch (e) {
			case TradeChatParser.Purchased p -> {
				title = "Bought";
				color = 0x3498DB;
			}
			case TradeChatParser.Listed l -> {
				title = "Listed";
				color = 0x95A5A6;
			}
			case TradeChatParser.Sold so -> {
				title = "Sold";
				color = 0x2ECC71;
			}
			case TradeChatParser.Collected c -> {
				title = "Collected";
				color = 0xF1C40F;
			}
			case TradeChatParser.Cancelled c -> {
				title = "Cancelled";
				color = 0x95A5A6;
			}
		}
		String text = lines.isEmpty() ? e.item() : String.join("\n", lines);
		return DiscordFeed.embed(title + ": " + e.item(), text, color, clock.millis());
	}

	/** WARN and ERROR lines go to the log channel, at most 20 per 10 minutes; Discord's own problems are not echoed. */
	private void toLogChannel(BwLog.Level level, String msg) {
		String target = channel(config.discordBot.logChannelId);
		if (target == null || msg.startsWith(DiscordFeed.LOG_PREFIX) || msg.startsWith("Discord")) {
			return;
		}
		long now = clock.millis();
		synchronized (logPosts) {
			while (!logPosts.isEmpty() && now - logPosts.peekFirst() > 600_000) {
				logPosts.pollFirst();
			}
			if (logPosts.size() >= 20) {
				return;
			}
			logPosts.addLast(now);
		}
		feed.post(target, DiscordFeed.embed(level == BwLog.Level.ERROR ? "Error" : "Warning", msg,
				level == BwLog.Level.ERROR ? 0xE74C3C : 0xE67E22, now));
	}

	/** Answers the bot's slash commands. */
	private JsonObject discordCommand(String name) {
		long now = clock.millis();
		return switch (name) {
			case "profit" -> {
				try {
					yield ProfitSummary.payload(ProfitSummary.compute(ledger.trades(), now, config.summary.topCount), now,
							config.market.enabled ? "Prices provided by SkyCofl (sky.coflnet.com)" : null);
				} catch (SQLException e) {
					yield DiscordFeed.embed("Profit unavailable", "Database error", 0xE74C3C, now);
				}
			}
			case "status" -> DiscordFeed.embed("BIN Watch status", String.join("\n", statusLines()), 0x3498DB, now);
			default -> null;
		};
	}

	private void tickSummary() {
		try {
			if (summaryPublisher != null && config.discord.enabled) {
				summaryPublisher.tick();
			}
		} catch (RuntimeException e) {
			BwLog.throttled(BwLog.Level.ERROR, "summary-tick", "Profit summary update failed", e);
		}
	}

	/** Handles one chat line from the server (formatting removed); returns lines to show the player. */
	public List<String> onGameChat(String line) {
		TradeLedger l = ledger;
		return l == null ? List.of() : l.onChat(line);
	}

	/** Lines for {@code /binwatch profit}. */
	public List<String> profitLines() {
		if (ledger == null) {
			return List.of("BIN Watch: " + notReadyReason());
		}
		try {
			return ProfitSummary.text(ProfitSummary.compute(ledger.trades(), clock.millis(), config.summary.topCount));
		} catch (SQLException e) {
			return List.of("Profit unavailable: " + e.getMessage());
		}
	}

	/** Publishes the Discord profit summary now. */
	public CompletableFuture<String> publishSummary() {
		if (summaryPublisher == null) {
			return CompletableFuture.completedFuture("BIN Watch: " + notReadyReason());
		}
		return CompletableFuture.supplyAsync(() -> summaryPublisher.publish(true));
	}

	private void rememberCandidate(FlipCandidate c) {
		topCandidates.put(c.auctionUuid(), c);
		if (topCandidates.size() > TOP_CANDIDATES * 2) {
			topCandidates.values().stream()
					.sorted(Comparator.comparingLong(FlipCandidate::estimatedNetProfit))
					.limit(topCandidates.size() - TOP_CANDIDATES)
					.map(FlipCandidate::auctionUuid)
					.toList()
					.forEach(topCandidates::remove);
		}
	}

	private void maintain() {
		try {
			long now = clock.millis();
			sales.prune(now);
			if (now - lastPruneMs > 3_600_000L) {
				lastPruneMs = now;
				int deleted = store.prune(now, config.storage.retentionDays, config.storage.salesRetentionDays,
						config.storage.maxApiObservationRows);
				BwLog.info("Retention pruning removed " + deleted + " rows");
			}
			if (config.export.autoExportCsv && now - lastAutoExportMs > config.export.autoExportIntervalMinutes * 60_000L) {
				lastAutoExportMs = now;
				exportCsv();
			}
		} catch (SQLException | IOException | RuntimeException e) {
			BwLog.throttled(BwLog.Level.ERROR, "maintenance", "Maintenance task failed", e);
		}
	}

	// ---- operations used by commands and GUI -------------------------------------------------------------------------

	public boolean ready() {
		return store != null && collector != null;
	}

	public String notReadyReason() {
		return storeError == null ? "BIN Watch is still starting" : "storage unavailable: " + storeError;
	}

	public String startApi() {
		if (!ready()) {
			return notReadyReason();
		}
		if (collector.running()) {
			return "API collector is already running";
		}
		collector.start();
		return "API collector started";
	}

	public String stopApi() {
		if (!ready()) {
			return notReadyReason();
		}
		if (!collector.running()) {
			return "API collector is not running";
		}
		collector.stop();
		return "API collector stopped";
	}

	public CompletableFuture<WebhookClient.Result> testWebhook() {
		return testWebhook(false);
	}

	/** @param preview true sends a sample flip alert marked [TEST DATA] instead of the plain test message */
	public CompletableFuture<WebhookClient.Result> testWebhook(boolean preview) {
		if (!ready()) {
			return CompletableFuture.completedFuture(new WebhookClient.Result(WebhookClient.Outcome.PERMANENT_ERROR, 0, 0,
					notReadyReason()));
		}
		return preview ? dispatcher.sendPreview() : dispatcher.sendTest();
	}

	private long configFileModifiedMs() {
		try {
			return java.nio.file.Files.getLastModifiedTime(configManager.configFile()).toMillis();
		} catch (IOException | RuntimeException e) {
			return 0;
		}
	}

	/** Picks up edits to config.json without a command, so settings can be changed while the game runs. */
	private void reloadIfConfigChanged() {
		long m = configFileModifiedMs();
		if (m != 0 && m != configFileModifiedMs) {
			configFileModifiedMs = m;
			BwLog.info("config.json changed on disk; reloading");
			reload();
		}
	}

	/** Reloads config.json and secrets.json. An invalid config is rejected and the running config is kept. */
	public List<String> reload() {
		List<String> messages = new ArrayList<>();
		ConfigManager.LoadResult<BinWatchConfig> cfg = configManager.loadConfig();
		if (!cfg.ok()) {
			messages.add("config.json rejected, previous configuration kept:");
			messages.addAll(cfg.errors());
		} else {
			config = cfg.value();
			BwLog.setLevel(BwLog.parseLevel(config.logging.level));
			if (ready()) {
				collector.applyConfig(config);
				dispatcher.applyConfig(config);
				sales.setLookbackMs(config.valuation.salesLookbackHours * 3_600_000L);
				valuator = new Valuator(config);
				evaluator = new FlipEvaluator(config);
			}
			messages.add("config.json reloaded (start/stop state of each source unchanged; poll pool size applies after restart of the collector)");
		}
		ConfigManager.LoadResult<Secrets> sec = configManager.loadSecrets();
		secrets = sec.value();
		if (sec.ok()) {
			messages.add("secrets.json reloaded: Discord webhook " + (secrets.hasWebhook() ? "configured" : "not configured"));
		} else {
			messages.addAll(sec.errors());
		}
		BwLog.info("Configuration reload: " + String.join(" | ", messages));
		return messages;
	}

	public List<String> statusLines() {
		List<String> out = new ArrayList<>();
		if (!ready()) {
			out.add("BIN Watch: " + notReadyReason());
			return out;
		}
		long now = clock.millis();
		String apiState = collector.running() ? (collector.lastError() != null ? "Running (last error: " + collector.lastError() + ")" : "Running") : "Stopped";
		out.add("API: " + apiState);
		if (config.api.snapshotTimedPolling) {
			dev.binwatch.api.SnapshotClock sc = collector.snapshotClock();
			out.add("  Snapshot-timed polling: snapshot every " + TimeFmt.duration(sc.avgIntervalMs()) + ", published ~"
					+ TimeFmt.duration(sc.minPublishDelayMs()) + " after its lastUpdated; probes " + collector.probes.get()
					+ ", bursts given up " + collector.burstGiveUps.get() + ", stale page retries " + collector.staleRetriesTotal.get());
		} else {
			out.add("  Poll interval " + config.api.pollIntervalSeconds + " s (Hypixel caches this endpoint; data may be older than the interval)");
		}
		out.add("  Last successful poll: " + ago(collector.lastSuccessfulPollMs(), now)
				+ (collector.running() && collector.nextPollAtMs() > 0 ? ", next in " + TimeFmt.duration(Math.max(0, collector.nextPollAtMs() - now)) : ""));
		AuctionCollector.SnapshotSummary s = collector.lastSummary();
		if (s != null) {
			out.add("  Last snapshot: lastUpdated " + TimeFmt.utc(s.lastUpdatedMs()) + ", " + s.status() + ", pages "
					+ s.pagesFetched() + "/" + s.totalPages() + (s.failedPages().isEmpty() ? "" : " (failed " + s.failedPages() + ")")
					+ ", " + s.auctionsSeen() + " auctions, " + s.firstSeen() + " first seen"
					+ (s.detectLagMs() == null ? "" : ", page 0 received " + TimeFmt.duration(s.detectLagMs()) + " after lastUpdated"));
		} else {
			out.add("  Last snapshot: none yet");
		}
		out.add("  Auctions first observed (this session): " + collector.firstSeenTotal.get() + ", tracked active: " + collector.activeAuctions());
		try {
			out.add("  API observations stored: " + store.count("api_obs") + " (last "
					+ ago(orZero(store.latestObservationMs()), now) + ")");
		} catch (SQLException e) {
			out.add("  API observations stored: unavailable (" + e.getMessage() + ")");
		}
		String alerts = alertTarget();
		out.add("Discord: " + (!config.discord.enabled ? "disabled" : DiscordBot.isTarget(alerts) ? "alerts to bot channel"
				: alerts != null && !alerts.isBlank() ? "webhook configured" : "no webhook or bot channel configured")
				+ ", delivered " + dispatcher.delivered.get() + ", failed " + dispatcher.failed.get() + ", dropped "
				+ dispatcher.dropped.get() + ", queued " + dispatcher.queued());
		BinWatchConfig.Market mk = config.market;
		out.add("Market (SkyCofl): " + (!mk.enabled ? "disabled" : "checked " + marketService.checked + ", worth it "
				+ marketService.worthIt + ", not worth it " + marketService.rejected + ", unavailable " + marketService.unavailable
				+ (marketService.lastError() == null ? "" : " (last error: " + marketService.lastError() + ")")));
		out.add("Profit summary: " + (!config.summary.enabled ? "disabled" : summaryPublisher.lastResult()));
		out.add("Discord bot: " + (!config.discordBot.enabled ? "disabled" : !secrets.hasBotToken() ? "no token in secrets.json"
				: gateway == null ? "not started" : gateway.status()));
		out.add("Flips: evaluated " + candidatesEvaluated + ", passed filters " + candidatesPassed + ", no valid valuation " + valuationsInvalid);
		return out;
	}

	public List<String> diagnosticsLines() {
		List<String> out = new ArrayList<>(statusLines());
		if (!ready()) {
			out.addAll(startupProblems);
			return out;
		}
		AuctionApiClient c = collector.client();
		out.add("Diagnostics:");
		out.add("  API requests " + c.requests.get() + ", retries " + c.retries.get() + ", HTTP 429 " + c.rateLimitedResponses.get()
				+ ", failed " + c.failedRequests.get() + ", item decode failures " + collector.decodeFailures.get());
		out.add("  Last CDN cache age: " + (collector.lastCdnAgeSeconds() == null ? "n/a" : collector.lastCdnAgeSeconds() + " s")
				+ ", local clock vs API Date header: " + (collector.clockOffsetEstimateMs() == null ? "n/a"
						: TimeFmt.signedDuration(collector.clockOffsetEstimateMs()) + " (best of recent Date headers)"));
		PriceIndex idx = collector.index();
		out.add("  Price index: " + (idx == null ? "not built yet" : idx.keys() + " keys from " + idx.snapshotStatus()
				+ " snapshot, built " + ago(idx.builtAtMs(), clock.millis())));
		out.add("  Sales history keys: " + sales.keys() + ", sales recorded this session: " + collector.salesRecorded.get());
		try {
			out.add("  Stored rows: api " + store.count("api_obs") + ", alerts " + store.count("alerts") + ", sales "
					+ store.count("sales"));
		} catch (SQLException e) {
			out.add("  Stored rows: unavailable (" + e.getMessage() + ")");
		}
		out.add("  Database: " + store.description());
		out.add("  Log file: " + BwLog.logFile());
		out.add("  Discord last error: " + (dispatcher.lastDeliveryError() == null ? "none" : dispatcher.lastDeliveryError()));
		out.addAll(startupProblems);
		return out;
	}

	public List<FlipCandidate> topCandidates(int n) {
		return topCandidates.values().stream()
				.sorted(Comparator.comparing(FlipCandidate::passed).reversed()
						.thenComparing(Comparator.comparingLong(FlipCandidate::estimatedNetProfit).reversed()))
				.limit(n)
				.toList();
	}

	public TimingReport timingReport(long windowMs) throws SQLException {
		long now = clock.millis();
		List<String> conditions = new ArrayList<>();
		conditions.add("API collector: " + (collector.running() ? "running" : "stopped") + ", "
				+ (config.api.snapshotTimedPolling ? "snapshot-timed polling (" + config.api.burstIntervalMs + " ms probes)"
						: "fixed " + config.api.pollIntervalSeconds + " s interval"));
		return TimingReport.build(store, now - windowMs, now, config.api.clockSkewToleranceMs, conditions);
	}

	public Path writeReport(long windowMs) throws SQLException, IOException {
		long now = clock.millis();
		List<String> lines = new ArrayList<>(timingReport(windowMs).toLines());
		lines.add("");
		lines.addAll(diagnosticsLines());
		return CsvExporter.writeText(dataDir.resolve("exports").resolve("report_" + CsvExporter.stamp(now) + ".txt"), lines);
	}

	public List<Path> exportCsv() throws SQLException, IOException {
		long now = clock.millis();
		long windowMs = config.storage.retentionDays * 86_400_000L;
		List<Path> files = new ArrayList<>(CsvExporter.exportAll(store, dataDir.resolve("exports"), now - windowMs, now,
				config.export.maxRowsPerFile));
		files.add(CsvExporter.writeText(dataDir.resolve("exports").resolve("timing_report_" + CsvExporter.stamp(now)
				+ ".txt"), timingReport(windowMs).toLines()));
		BwLog.info("CSV export written to " + dataDir.resolve("exports"));
		return files;
	}

	/** Listings valued so far (all first-seen BIN auctions) and the CPU time spent valuing and filtering them. */
	public long candidatesEvaluated() {
		return candidatesEvaluated.get();
	}

	public long evaluationNanos() {
		return evaluationNanos.get();
	}

	/** Whether the auction was in the newest snapshot (removals are only applied after a COMPLETE one). */
	public boolean isListed(UUID auctionUuid) {
		return collector != null && collector.isActive(auctionUuid);
	}

	/** Null before {@link #init()} succeeded. */
	public AuctionCollector collector() {
		return collector;
	}

	public ObservationStore store() {
		return store;
	}

	public WebhookClient webhookClient() {
		return webhook;
	}

	/** The profit tracker webhook from secrets.json; never log it. Empty when not set. */
	public String profitWebhookUrl() {
		return secrets.hasProfitWebhook() ? secrets.discordProfitWebhookUrl : "";
	}

	/** The alert webhook URL from secrets.json or the environment; never log it. Empty when not set. */
	public String webhookUrl() {
		return secrets.hasWebhook() ? secrets.discordWebhookUrl : "";
	}

	public BinWatchConfig config() {
		return config;
	}

	public Path dataDir() {
		return dataDir;
	}

	/** When Hypixel published the snapshot with this lastUpdated, if the collector bracketed it. */
	public AuctionCollector.PublishEstimate publishEstimate(long lastUpdatedMs) {
		return collector == null ? null : collector.publishEstimate(lastUpdatedMs);
	}

	public Path configFile() {
		return configManager.configFile();
	}

	public Path secretsFile() {
		return configManager.secretsFile();
	}

	public boolean apiRunning() {
		return ready() && collector.running();
	}

	/**
	 * Runs the whole pipeline once against synthetic data in a private in-memory database with a scripted transport.
	 * Nothing touches the network, the live database, or Discord.
	 */
	public List<String> runSyntheticTest() {
		List<String> out = new ArrayList<>();
		out.add("[TEST DATA] Synthetic pipeline run (in-memory database, no network, no Discord)");
		try (ObservationStore mem = ObservationStore.openInMemory("binwatch-testmode-" + UUID.randomUUID())) {
			long now = clock.millis();
			List<SyntheticData.Auction> market = new ArrayList<>();
			for (int i = 0; i < 6; i++) {
				market.add(SyntheticData.Auction.bin("TEST_SWORD", 10_000_000L + i * 100_000L, now - 3_600_000L));
			}
			UUID cheapUuid = UUID.randomUUID();
			ScriptedTransport t = new ScriptedTransport();
			t.enqueue("auctions?page=0",
					() -> ScriptedTransport.ok(SyntheticData.pageJson(0, 1, now - 60_000, market)),
					() -> {
						// Listed after the synthetic collector started, so it counts as created during monitoring.
						List<SyntheticData.Auction> next = new ArrayList<>(market);
						next.add(SyntheticData.Auction.bin("TEST_SWORD", 6_000_000L, clock.millis()).withUuid(cheapUuid));
						return ScriptedTransport.ok(SyntheticData.pageJson(0, 1, clock.millis(), next));
					});
			t.whenEmpty("auctions_ended", u -> ScriptedTransport.ok(SyntheticData.endedJson(now, List.of(), now)));
			BinWatchConfig cfg = config;
			SalesHistory hist = new SalesHistory(50, cfg.valuation.salesLookbackHours * 3_600_000L);
			AuctionApiClient client = new AuctionApiClient(t, java.net.URI.create("https://synthetic.invalid"), clock,
					ms -> { }, new Random(1), AuctionCollector.policyFrom(cfg.api));
			List<FlipCandidate> found = new ArrayList<>();
			Valuator val = new Valuator(cfg);
			FlipEvaluator ev = new FlipEvaluator(cfg);
			AuctionCollector col = new AuctionCollector(client, mem, clock, cfg, hist, new AuctionCollector.Listener() {
				@Override
				public void onFirstSeen(List<AuctionCollector.FirstSeen> firstSeen, PriceIndex index) {
					for (AuctionCollector.FirstSeen f : firstSeen) {
						String key = f.item() == null ? null : f.item().comparisonKey();
						Valuation v = val.value(key, f.auction().uuid(), f.auction().startingBid(), index, List.of(), null, clock.millis());
						Records.ApiObservation r = f.row();
						found.add(ev.evaluate(new FlipEvaluator.Listing(r.auctionUuid(), f.item(), r.itemName(), r.startingBid(),
								r.bin(), r.auctionStartMs(), r.auctionEndMs(), r.firstApiObservedMs(), r.snapshotLastUpdatedMs(),
								r.sourceStatus(), r.observationKind(), true), v));
					}
				}

				@Override
				public void onSnapshot(AuctionCollector.SnapshotSummary summary) {
				}
			});
			AuctionCollector.PollResult first = col.pollOnce(null);
			AuctionCollector.PollResult second = col.pollOnce(null);
			out.add("Poll 1: " + first.outcome() + " " + (first.summary() == null ? "" : first.summary().status()
					+ ", first seen " + first.summary().firstSeen()));
			out.add("Poll 2: " + second.outcome() + " " + (second.summary() == null ? "" : second.summary().status()
					+ ", first seen " + second.summary().firstSeen()));
			for (FlipCandidate c : found) {
				if (c.auctionUuid().equals(cheapUuid)) {
					out.add("Cheap synthetic listing: price " + TimeFmt.shortCoins(c.price()) + ", est. resale "
							+ TimeFmt.shortCoins(c.estimatedResale()) + ", est. net " + TimeFmt.shortCoins(c.estimatedNetProfit())
							+ ", confidence " + String.format(Locale.ROOT, "%.2f", c.valuation().confidence())
							+ " -> " + (c.passed() ? "PASSES filters" : "rejected: " + String.join("; ", c.rejectReasons())));
					AlertDispatcher.OfferResult r = dispatcher == null ? AlertDispatcher.OfferResult.SYNTHETIC_NOT_SENT : dispatcher.offer(c);
					out.add("Discord: " + r + " (synthetic data is never sent)");
				}
			}
			out.add("Pipeline OK. The [TEST] items above are synthetic and are not live opportunities.");
		} catch (SQLException | RuntimeException e) {
			out.add("Synthetic run failed: " + e);
			BwLog.error("Synthetic test mode failed", e);
		}
		return out;
	}

	private static long orZero(Long v) {
		return v == null ? 0 : v;
	}

	private static String ago(long ms, long now) {
		if (ms <= 0) {
			return "never";
		}
		return TimeFmt.utc(ms) + " (" + TimeFmt.duration(Math.max(0, now - ms)) + " ago)";
	}

	@Override
	public void close() {
		BwLog.info("BIN Watch shutting down");
		if (collector != null) {
			collector.stop();
		}
		if (dispatcher != null) {
			dispatcher.stop();
		}
		if (marketService != null) {
			marketService.shutdown();
		}
		BwLog.setSink(null);
		if (gateway != null) {
			gateway.stop();
		}
		if (feed != null) {
			feed.shutdown();
		}
		if (maintenance != null) {
			maintenance.shutdownNow();
		}
		if (store != null) {
			store.close();
		}
	}
}
