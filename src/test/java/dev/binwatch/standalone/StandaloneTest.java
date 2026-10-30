package dev.binwatch.standalone;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.binwatch.api.HttpTransport;
import dev.binwatch.api.ScriptedTransport;
import dev.binwatch.config.BinWatchConfig;
import dev.binwatch.config.ConfigManager;
import dev.binwatch.market.MarketCheck;
import dev.binwatch.market.MarketQuote;
import dev.binwatch.service.SyntheticData;
import dev.binwatch.store.AtomicFiles;
import dev.binwatch.value.FlipCandidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Standalone app: board tracking, embed, publisher, first-run setup and the whole pipeline, all offline. */
class StandaloneTest {
	static final String HOOK = "https://discord.com/api/webhooks/123456789012/ABCDEFGHIJKLMNOPQRSTUVWXYZabcd";
	static final String TOKEN = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcd";
	static final long NOW = 1_793_310_000_000L;

	// ---- helpers ------------------------------------------------------------------------------------------------

	static FlipCandidate flip(UUID id, String name, long price, long resale, long net, Long endMs, MarketCheck market) {
		FlipCandidate c = new FlipCandidate(id, name, "ITEM", "", "item", price, 1, NOW - 1000, endMs, NOW - 500, NOW - 8000,
				"NEW", null, resale, resale - price, resale - price - net, net, (resale - price) * 100.0 / resale, true, "ok",
				List.of(), "", false, null);
		return market == null ? c : c.withMarket(market);
	}

	static FlipCandidate flip(String name, long price, long net) {
		return flip(UUID.randomUUID(), name, price, price + net + net / 10, net, NOW + 3_600_000L, null);
	}

	static MarketCheck market(long listPrice, long net) {
		return new MarketCheck(new MarketQuote(listPrice, "0123", listPrice, 5.0, listPrice, "item", NOW, "COFL"), null, listPrice, 0, net,
				12, 4.8, true, List.of(), "COFL", "Prices provided by SkyCofl (sky.coflnet.com)");
	}

	static FlipBoard board(BinWatchConfig.Board cfg) {
		return new FlipBoard(() -> cfg);
	}

	static void await(BooleanSupplier cond, long maxMs, String what) throws InterruptedException {
		long end = System.nanoTime() + maxMs * 1_000_000;
		while (!cond.getAsBoolean()) {
			if (System.nanoTime() > end) {
				fail("timed out waiting for " + what);
			}
			Thread.sleep(20);
		}
	}

	static ConsoleOut quiet(ByteArrayOutputStream sink) {
		return new ConsoleOut(new PrintStream(sink, true, StandardCharsets.UTF_8), false);
	}

	/** In-memory Discord: records every call and answers from a queue (default: success). */
	static final class FakeDiscord implements BoardPublisher.Sender {
		final List<String> calls = Collections.synchronizedList(new ArrayList<>());
		final List<JsonObject> bodies = Collections.synchronizedList(new ArrayList<>());
		final Deque<HttpTransport.Response> answers = new ArrayDeque<>();
		volatile long delayMs;
		final AtomicInteger nextId = new AtomicInteger(111);

		@Override
		public HttpTransport.Response post(JsonObject payload, Duration timeout) throws InterruptedException {
			return answer("POST", payload, "{\"id\":\"" + nextId.getAndIncrement() + "\"}");
		}

		@Override
		public HttpTransport.Response edit(String messageId, JsonObject payload, Duration timeout) throws InterruptedException {
			return answer("PATCH " + messageId, payload, "{\"id\":\"" + messageId + "\"}");
		}

		private HttpTransport.Response answer(String call, JsonObject payload, String okBody) throws InterruptedException {
			calls.add(call);
			bodies.add(payload);
			if (delayMs > 0) {
				Thread.sleep(delayMs);
			}
			synchronized (answers) {
				HttpTransport.Response r = answers.pollFirst();
				return r != null ? r : new HttpTransport.Response(200, Map.of(), okBody);
			}
		}
	}

