package dev.binwatch.standalone;

import dev.binwatch.api.EndedAuction;
import dev.binwatch.api.ScriptedTransport;
import dev.binwatch.config.BinWatchConfig;
import dev.binwatch.config.ConfigManager;
import dev.binwatch.service.SyntheticData;
import dev.binwatch.store.AtomicFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The profit tracker: was each alerted flip still unsold when Discord got it, and how long did it stay buyable. */
class ProfitTrackerTest {
	static final long T = 1_793_310_000_000L;

	static FlipBoard.Entry entry(String name, long price, long net) {
		return FlipBoard.entryOf(StandaloneTest.flip(UUID.randomUUID(), name, price, price + net, net, T + 6 * 3_600_000L, null));
	}

	static EndedAuction sale(FlipBoard.Entry e, long atMs) {
		return new EndedAuction(e.uuid(), atMs, e.price(), true, null);
	}

	static ProfitTracker tracker() {
		BinWatchConfig.Profit cfg = new BinWatchConfig.Profit();
		return new ProfitTracker(() -> cfg);
	}

	@Test
	void soldAfterTheAlertCountsAndSaysHowLongAfter() {
		ProfitTracker p = tracker();
		FlipBoard.Entry hype = entry("Hyperion", 500_000_000, 32_000_000);
		assertTrue(p.onAlerted(List.of(hype), T, T + 200).isEmpty());
		assertEquals(ProfitTracker.Status.PENDING, p.get(hype.uuid()).status());
		List<ProfitTracker.Flip> r = p.onSold(List.of(sale(hype, T + 200 + 14_200)), T + 60_000);
		assertEquals(1, r.size());
		ProfitTracker.Flip f = r.get(0);
		assertEquals(ProfitTracker.Status.BUYABLE, f.status());
		assertEquals(14_200L, f.soldAfterMs());
		assertEquals("sold 14.2 s after the alert", f.note());
		ProfitTracker.Stats st = p.stats(T + 60_000, 5);
		assertEquals(32_000_000, st.lastHour().profit());
		assertEquals(1, st.allTime().buyable());
		assertEquals(0, st.pending());
		String line = ProfitEmbed.result(f).toString();
		assertTrue(line.contains("Could have bought: Hyperion") && line.contains("Sold 14.2 s after the alert"), line);
		String summary = ProfitEmbed.summary(st, T + 60_000, false, null).toString();
		assertTrue(summary.contains("+32M") && summary.contains("Median **14.2 s** after the alert"), summary);
		// A second report of the same sale changes nothing.
		assertTrue(p.onSold(List.of(sale(hype, T + 200 + 14_200)), T + 61_000).isEmpty());
	}

	@Test
	void soldBeforeTheAlertIsSnipedAndNotCounted() {
		ProfitTracker p = tracker();
		FlipBoard.Entry e = entry("Necron's Handle", 300_000_000, 20_000_000);
		p.onAlerted(List.of(e), T, T);
		ProfitTracker.Flip f = p.onSold(List.of(sale(e, T - 1_300)), T + 60_000).get(0);
		assertEquals(ProfitTracker.Status.SNIPED, f.status());
		assertEquals("sold 1.3 s before the alert reached Discord", f.note());
		ProfitTracker.Stats st = p.stats(T + 60_000, 5);
		assertEquals(0, st.lastDay().profit());
		assertEquals(1, st.lastDay().sniped());
		assertTrue(st.top().isEmpty());
	}

	@Test
	void aSaleReportedBeforeTheAlertIsConfirmedIsStillMatched() {
		ProfitTracker p = tracker();
		FlipBoard.Entry e = entry("Dye", 20_000_000, 2_000_000);
		assertTrue(p.onSold(List.of(sale(e, T + 5_000)), T + 4_000).isEmpty());
		List<ProfitTracker.Flip> r = p.onAlerted(List.of(e), T + 4_000, T + 3_000);
		assertEquals(ProfitTracker.Status.BUYABLE, r.get(0).status());
		assertEquals(2_000L, r.get(0).soldAfterMs());
	}

