package dev.binwatch.standalone;

import com.google.gson.JsonObject;
import dev.binwatch.api.AuctionApiClient;
import dev.binwatch.api.AuctionCollector;
import dev.binwatch.api.HttpTransport;
import dev.binwatch.api.JdkHttpTransport;
import dev.binwatch.api.SnapshotStatus;
import dev.binwatch.config.BinWatchConfig;
import dev.binwatch.config.ConfigManager;
import dev.binwatch.discord.SummaryPublisher;
import dev.binwatch.discord.WebhookClient;
import dev.binwatch.log.BwLog;
import dev.binwatch.service.BinWatchService;
import dev.binwatch.store.AtomicFiles;
import dev.binwatch.store.ObservationStore;
import dev.binwatch.value.FlipCandidate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BIN Watch without Minecraft: runs the mod's own collector, valuation, filters and SkyCofl market check against the
 * official Hypixel API and keeps one "AH Flipper" Discord message listing the best opportunities that are still listed.
 * Read-only: it never buys, bids or connects to the game. Stops cleanly on Ctrl+C.
 */
public final class StandaloneMain {
	static final String VERSION = "1.0.0";
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

	record Options(Path dir, boolean dryRun, boolean setup, long runForSeconds, boolean verbose, boolean help) {
		static Options parse(String[] args) {
			Path dir = Path.of("binwatch-data");
			boolean dry = false;
			boolean setup = false;
			boolean verbose = false;
			boolean help = false;
			long runFor = 0;
			for (int i = 0; i < args.length; i++) {
				switch (args[i]) {
					case "--dir" -> dir = Path.of(value(args, ++i, "--dir"));
					case "--dry-run" -> dry = true;
					case "--setup" -> setup = true;
					case "--verbose", "-v" -> verbose = true;
					case "--run-for" -> {
						try {
							runFor = Long.parseLong(value(args, ++i, "--run-for"));
						} catch (NumberFormatException e) {
							throw new IllegalArgumentException("--run-for needs a number of seconds");
						}
					}
					case "--help", "-h" -> help = true;
					default -> throw new IllegalArgumentException("unknown option " + args[i] + " (see --help)");
				}
			}
			return new Options(dir, dry, setup, runFor, verbose, help);
		}

		private static String value(String[] args, int i, String name) {
			if (i >= args.length) {
				throw new IllegalArgumentException(name + " needs a value");
			}
			return args[i];
		}
	}

	private final Options opt;
	private final ConsoleOut console;
	private final Metrics metrics = new Metrics();
	private final AtomicBoolean stopping = new AtomicBoolean();
	private final CountDownLatch stopped = new CountDownLatch(1);
	private BinWatchService service;
	private FlipBoard board;
	private BoardPublisher publisher;
	private Path boardFile;
	private java.util.concurrent.ScheduledExecutorService health;
	private ProfitTracker profit;
	private BoardPublisher profitPublisher;
	private dev.binwatch.discord.DiscordFeed profitFeed;
	private Path profitFile;
	/** The flips in the board message most recently rendered (worker thread), for the profit tracker. */
	private volatile java.util.List<FlipBoard.Entry> lastShown = java.util.List.of();
	private volatile AuctionCollector.SnapshotSummary lastSnapshot;
	private long lastEvalNanos;
	private long lastEvaluated;
	// Tests replace the network and the keyboard; null in normal runs.
	HttpTransport http;
	URI apiBase;
	FirstRunSetup.Input input;

	StandaloneMain(Options opt, PrintStream out) {
		this.opt = opt;
		this.console = new ConsoleOut(out, opt.verbose());
	}