	static final class MemoryIds implements BoardPublisher.MessageIds {
		volatile String id;

		@Override
		public String load() {
			return id;
		}

		@Override
		public void save(String id) {
			this.id = id;
		}
	}

	static BoardPublisher publisher(FakeDiscord d, MemoryIds ids, AtomicReference<JsonObject> view, Metrics m,
			boolean dryRun, long gapMs) {
		return new BoardPublisher(() -> d, ids, view::get, () -> gapMs, Duration.ofSeconds(5), m,
				quiet(new ByteArrayOutputStream()), dryRun, System::currentTimeMillis);
	}

	static JsonObject view(String marker) {
		BoardEmbed.Status st = new BoardEmbed.Status(BoardEmbed.State.SCANNING, NOW, "COMPLETE", null, NOW, null);
		List<FlipBoard.Entry> entries = new ArrayList<>();
		entries.add(FlipBoard.entryOf(flip(marker, 1_000_000, 200_000)));
		return BoardEmbed.payload(entries, 8, st);
	}

	static String text(JsonObject payload) {
		return payload.toString();
	}

	// ---- tracker ------------------------------------------------------------------------------------------------

	@Test
	void boardAddsDeduplicatesChangesAndRanks() {
		FlipBoard b = board(new BinWatchConfig.Board());
		UUID id = UUID.randomUUID();
		FlipCandidate c = flip(id, "Hyperion", 500_000_000, 560_000_000, 30_000_000, NOW + 3_600_000L, null);
		FlipBoard.Diff d1 = b.offer(c);
		assertEquals(1, d1.added().size());
		assertFalse(b.offer(c).any(), "the same auction offered again is not a new opportunity");
		FlipBoard.Diff d3 = b.offer(c.withMarket(market(540_000_000, 25_000_000)));
		assertEquals(1, d3.changed().size(), "the market check changed what is shown");
		assertEquals(25_000_000, b.ranked().get(0).netProfit());
		assertTrue(b.ranked().get(0).marketChecked());
		b.offer(flip("Small", 1_000_000, 2_000_000));
		b.offer(flip("Big", 10_000_000, 90_000_000));
		assertEquals(List.of("Big", "Hyperion", "Small"), b.ranked().stream().map(FlipBoard.Entry::name).toList());
		assertEquals(3, b.size());
	}

	@Test
	void boardRemovesOnlyOnCompleteSnapshotsAndExpiry() {
		FlipBoard b = board(new BinWatchConfig.Board());
		FlipCandidate keep = flip("Keep", 1_000_000, 500_000);
		FlipCandidate sold = flip("Sold", 1_000_000, 400_000);
		FlipCandidate ending = flip(UUID.randomUUID(), "Ending", 1_000_000, 1_600_000, 500_000, NOW + 10_000, null);
		b.offer(keep);
		b.offer(sold);
		b.offer(ending);
		Set<UUID> listed = Set.of(keep.auctionUuid(), ending.auctionUuid());
		// A partial snapshot cannot prove the sold auction is gone.
		assertFalse(b.onSnapshot(NOW + 60_000, false, listed::contains, NOW).any());
		assertEquals(3, b.size());
		FlipBoard.Diff d = b.onSnapshot(NOW + 60_000, true, listed::contains, NOW);
		assertEquals(List.of("Sold"), d.removed().stream().map(FlipBoard.Entry::name).toList());
		FlipBoard.Diff d2 = b.onSnapshot(NOW + 120_000, false, listed::contains, NOW + 20_000);
		assertEquals(List.of("Ending"), d2.removed().stream().map(FlipBoard.Entry::name).toList(), "ended auctions always leave");
		assertEquals(NOW + 60_000, b.ranked().get(0).verifiedLastUpdatedMs());
	}