	@Test
	void vanishedWithoutASaleIsDecidedAfterTheGracePeriod() {
		ProfitTracker p = tracker();
		FlipBoard.Entry seenAfter = entry("Seen after", 10_000_000, 1_000_000);
		FlipBoard.Entry neverSeen = entry("Never seen", 10_000_000, 1_500_000);
		p.onAlerted(List.of(seenAfter, neverSeen), T, T);
		// Snapshot taken 50 s after the alert still lists the first one only.
		p.onSnapshot(T + 50_000, true, Set.of(seenAfter.uuid())::contains, T + 60_000);
		// Both gone, but auctions_ended may still report a sale: nothing is decided yet.
		assertTrue(p.onSnapshot(T + 110_000, true, Set.<UUID>of()::contains, T + 120_000).isEmpty());
		// A partial snapshot does not count as gone.
		assertTrue(p.onSnapshot(T + 170_000, false, Set.<UUID>of()::contains, T + 180_000).isEmpty());
		List<ProfitTracker.Flip> r = p.onSnapshot(T + 290_000, true, Set.<UUID>of()::contains, T + 120_000
				+ ProfitTracker.GONE_GRACE_MS);
		assertEquals(2, r.size());
		ProfitTracker.Flip a = p.get(seenAfter.uuid());
		ProfitTracker.Flip b = p.get(neverSeen.uuid());
		assertEquals(ProfitTracker.Status.BUYABLE, a.status());
		assertTrue(a.note().contains("still listed 50.0 s after the alert"), a.note());
		assertNull(a.soldAfterMs());
		assertEquals(ProfitTracker.Status.UNKNOWN, b.status());
		assertEquals(1_000_000, p.stats(T + 400_000, 5).lastDay().profit());
	}

	@Test
	void stillListedAfterTheFollowTimeCountsAsBuyable() {
		ProfitTracker p = tracker();
		FlipBoard.Entry e = entry("Slow seller", 5_000_000, 600_000);
		p.onAlerted(List.of(e), T, T);
		p.onSnapshot(T + 60_000, true, Set.of(e.uuid())::contains, T + 61_000);
		assertTrue(p.onSnapshot(T + 120_000, true, Set.of(e.uuid())::contains, T + 30 * 60_000L).isEmpty());
		List<ProfitTracker.Flip> r = p.onSnapshot(T + 3_600_000L, true, Set.of(e.uuid())::contains, T + 3_600_000L);
		assertEquals(ProfitTracker.Status.BUYABLE, r.get(0).status());
		assertEquals("still unsold 60 min after the alert", r.get(0).note());
	}

	@Test
	void eachAuctionIsFollowedOnceAndStateSurvivesARestart(@TempDir Path dir) throws IOException {
		ProfitTracker p = tracker();
		FlipBoard.Entry a = entry("A", 1_000_000, 300_000);
		FlipBoard.Entry b = entry("B", 1_000_000, 400_000);
		p.onAlerted(List.of(a), T, T);
		p.onAlerted(List.of(a, b), T + 5_000, T + 5_000);
		assertEquals(T, p.get(a.uuid()).alertedAtMs(), "the first alert is the one that counts");
		p.onSold(List.of(sale(a, T + 3_000)), T + 60_000);
		Path file = dir.resolve("profit.json");
		p.save(file);

		ProfitTracker q = tracker();
		assertEquals(2, q.restore(file));
		assertEquals(ProfitTracker.Status.PENDING, q.get(b.uuid()).status(), "still followed after a restart");
		q.onSold(List.of(sale(b, T + 70_000)), T + 80_000);
		ProfitTracker.Stats st = q.stats(T + 80_000, 5);
		assertEquals(2, st.allTime().buyable());
		assertEquals(700_000, st.allTime().profit());
		assertEquals(List.of("B", "A"), st.top().stream().map(ProfitTracker.Flip::name).toList());
	}