	public static void main(String[] args) {
		Options opt;
		try {
			opt = Options.parse(args);
		} catch (IllegalArgumentException e) {
			System.err.println(e.getMessage());
			System.exit(64);
			return;
		}
		if (opt.help()) {
			System.out.println(usage());
			return;
		}
		StandaloneMain app = new StandaloneMain(opt, System.out);
		int code = app.start();
		if (code != 0) {
			System.exit(code);
		}
		Runtime.getRuntime().addShutdownHook(new Thread(app::stop, "BINWatch-shutdown"));
		try {
			if (opt.runForSeconds() > 0) {
				app.stopped.await(opt.runForSeconds(), java.util.concurrent.TimeUnit.SECONDS);
				System.exit(0);
			} else {
				app.stopped.await();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	static String usage() {
		return String.join(System.lineSeparator(),
				"BIN Watch standalone " + VERSION + " - Hypixel SkyBlock BIN flips to one Discord message, no Minecraft needed",
				"",
				"Usage: java -jar binwatch-standalone-" + VERSION + ".jar [options]",
				"  --dir <path>      where config, data and logs live (default: ./binwatch-data)",
				"  --dry-run         scan and build the embed, but send nothing to Discord",
				"  --setup           enter the webhook and main thresholds again",
				"  --verbose, -v     also print the detailed log lines",
				"  --run-for <s>     stop after this many seconds (benchmarks)",
				"  --help            this text",
				"",
				"Stop with Ctrl+C.");
	}

	/** Configures, opens storage and starts scanning. Returns a non-zero exit code when it cannot run. */
	int start() {
		BwLog.setOutput(console::fromLog);
		console.line("STARTUP", "BIN Watch standalone " + VERSION + " (read-only: it never buys, bids or joins the game)");
		Path configDir = opt.dir().resolve("config");
		Path dataDir = opt.dir().resolve("data");
		Path logDir = opt.dir().resolve("logs");
		console.line("STARTUP", "Loading configuration from " + configDir.toAbsolutePath());
		ConfigManager files = new ConfigManager(configDir);
		boolean firstRun = !Files.exists(files.configFile());
		BinWatchConfig cfg;
		try {
			if (firstRun) {
				Files.createDirectories(configDir);
				AtomicFiles.writeString(files.configFile(), ConfigManager.GSON.toJson(FirstRunSetup.standaloneDefaults()));
				console.line("CONFIG", "First run: created " + files.configFile());
			}
			ConfigManager.LoadResult<BinWatchConfig> loaded = files.loadConfig();
			if (!loaded.ok()) {
				console.line("ERROR", "config.json is invalid: " + String.join("; ", loaded.errors()));
				console.line("ERROR", "Fix " + files.configFile() + " or delete it to start again with defaults.");
				return 2;
			}
			cfg = loaded.value();
			FirstRunSetup setup = new FirstRunSetup(input != null ? input : consoleInput(), console,
					() -> http != null ? http : new JdkHttpTransport(Duration.ofSeconds(10)), files);
			if (!opt.dryRun() || opt.setup()) {
				if (!setup.ensureWebhook(opt.setup())) {
					console.line("ERROR", "No Discord webhook configured. Run in a terminal to be asked for one, or use "
							+ "--dry-run to scan without Discord.");
					return 2;
				}
			}
			if ((firstRun || opt.setup()) && (input != null || terminal())) {
				if (!opt.dryRun() && cfg.profit.enabled) {
					setup.askProfitWebhook();
				}
				setup.askThresholds(cfg);
			}
		} catch (IOException e) {
			console.line("ERROR", "Could not write the configuration: " + e.getMessage());
			return 2;
		}

		service = new BinWatchService(configDir, dataDir, logDir, Clock.systemUTC());
		service.setDiscordOff(opt.dryRun());
		cfg = service.config();
		console.line("CONFIG", "Configuration validated: min profit " + BoardEmbed.coins(cfg.filters.minNetProfit)
				+ " after fees, min discount " + fmt(cfg.filters.minDiscountPercent) + "%, confidence "
				+ fmt(cfg.filters.minConfidence) + ", market check " + (cfg.market.enabled ? "on (SkyCofl)" : "off")
				+ ", board shows " + cfg.board.maxFlips + (cfg.discord.enabled ? ", per-flip alert messages on" : ""));
		if (opt.dryRun()) {
			console.line("CONFIG", "Dry run: nothing will be sent to Discord.");
		}

		board = new FlipBoard(() -> service.config().board);
		boardFile = dataDir.resolve("board.json");
		try {
			int restored = board.restore(boardFile, System.currentTimeMillis());
			if (restored > 0) {
				console.line("STARTUP", "Restored " + restored + " opportunit" + (restored == 1 ? "y" : "ies")
						+ " from the last run; shown as not re-checked until the first complete snapshot");
			}
		} catch (IOException e) {
			console.line("WARN", "Previous board state ignored: " + e.getMessage());
		}
		profit = new ProfitTracker(() -> service.config().profit);
		profitFile = dataDir.resolve("profit.json");
		try {
			profit.restore(profitFile);
		} catch (IOException e) {
			console.line("WARN", "Previous profit tracker state ignored: " + e.getMessage());
		}
		profitPublisher = new BoardPublisher("Profit summary", () -> sender(service.profitWebhookUrl()),
				messageIds("profit", service::profitWebhookUrl), this::renderProfit, () -> 10_000,
				Duration.ofSeconds(service.config().discord.requestTimeoutSeconds), new Metrics(), console, opt.dryRun(),
				System::currentTimeMillis);
		publisher = new BoardPublisher("Embed", () -> sender(service.webhookUrl()), messageIds("board", service::webhookUrl),
				this::render, () -> service.config().board.minEditIntervalMs,
				Duration.ofSeconds(service.config().discord.requestTimeoutSeconds), metrics, console, opt.dryRun(),
				System::currentTimeMillis);
		service.setOpportunityListener(this::onOpportunity);
		service.setSnapshotListener(this::onSnapshot);
		service.setEndedListener(this::onSold);
		publisher.setOnDelivered(this::onBoardDelivered);

		console.line("API", "Connecting to the official Hypixel API (" + AuctionApiClient.DEFAULT_BASE
				+ "/v2/skyblock/auctions, public, no API key needed)...");
		if (http != null) {
			service.init(http, apiBase);
		} else {
			service.init();
		}
		if (!service.ready()) {
			console.line("ERROR", "Cannot start: " + service.notReadyReason());
			service.close();
			return 1;
		}
		if (!opt.dryRun()) {
			profitFeed = new dev.binwatch.discord.DiscordFeed(service.webhookClient(), System::currentTimeMillis,
					Duration.ofSeconds(service.config().discord.requestTimeoutSeconds));
		}
		if (service.config().profit.enabled) {
			console.line("PROFIT", "Profit tracker on: " + (service.profitWebhookUrl().isEmpty()
					? "no profit webhook set, so results are only shown here (add one with --setup)"
					: "results go to the profit webhook") + ".");
		}
		if (!service.config().api.enabled) {
			console.line("WARN", "api.enabled is false in config.json, so no auctions are fetched.");
		}
		console.line("SCAN", "Waiting for the next snapshot (Hypixel publishes one about every 60 s)...");
		requestBoard(0);
		requestProfit();
		// Re-render once a minute so a stalled data source shows as a problem; unchanged content sends nothing.
		health = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "BINWatch-health");
			t.setDaemon(true);
			return t;
		});
		health.scheduleWithFixedDelay(() -> requestBoard(0), 60, 60, java.util.concurrent.TimeUnit.SECONDS);
		return 0;
	}