	@Test
	void boardAppliesMinProfitPercentAndCap() {
		BinWatchConfig.Board cfg = new BinWatchConfig.Board();
		cfg.minProfitPercent = 10;
		cfg.maxTracked = 10;
		FlipBoard b = board(cfg);
		assertFalse(b.offer(flip("Low", 10_000_000, 500_000)).any(), "5% is below the 10% minimum");
		for (int i = 0; i < 15; i++) {
			b.offer(flip("F" + i, 1_000_000, 200_000 + i * 10_000));
		}
		assertEquals(10, b.size());
		assertEquals("F14", b.ranked().get(0).name());
		assertFalse(b.offer(flip("Tiny", 1_000_000, 150_000)).any(), "below the cap's least profitable entry");
	}

	@Test
	void boardSurvivesRestartAndIsRecheckedAgainstTheFirstCompleteSnapshot(@TempDir Path dir) throws IOException {
		FlipBoard b = board(new BinWatchConfig.Board());
		FlipCandidate still = flip("Still listed", 1_000_000, 500_000);
		FlipCandidate gone = flip("Gone", 1_000_000, 400_000);
		FlipCandidate ended = flip(UUID.randomUUID(), "Ended", 1_000_000, 1_600_000, 500_000, NOW + 1000, null);
		b.offer(still);
		b.offer(gone);
		b.offer(ended);
		Path file = dir.resolve("board.json");
		b.save(file);

		FlipBoard after = board(new BinWatchConfig.Board());
		assertEquals(2, after.restore(file, NOW + 5000), "the ended auction is not restored");
		assertTrue(after.ranked().stream().allMatch(FlipBoard.Entry::restored));
		String shown = BoardEmbed.fieldValue(after.ranked().get(0));
		assertTrue(shown.contains("not re-checked"), shown);
		FlipBoard.Diff d = after.onSnapshot(NOW + 60_000, true, Set.of(still.auctionUuid())::contains, NOW + 5000);
		assertEquals(List.of("Gone"), d.removed().stream().map(FlipBoard.Entry::name).toList());
		assertEquals(1, d.changed().size());
		assertFalse(after.ranked().get(0).restored());
	}

	// ---- embed --------------------------------------------------------------------------------------------------

	@Test
	void embedShowsEmptyStateCompactCoinsAndStaysWithinDiscordLimits() {
		assertEquals("12.5M", BoardEmbed.coins(12_500_000));
		assertEquals("850K", BoardEmbed.coins(850_000));
		assertEquals("12.5K", BoardEmbed.coins(12_500));
		assertEquals("520M", BoardEmbed.coins(520_000_000));
		assertEquals("1.25B", BoardEmbed.coins(1_250_000_000));
		assertEquals("2M", BoardEmbed.coins(2_000_000));
		assertEquals("-300K", BoardEmbed.coins(-300_000));
		assertEquals("999", BoardEmbed.coins(999));

		BoardEmbed.Status st = new BoardEmbed.Status(BoardEmbed.State.SCANNING, NOW, "COMPLETE", null, NOW, null);
		JsonObject empty = BoardEmbed.payload(List.of(), 8, st);
		JsonObject e = empty.getAsJsonArray("embeds").get(0).getAsJsonObject();
		assertEquals("AH Flipper", e.get("title").getAsString());
		assertTrue(e.get("description").getAsString().contains("No opportunities right now"));
		assertEquals(0, e.getAsJsonArray("fields").size());
		assertEquals(0, empty.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size(), "never pings");

		List<FlipBoard.Entry> many = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			many.add(FlipBoard.entryOf(flip("X".repeat(300), 1_000_000_000L + i, 50_000_000L + i)));
		}
		JsonObject full = BoardEmbed.payload(many, 20, st).getAsJsonArray("embeds").get(0).getAsJsonObject();
		int fields = full.getAsJsonArray("fields").size();
		assertTrue(fields > 0 && fields <= 20, "fields " + fields);
		int total = full.get("title").getAsString().length() + full.get("description").getAsString().length()
				+ full.getAsJsonObject("footer").get("text").getAsString().length();
		for (var f : full.getAsJsonArray("fields")) {
			String name = f.getAsJsonObject().get("name").getAsString();
			String value = f.getAsJsonObject().get("value").getAsString();
			assertTrue(name.length() <= 256 && value.length() <= 1024);
			total += name.length() + value.length();
		}
		assertTrue(total <= 6000, "embed is " + total + " characters");
		assertTrue(full.get("description").getAsString().contains("**40** opportunities tracked"));