	@Test
	void secondsFormat() {
		assertEquals("0.8 s", ProfitTracker.secs(800));
		assertEquals("3m 05s", ProfitTracker.secs(185_000));
		assertEquals("1h 02m", ProfitTracker.secs(3_720_000));
	}

	// ---- whole pipeline ------------------------------------------------------------------------------------------

	static final String PROFIT_HOOK = "https://discord.com/api/webhooks/987654321098/PROFITTOKENprofittokenPROFIT";

	@Test
	void endToEndASaleAfterTheAlertReachesTheProfitWebhook(@TempDir Path dir) throws Exception {
		Path base = dir.resolve("app");
		StandaloneTest.writeStandaloneConfig(base, 1);
		AtomicFiles.writeString(base.resolve("config/secrets.json"), "{\"discordWebhookUrl\":\"" + StandaloneTest.HOOK
				+ "\",\"discordProfitWebhookUrl\":\"" + PROFIT_HOOK + "\"}");
		long now = System.currentTimeMillis();
		List<SyntheticData.Auction> market = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			market.add(SyntheticData.Auction.bin("TEST_SWORD", 10_000_000L + i * 100_000L, now - 3_600_000L));
		}
		SyntheticData.Auction cheap = SyntheticData.Auction.bin("TEST_SWORD", 6_000_000L, now + 5_000);
		List<SyntheticData.Auction> withCheap = new ArrayList<>(market);
		withCheap.add(cheap);
		AtomicReference<String> ended = new AtomicReference<>(SyntheticData.endedJson(now, List.of(), now));
		ScriptedTransport t = StandaloneTest.hypixel(List.of(market, withCheap, market),
				List.of(now - 120_000, now - 60_000, now), 1000, ended::get);
		ByteArrayOutputStream printed = new ByteArrayOutputStream();
		StandaloneMain app = StandaloneTest.app(base, t, printed);
		assertEquals(0, app.start(), printed.toString(StandardCharsets.UTF_8));
		try {
			var collector = app.serviceForTest().collector();
			collector.pollOnce(null);
			collector.pollOnce(null);
			StandaloneTest.await(() -> app.profitForTest().get(cheap.uuid()) != null, 6000, "the alert to be confirmed");
			// Someone buys it a second after it reached Discord; auctions_ended reports it with the next snapshot.
			long soldAt = app.profitForTest().get(cheap.uuid()).alertedServerMs() + 1_000;
			ended.set(SyntheticData.endedJson(now, List.of(cheap), soldAt));
			collector.pollOnce(null);
			assertEquals(ProfitTracker.Status.BUYABLE, app.profitForTest().get(cheap.uuid()).status());
			StandaloneTest.await(() -> profitBodies(t).stream().anyMatch(b -> b.contains("Could have bought")), 6000,
					"the result line");
			StandaloneTest.await(() -> profitBodies(t).stream().anyMatch(b -> b.contains("Profit tracker")
					&& b.contains("1 buyable")), 15_000, "the summary with the result");
			String out = printed.toString(StandardCharsets.UTF_8);
			assertTrue(out.contains("[PROFIT] Could have bought: [TEST] TEST_SWORD"), out);
			assertTrue(out.contains("sold 1.0 s after the alert"), out);
			assertFalse(out.contains("PROFITTOKEN"), "webhook token printed");
			assertTrue(StandaloneTest.count(t, "PROFITTOKEN") >= 2, "the summary and the result went to the profit webhook");
		} finally {
			app.stop();
		}
	}

	/** Messages for the profit webhook, told apart by their title (the board's is "AH Flipper"). */
	static List<String> profitBodies(ScriptedTransport t) {
		synchronized (t.postedBodies) {
			return t.postedBodies.stream().filter(b -> b.contains("\"embeds\"") && !b.contains("\"title\":\"AH Flipper\""))
					.toList();
		}
	}
}