	/** Ctrl+C or {@code --run-for} elapsed: show "Stopped" in Discord, save state, close everything. Idempotent. */
	void stop() {
		if (!stopping.compareAndSet(false, true)) {
			return;
		}
		console.line("SHUTDOWN", "Stopping...");
		try {
			if (health != null) {
				health.shutdownNow();
			}
			if (service != null && service.collector() != null) {
				service.collector().stop();
			}
			if (publisher != null && service.config().board.enabled) {
				if (!publisher.publishNow(Duration.ofSeconds(5)) && !opt.dryRun()) {
					console.line("SHUTDOWN", "Discord did not confirm the stopped state in time");
				}
				publisher.close();
			}
			if (profitPublisher != null) {
				if (service.config().profit.enabled) {
					profitPublisher.publishNow(Duration.ofSeconds(5));
				}
				profitPublisher.close();
			}
			if (profitFeed != null) {
				profitFeed.shutdown();
			}
			saveBoard();
			saveProfit();
			if (service != null) {
				service.close();
			}
			printSummary();
		} finally {
			console.line("SHUTDOWN", "Stopped.");
			stopped.countDown();
		}
	}

	// ---- events ------------------------------------------------------------------------------------------------

	private void onOpportunity(FlipCandidate c) {
		if (stopping.get()) {
			return;
		}
		FlipBoard.Diff diff = board.offer(c);
		if (!diff.any()) {
			return;
		}
		long now = System.currentTimeMillis();
		long sincePage = Math.max(0, now - c.firstApiObservedMs());
		metrics.opportunitiesAdded.addAndGet(diff.added().size());
		metrics.opportunitiesChanged.addAndGet(diff.changed().size());
		metrics.opportunitiesRemoved.addAndGet(diff.removed().size());
		for (FlipBoard.Entry e : diff.added()) {
			metrics.detectionToBoardMs.add(sincePage);
			console.line("FLIPS", "New: " + e.name() + " · buy " + BoardEmbed.coins(e.price()) + " → resale "
					+ BoardEmbed.coins(e.resale()) + " · profit " + BoardEmbed.coins(e.netProfit()) + " ("
					+ String.format(Locale.ROOT, "%.1f%%", e.profitPercent()) + ")"
					+ (e.marketChecked() ? " · SkyCofl checked" : " · local estimate") + " · " + sincePage
					+ " ms after its page arrived");
		}
		for (FlipBoard.Entry e : diff.changed()) {
			console.line("FLIPS", "Changed: " + e.name() + " · profit now " + BoardEmbed.coins(e.netProfit()));
		}
		requestBoard(c.firstApiObservedMs());
	}