		FlipBoard.Entry checked = FlipBoard.entryOf(flip(UUID.fromString("0123abcd-0000-0000-0000-000000000001"), "Necron",
				100_000_000, 120_000_000, 15_000_000, NOW + 3_600_000L, market(118_000_000, 14_000_000)));
		String v = BoardEmbed.fieldValue(checked);
		assertTrue(v.contains("Buy **100M** → resale **118M**"), v);
		assertTrue(v.contains("Profit **+14M** after fees (14.0%)"), v);
		assertTrue(v.contains("/viewauction 0123abcd000000000000000000000001"), v);
		assertFalse(v.contains("http"), "no invented auction link");
	}

	// ---- publisher ----------------------------------------------------------------------------------------------

	@Test
	void publisherPostsOnceThenEditsAndSkipsUnchanged() throws Exception {
		FakeDiscord d = new FakeDiscord();
		MemoryIds ids = new MemoryIds();
		Metrics m = new Metrics();
		AtomicReference<JsonObject> view = new AtomicReference<>(view("A"));
		try (BoardPublisher p = publisher(d, ids, view, m, false, 1000)) {
			assertTrue(p.publishNow(Duration.ofSeconds(5)));
			assertEquals(List.of("POST"), d.calls);
			assertEquals("111", ids.id);
			view.set(view("B"));
			assertTrue(p.publishNow(Duration.ofSeconds(5)));
			assertEquals(List.of("POST", "PATCH 111"), d.calls);
			assertTrue(p.publishNow(Duration.ofSeconds(5)));
			assertEquals(2, d.calls.size(), "nothing visible changed, so no request");
			assertEquals(1, m.discordUnchangedSkips.get());
		}
	}

	@Test
	void publisherRecreatesADeletedMessage() throws Exception {
		FakeDiscord d = new FakeDiscord();
		MemoryIds ids = new MemoryIds();
		ids.id = "555";
		d.answers.add(new HttpTransport.Response(404, Map.of(), "{\"message\":\"Unknown Message\",\"code\":10008}"));
		AtomicReference<JsonObject> view = new AtomicReference<>(view("A"));
		try (BoardPublisher p = publisher(d, ids, view, new Metrics(), false, 1000)) {
			assertTrue(p.publishNow(Duration.ofSeconds(5)));
		}
		assertEquals(List.of("PATCH 555", "POST"), d.calls);
		assertEquals("111", ids.id);
	}

	@Test
	void publisherHonoursDiscord429WithoutATightLoop() throws Exception {
		FakeDiscord d = new FakeDiscord();
		MemoryIds ids = new MemoryIds();
		Metrics m = new Metrics();
		d.answers.add(new HttpTransport.Response(429, Map.of("Retry-After", List.of("1")),
				"{\"message\":\"You are being rate limited.\",\"retry_after\":1.2}"));
		AtomicReference<JsonObject> view = new AtomicReference<>(view("A"));
		try (BoardPublisher p = publisher(d, ids, view, m, false, 1000)) {
			long t0 = System.nanoTime();
			p.request(0);
			await(() -> d.calls.size() >= 1, 3000, "first attempt");
			Thread.sleep(600);
			assertEquals(1, d.calls.size(), "no retry before retry_after");
			view.set(view("B"));
			p.request(0);
			p.request(0);
			await(() -> d.calls.size() >= 2, 4000, "retry after the rate limit");
			long waitedMs = (System.nanoTime() - t0) / 1_000_000;
			assertTrue(waitedMs >= 1150, "retried after " + waitedMs + " ms");
			Thread.sleep(300);
			assertEquals(2, d.calls.size(), "the queued requests were merged into one retry");
			assertTrue(text(d.bodies.get(1)).contains("B"), "the retry sends the newest board");
			assertEquals(1, m.discordRateLimits.get());
		}
	}

	@Test
	void publisherBacksOffOnARejectedWebhook() throws Exception {
		FakeDiscord d = new FakeDiscord();
		d.answers.add(new HttpTransport.Response(401, Map.of(), "{\"message\":\"Invalid Webhook Token\",\"code\":50027}"));
		AtomicReference<JsonObject> view = new AtomicReference<>(view("A"));
		try (BoardPublisher p = publisher(d, new MemoryIds(), view, new Metrics(), false, 1000)) {
			p.request(0);
			await(() -> d.calls.size() >= 1, 3000, "first attempt");
			for (int i = 0; i < 20; i++) {
				p.request(0);
			}
			Thread.sleep(1500);
			assertEquals(1, d.calls.size(), "a rejected webhook is not retried for minutes");
			assertTrue(p.lastResult().contains("rejected"), p.lastResult());
		}
	}

	@Test
	void scanningIsNotBlockedBySlowDiscordAndChangesAreCoalesced() throws Exception {
		FakeDiscord d = new FakeDiscord();
		d.delayMs = 800;
		AtomicReference<JsonObject> view = new AtomicReference<>(view("first"));
		try (BoardPublisher p = publisher(d, new MemoryIds(), view, new Metrics(), false, 1000)) {
			p.request(0);
			await(() -> d.calls.size() == 1, 3000, "first request in flight");
			long t0 = System.nanoTime();
			for (int i = 0; i < 200; i++) {
				view.set(view("change-" + i));
				p.request(0);
			}
			long scanMs = (System.nanoTime() - t0) / 1_000_000;
			assertTrue(scanMs < 200, "200 changes took " + scanMs + " ms while Discord was slow");
			await(() -> d.calls.size() == 2, 5000, "the merged follow-up");
			Thread.sleep(1500);
			assertEquals(2, d.calls.size(), "200 changes during a slow request became one edit");
			assertTrue(text(d.bodies.get(1)).contains("change-199"), "only the newest state is sent");
		}
	}

	@Test
	void dryRunNeverCallsDiscord() throws Exception {
		AtomicInteger used = new AtomicInteger();
		Metrics m = new Metrics();
		BoardPublisher p = new BoardPublisher(() -> {
			used.incrementAndGet();
			return null;
		}, new MemoryIds(), () -> view("A"), () -> 1000, Duration.ofSeconds(5), m, quiet(new ByteArrayOutputStream()),
				true, System::currentTimeMillis);
		try (p) {
			assertTrue(p.publishNow(Duration.ofSeconds(5)));
		}
		assertEquals(0, used.get());
		assertEquals(1, m.dryRunRenders.get());
	}

	// ---- configuration ------------------------------------------------------------------------------------------

	@Test
	void firstRunAsksForTheWebhookChecksItAndNeverEchoesIt(@TempDir Path dir) throws IOException {
		ScriptedTransport discord = new ScriptedTransport()
				.enqueue(TOKEN, () -> ScriptedTransport.status(404, Map.of(), "{\"message\":\"Unknown Webhook\",\"code\":10015}"),
						() -> ScriptedTransport.ok("{\"name\":\"Flips\",\"channel_id\":\"42\",\"token\":\"" + TOKEN + "\"}"));
		Deque<String> answers = new ArrayDeque<>(List.of("not a webhook", HOOK, HOOK, "1.5m", "12", "5"));
		ByteArrayOutputStream printed = new ByteArrayOutputStream();
		ConfigManager files = new ConfigManager(dir);
		FirstRunSetup setup = new FirstRunSetup((prompt, secret) -> answers.pollFirst(), quiet(printed), () -> discord, files);
		assertTrue(setup.ensureWebhook(false));
		assertEquals(List.of("GET", "GET"), discord.methods, "checked with GET only; nothing posted");
		assertTrue(Files.readString(files.secretsFile()).contains(TOKEN));
		BinWatchConfig cfg = FirstRunSetup.standaloneDefaults();
		setup.askThresholds(cfg);
		String out = printed.toString(StandardCharsets.UTF_8);
		assertFalse(out.contains(TOKEN), "the webhook token must never be printed:\n" + out);
		assertTrue(out.contains("Webhook OK: \"Flips\""), out);
		BinWatchConfig saved = files.loadConfig().value();
		assertEquals(1_500_000, saved.filters.minNetProfit);
		assertEquals(12.0, saved.filters.minDiscountPercent);
		assertEquals(5, saved.board.maxFlips);
		assertFalse(saved.discord.enabled, "standalone default: the board instead of one message per flip");

		// Configured already: no questions.
		FirstRunSetup again = new FirstRunSetup((p, s) -> fail("asked " + p), quiet(printed), () -> discord, files);
		assertTrue(again.ensureWebhook(false));
	}

	@Test
	void parsesCoinAmounts() {
		assertEquals(1_500_000L, FirstRunSetup.parseCoins("1.5m"));
		assertEquals(500_000L, FirstRunSetup.parseCoins("500K"));
		assertEquals(2_000_000_000L, FirstRunSetup.parseCoins("2b"));
		assertEquals(1_234_567L, FirstRunSetup.parseCoins("1,234,567"));
		assertNull(FirstRunSetup.parseCoins("lots"));
		assertNull(FirstRunSetup.parseCoins("-5"));
	}

	@Test
	void invalidConfigOrMissingWebhookStopsStartup(@TempDir Path dir) throws IOException {
		ByteArrayOutputStream printed = new ByteArrayOutputStream();
		Path cfgFile = dir.resolve("a/config/config.json");
		Files.createDirectories(cfgFile.getParent());
		BinWatchConfig bad = FirstRunSetup.standaloneDefaults();
		bad.board.maxFlips = 99;
		AtomicFiles.writeString(cfgFile, ConfigManager.GSON.toJson(bad));
		StandaloneMain a = new StandaloneMain(new StandaloneMain.Options(dir.resolve("a"), false, false, 0, false, false),
				new PrintStream(printed, true, StandardCharsets.UTF_8));
		a.input = (p, s) -> null;
		assertEquals(2, a.start());
		assertTrue(printed.toString(StandardCharsets.UTF_8).contains("board.maxFlips must be between 1 and 20"));

		StandaloneMain b = new StandaloneMain(new StandaloneMain.Options(dir.resolve("b"), false, false, 0, false, false),
				new PrintStream(printed, true, StandardCharsets.UTF_8));
		b.input = (p, s) -> null;
		assertEquals(2, b.start(), "no webhook and no way to ask for one");
		assertTrue(printed.toString(StandardCharsets.UTF_8).contains("No Discord webhook configured"));
	}

	// ---- whole pipeline -----------------------------------------------------------------------------------------

	/** Scripted Hypixel + Discord. Page 0 serves {@code snapshots} in turn; the newest stays after the last. */
	static ScriptedTransport hypixel(List<List<SyntheticData.Auction>> snapshots, List<Long> lastUpdated, int pageSize) {
		// Page bodies are built up front so a benchmark measures the app, not the fake server.
		List<List<String>> bodies = new ArrayList<>();
		for (int s = 0; s < snapshots.size(); s++) {
			List<SyntheticData.Auction> all = snapshots.get(s);
			int pages = Math.max(1, (all.size() + pageSize - 1) / pageSize);
			List<String> pageBodies = new ArrayList<>();
			for (int page = 0; page < pages; page++) {
				pageBodies.add(SyntheticData.pageJson(page, pages, lastUpdated.get(s),
						all.subList(Math.min(all.size(), page * pageSize), Math.min(all.size(), (page + 1) * pageSize))));
			}
			bodies.add(pageBodies);
		}
		ScriptedTransport t = new ScriptedTransport();
		AtomicInteger current = new AtomicInteger(-1);
		t.whenEmpty("auctions?page=", u -> {
			String q = u.getRawQuery();
			int page = Integer.parseInt(q.substring(q.indexOf("page=") + 5));
			int snap = page == 0 ? Math.min(current.incrementAndGet(), snapshots.size() - 1) : Math.max(0, current.get());
			return ScriptedTransport.ok(bodies.get(snap).get(page));
		});
		t.whenEmpty("auctions_ended", u -> ScriptedTransport.ok(SyntheticData.endedJson(NOW, List.of(), NOW)));
		t.whenEmpty("?wait=true", u -> ScriptedTransport.ok("{\"id\":\"111\"}"));
		t.whenEmpty("/messages/", u -> ScriptedTransport.ok("{\"id\":\"111\"}"));
		return t;
	}

	static void writeStandaloneConfig(Path base, int maxConcurrentPages) throws IOException {
		BinWatchConfig cfg = FirstRunSetup.standaloneDefaults();
		cfg.api.enabled = false; // the test drives polls itself
		cfg.api.maxConcurrentPageRequests = maxConcurrentPages;
		cfg.market.enabled = false; // no SkyCofl calls offline
		cfg.board.minEditIntervalMs = 1000;
		AtomicFiles.writeString(base.resolve("config/config.json"), ConfigManager.GSON.toJson(cfg));
		AtomicFiles.writeString(base.resolve("config/secrets.json"), "{\"discordWebhookUrl\":\"" + HOOK + "\"}");
	}

	static StandaloneMain app(Path base, ScriptedTransport t, ByteArrayOutputStream printed) {
		StandaloneMain app = new StandaloneMain(new StandaloneMain.Options(base, false, false, 0, false, false),
				new PrintStream(printed, true, StandardCharsets.UTF_8));
		app.http = t;
		app.apiBase = URI.create("https://api.example.invalid");
		app.input = (p, s) -> fail("should not ask: " + p);
		return app;
	}

	/** Requests whose URL contains {@code pathPart}: {@code ?wait=true} is a new message, {@code /messages/} an edit. */
	static long count(ScriptedTransport t, String pathPart) {
		synchronized (t.requests) {
			return t.requests.stream().filter(u -> u.toString().contains(pathPart)).count();
		}
	}

	@Test
	void endToEndBaselineNewFlipRemovalAndRestart(@TempDir Path dir) throws Exception {
		Path base = dir.resolve("app");
		writeStandaloneConfig(base, 1);
		long now = System.currentTimeMillis();
		List<SyntheticData.Auction> market = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			market.add(SyntheticData.Auction.bin("TEST_SWORD", 10_000_000L + i * 100_000L, now - 3_600_000L));
		}
		// An underpriced bow that was already listed before the app started: the baseline must not alert it.
		for (int i = 0; i < 6; i++) {
			market.add(SyntheticData.Auction.bin("TEST_BOW", 10_000_000L + i * 100_000L, now - 3_600_000L));
		}
		market.add(SyntheticData.Auction.bin("TEST_BOW", 6_000_000L, now - 600_000L));
		SyntheticData.Auction cheap = SyntheticData.Auction.bin("TEST_SWORD", 6_000_000L, now + 5_000);
		List<SyntheticData.Auction> withCheap = new ArrayList<>(market);
		withCheap.add(cheap);
		ScriptedTransport t = hypixel(List.of(market, withCheap, market), List.of(now - 120_000, now - 60_000, now), 1000);
		ByteArrayOutputStream printed = new ByteArrayOutputStream();

		StandaloneMain app = app(base, t, printed);
		assertEquals(0, app.start(), printed.toString(StandardCharsets.UTF_8));
		var collector = app.serviceForTest().collector();
		collector.pollOnce(null); // baseline
		assertEquals(0, app.boardForTest().size(), "auctions listed before startup are not opportunities");
		collector.pollOnce(null); // the cheap listing appears
		assertEquals(1, app.boardForTest().size());
		await(() -> count(t, "?wait=true") == 1 && count(t, "/messages/111") >= 1
				&& lastBody(t).contains("6M"), 6000, "the flip published as an edit of the one message");
		collector.pollOnce(null); // it sold
		assertEquals(0, app.boardForTest().size());
		await(() -> lastBody(t).contains("No opportunities right now"), 6000, "the empty state");
		app.stop();
		assertTrue(lastBody(t).contains("Stopped"), "shows that it is no longer checked");
		assertEquals(1, count(t, "?wait=true"), "one message for the whole run, never one per scan");
		String out = printed.toString(StandardCharsets.UTF_8);
		assertFalse(out.contains(TOKEN), "webhook token printed");
		assertTrue(out.contains("[FLIPS] New: [TEST] TEST_SWORD"), out);

		// Restart: the same message is edited again, no second post.
		ScriptedTransport t2 = hypixel(List.of(market), List.of(now), 1000);
		StandaloneMain again = app(base, t2, printed);
		assertEquals(0, again.start());
		await(() -> count(t2, "/messages/111") >= 1, 6000, "edit after restart");
		assertEquals(0, count(t2, "?wait=true"));
		again.stop();
	}

	@Test
	void processesARepresentativeSnapshotQuickly(@TempDir Path dir) throws Exception {
		Path base = dir.resolve("perf");
		writeStandaloneConfig(base, 8);
		long now = System.currentTimeMillis();
		// ~20k listings over 20 pages, 400 item types, as in a real snapshot (Hypixel has ~45k on ~45 pages).
		List<SyntheticData.Auction> before = new ArrayList<>();
		for (int i = 0; i < 20_000; i++) {
			before.add(SyntheticData.Auction.bin("PERF_ITEM_" + (i % 400), 1_000_000L + (i % 97) * 10_000L, now - 3_600_000L));
		}
		List<SyntheticData.Auction> after = new ArrayList<>(before);
		for (int i = 0; i < 300; i++) {
			after.add(0, SyntheticData.Auction.bin("PERF_ITEM_" + (i % 400), 1_000_000L + (i % 50) * 20_000L, now + 5_000));
		}
		ScriptedTransport t = hypixel(List.of(before, after), List.of(now - 60_000, now), 1000);
		ByteArrayOutputStream printed = new ByteArrayOutputStream();
		StandaloneMain app = app(base, t, printed);
		assertEquals(0, app.start());
		try {
			var service = app.serviceForTest();
			var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
			try {
				long t0 = System.nanoTime();
				service.collector().pollOnce(pool);
				long baselineMs = (System.nanoTime() - t0) / 1_000_000;
				long evalBefore = service.evaluationNanos();
				long candBefore = service.candidatesEvaluated();
				long t1 = System.nanoTime();
				var r = service.collector().pollOnce(pool);
				long snapshotMs = (System.nanoTime() - t1) / 1_000_000;
				long evalMs = (service.evaluationNanos() - evalBefore) / 1_000_000;
				System.out.println("[BENCH] offline: baseline 20,000 listings " + baselineMs + " ms; next snapshot 20,300 listings ("
						+ r.summary().newListings() + " new) parsed, stored and valued in " + snapshotMs + " ms, valuation CPU "
						+ evalMs + " ms for " + (service.candidatesEvaluated() - candBefore) + " listings");
				assertEquals(300, r.summary().newListings());
				assertTrue(snapshotMs < 5_000, "processing took " + snapshotMs + " ms");
			} finally {
				pool.shutdownNow();
			}
		} finally {
			app.stop();
		}
	}

	static String lastBody(ScriptedTransport t) {
		synchronized (t.postedBodies) {
			for (int i = t.postedBodies.size() - 1; i >= 0; i--) {
				String b = t.postedBodies.get(i);
				if (b.contains("AH Flipper")) {
					return JsonParser.parseString(b).toString();
				}
			}
		}
		return "";
	}
}