	private void onSnapshot(AuctionCollector.SnapshotSummary s) {
		if (stopping.get()) {
			return;
		}
		long now = System.currentTimeMillis();
		lastSnapshot = s;
		metrics.snapshots.incrementAndGet();
		if (s.status() == SnapshotStatus.INCOMPLETE) {
			metrics.incompleteSnapshots.incrementAndGet();
		}
		long evalNanos = service.evaluationNanos();
		long evaluated = service.candidatesEvaluated();
		long dNanos = evalNanos - lastEvalNanos;
		long dCandidates = evaluated - lastEvaluated;
		lastEvalNanos = evalNanos;
		lastEvaluated = evaluated;
		long fetchMs = Math.max(0, s.finishedMs() - s.page0ReceivedMs());
		metrics.snapshotFetchMs.add(fetchMs);
		metrics.processingMicros.add(dNanos / 1_000);
		metrics.candidatesPerSnapshot.add(dCandidates);
		if (s.detectLagMs() != null) {
			metrics.snapshotAgeAtDetectionMs.add(s.detectLagMs());
		}
		AuctionCollector.PublishEstimate pub = service.publishEstimate(s.lastUpdatedMs());
		String pubText = "";
		if (pub != null) {
			long ms = s.page0ReceivedMs() - pub.publishedLocalMs();
			metrics.publishToPage0Ms.add(ms);
			pubText = ", page 0 received " + ms + " ms (±" + pub.uncertaintyMs() + ") after Hypixel published it";
		}
		FlipBoard.Diff diff = board.onSnapshot(s.lastUpdatedMs(), s.status() == SnapshotStatus.COMPLETE,
				service::isListed, now);
		metrics.opportunitiesRemoved.addAndGet(diff.removed().size());
		console.line("SCAN", "Snapshot " + CLOCK.format(Instant.ofEpochMilli(s.lastUpdatedMs())) + " " + s.status() + ": "
				+ String.format(Locale.ROOT, "%,d", s.auctionsSeen()) + " auctions, " + s.pagesFetched() + "/" + s.totalPages()
				+ " pages, " + s.newListings() + " new listings"
				+ (s.detectLagMs() == null ? "" : ", snapshot " + secs(s.detectLagMs()) + " old when detected") + pubText);
		if (dCandidates > 0) {
			console.line("PROCESS", "Valued " + dCandidates + " listing(s) in " + String.format(Locale.ROOT, "%.1f", dNanos / 1e6)
					+ " ms CPU; all pages fetched and processed " + fetchMs + " ms after page 0");
		}
		if (diff.any()) {
			console.line("FLIPS", (diff.removed().isEmpty() ? "" : diff.removed().size()
					+ " gone (bought, cancelled or ended)") + (diff.changed().isEmpty() ? ""
					: (diff.removed().isEmpty() ? "" : ", ") + diff.changed().size() + " re-checked after restart")
					+ " · " + board.size() + " tracked");
		}
		AuctionCollector col = service.collector();
		console.line("METRICS", "Board " + board.size() + " | Candidates " + dCandidates + " | Discord "
				+ (metrics.discordRequestMs.last() < 0 ? "-" : metrics.discordRequestMs.last() + " ms") + " | API errors "
				+ (col == null ? 0 : col.client().failedRequests.get()) + " | API retries "
				+ (col == null ? 0 : col.client().retries.get()) + " | Rate limits "
				+ ((col == null ? 0 : col.client().rateLimitedResponses.get()) + metrics.discordRateLimits.get()));
		handleProfit(profit.onSnapshot(s.lastUpdatedMs(), s.status() == SnapshotStatus.COMPLETE, service::isListed, now));
		saveBoard();
		saveProfit();
		requestBoard(0);
		requestProfit();
	}

	/** Discord confirmed a board message: its flips were sent at this moment. */
	private void onBoardDelivered(long ackMs) {
		if (stopping.get() || !service.config().profit.enabled) {
			return;
		}
		handleProfit(profit.onAlerted(lastShown, ackMs, toServerTime(ackMs)));
		requestProfit();
	}

	private void onSold(java.util.List<dev.binwatch.api.EndedAuction> sold) {
		if (stopping.get() || !service.config().profit.enabled) {
			return;
		}
		handleProfit(profit.onSold(sold, System.currentTimeMillis()));
	}

	private void handleProfit(java.util.List<ProfitTracker.Flip> resolved) {
		BinWatchConfig cfg = service.config();
		String url = service.profitWebhookUrl();
		for (ProfitTracker.Flip f : resolved) {
			console.line("PROFIT", switch (f.status()) {
				case BUYABLE -> "Could have bought";
				case SNIPED -> "Sniped";
				default -> "No result";
			} + ": " + f.name() + " | profit " + BoardEmbed.coins(f.netProfit()) + " | " + f.note());
			if (profitFeed != null && cfg.profit.postEachResult && !url.isEmpty()) {
				profitFeed.post(url, ProfitEmbed.result(f));
			}
		}
		if (!resolved.isEmpty()) {
			requestProfit();
		}
	}

	private void requestProfit() {
		if (profitPublisher != null && service.config().profit.enabled) {
			profitPublisher.request(0);
		}
	}

	/** Local time on Hypixel's clock, using the collector's offset estimate when it has one. */
	private long toServerTime(long localMs) {
		AuctionCollector col = service.collector();
		return col != null && col.snapshotClock().hasOffset() ? col.snapshotClock().toServer(localMs) : localMs;
	}

	private void requestBoard(long changeWallMs) {
		if (publisher != null && service.config().board.enabled) {
			publisher.request(changeWallMs);
		}
	}

	// ---- Discord -----------------------------------------------------------------------------------------------

	private JsonObject render() {
		BinWatchConfig cfg = service.config();
		AuctionCollector.SnapshotSummary s = lastSnapshot;
		AuctionCollector col = service.collector();
		BoardEmbed.State state;
		String problem = null;
		long now = System.currentTimeMillis();
		if (stopping.get()) {
			state = BoardEmbed.State.STOPPED;
		} else if (s == null) {
			state = BoardEmbed.State.STARTING;
		} else if (s.status() == SnapshotStatus.INCOMPLETE || now - s.finishedMs() > 5 * 60_000L) {
			state = BoardEmbed.State.DEGRADED;
			problem = s.status() == SnapshotStatus.INCOMPLETE ? "some auction pages failed to load"
					: "no new snapshot for " + (now - s.finishedMs()) / 60_000 + " min";
		} else {
			state = BoardEmbed.State.SCANNING;
		}
		if (col != null && col.lastError() != null && state == BoardEmbed.State.SCANNING) {
			state = BoardEmbed.State.DEGRADED;
			problem = "last API request failed";
		}
		java.util.List<FlipBoard.Entry> ranked = board.ranked();
		JsonObject payload = BoardEmbed.payload(ranked, cfg.board.maxFlips, new BoardEmbed.Status(state,
				s == null ? null : s.lastUpdatedMs(), s == null ? null : s.status().name(), problem, now,
				cfg.market.enabled ? "Prices provided by SkyCofl (sky.coflnet.com)" : null));
		int shown = payload.getAsJsonArray("embeds").get(0).getAsJsonObject().getAsJsonArray("fields").size();
		lastShown = java.util.List.copyOf(ranked.subList(0, Math.min(shown, ranked.size())));
		return payload;
	}

	private JsonObject renderProfit() {
		BinWatchConfig cfg = service.config();
		long now = System.currentTimeMillis();
		return ProfitEmbed.summary(profit.stats(now, cfg.profit.topCount), now, stopping.get(),
				cfg.market.enabled ? "Prices provided by SkyCofl (sky.coflnet.com)" : null);
	}

	private BoardPublisher.Sender sender(String url) {
		WebhookClient wc = service.webhookClient();
		if (url.isEmpty() || wc == null) {
			return null;
		}
		return new BoardPublisher.Sender() {
			@Override
			public HttpTransport.Response post(JsonObject payload, Duration timeout) throws IOException, InterruptedException {
				return wc.post(url, payload, timeout);
			}

			@Override
			public HttpTransport.Response edit(String messageId, JsonObject payload, Duration timeout)
					throws IOException, InterruptedException {
				return wc.edit(url, messageId, payload, timeout);
			}
		};
	}

	/** The message id lives in the database next to a hash of the webhook it belongs to. */
	private BoardPublisher.MessageIds messageIds(String key, java.util.function.Supplier<String> webhook) {
		return new BoardPublisher.MessageIds() {
			@Override
			public String load() {
				ObservationStore st = service.store();
				String url = webhook.get();
				if (st == null || url.isEmpty()) {
					return null;
				}
				try {
					return SummaryPublisher.hookHash(url).equals(st.getMeta(key + "_webhook_hash")) ? st.getMeta(key + "_message_id") : null;
				} catch (java.sql.SQLException e) {
					BwLog.error("Could not read the " + key + " message id", e);
					return null;
				}
			}

			@Override
			public void save(String id) {
				ObservationStore st = service.store();
				if (st == null) {
					return;
				}
				try {
					st.setMeta(key + "_message_id", id);
					st.setMeta(key + "_webhook_hash", SummaryPublisher.hookHash(webhook.get()));
				} catch (java.sql.SQLException e) {
					BwLog.error("Could not store the " + key + " message id; a restart will post a new message", e);
				}
			}
		};
	}

	// ---- helpers -----------------------------------------------------------------------------------------------

	BinWatchService serviceForTest() {
		return service;
	}

	FlipBoard boardForTest() {
		return board;
	}

	ProfitTracker profitForTest() {
		return profit;
	}

	private void saveProfit() {
		if (profit == null || profitFile == null || opt.dryRun()) {
			return;
		}
		try {
			profit.save(profitFile);
		} catch (IOException e) {
			BwLog.throttled(BwLog.Level.WARN, "profit-save", "Could not save the profit tracker: " + e.getMessage(), null);
		}
	}

	private void saveBoard() {
		if (board == null || boardFile == null || opt.dryRun()) {
			return;
		}
		try {
			board.save(boardFile);
		} catch (IOException e) {
			BwLog.throttled(BwLog.Level.WARN, "board-save", "Could not save the board state: " + e.getMessage(), null);
		}
	}

	private void printSummary() {
		console.line("METRICS", "Session: " + metrics.snapshots.get() + " snapshots (" + metrics.incompleteSnapshots.get()
				+ " incomplete), " + metrics.opportunitiesAdded.get() + " opportunities added, "
				+ metrics.opportunitiesRemoved.get() + " removed; Discord " + metrics.discordPosts.get() + " posts, "
				+ metrics.discordEdits.get() + " edits, " + metrics.discordUnchangedSkips.get() + " unchanged skips, "
				+ metrics.discordErrors.get() + " errors, " + metrics.discordRateLimits.get() + " rate limits"
				+ (opt.dryRun() ? ", " + metrics.dryRunRenders.get() + " dry-run renders" : ""));
		console.line("METRICS", "Snapshot age when page 0 arrived: " + metrics.snapshotAgeAtDetectionMs.describe("ms"));
		console.line("METRICS", "Hypixel publish → page 0 received: " + metrics.publishToPage0Ms.describe("ms"));
		console.line("METRICS", "Page 0 → all pages fetched and processed: " + metrics.snapshotFetchMs.describe("ms"));
		console.line("METRICS", "Valuation CPU per snapshot: " + metrics.processingMicros.describe("µs") + "; listings "
				+ metrics.candidatesPerSnapshot.describe("per snapshot"));
		console.line("METRICS", "Page received → flip on the board: " + metrics.detectionToBoardMs.describe("ms"));
		console.line("METRICS", "Embed build: " + metrics.embedBuildMicros.describe("µs"));
		console.line("METRICS", "Discord request: " + metrics.discordRequestMs.describe("ms"));
		console.line("METRICS", "Page received → Discord confirmed the edit: " + metrics.detectionToDiscordMs.describe("ms"));
		if (profit != null) {
			ProfitTracker.Stats st = profit.stats(System.currentTimeMillis(), 0);
			console.line("PROFIT", "All time: " + st.allTime().buyable() + " buyable (+" + BoardEmbed.coins(st.allTime().profit())
					+ " potential), " + st.allTime().sniped() + " sniped, " + st.allTime().unknown() + " unknown, "
					+ st.pending() + " still waiting for a result");
		}
	}

	private static boolean terminal() {
		java.io.Console c = System.console();
		return c != null && c.isTerminal();
	}

	private FirstRunSetup.Input consoleInput() {
		java.io.Console sys = terminal() ? System.console() : null;
		BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
		return (prompt, secret) -> {
			if (sys != null) {
				if (secret) {
					char[] pw = sys.readPassword("%s", prompt);
					return pw == null ? null : new String(pw);
				}
				return sys.readLine("%s", prompt);
			}
			System.out.print(prompt);
			System.out.flush();
			try {
				return reader.readLine();
			} catch (IOException e) {
				return null;
			}
		};
	}

	private static String secs(long ms) {
		return String.format(Locale.ROOT, "%.1f s", ms / 1000.0);
	}

	private static String fmt(double v) {
		return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
	}
}
