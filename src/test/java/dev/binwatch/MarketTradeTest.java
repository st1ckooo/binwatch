package dev.binwatch;

import dev.binwatch.api.ScriptedTransport;
import dev.binwatch.config.BinWatchConfig;
import dev.binwatch.discord.FlipEmbed;
import dev.binwatch.discord.SummaryPublisher;
import dev.binwatch.market.CoflProvider;
import dev.binwatch.market.MarketCheck;
import dev.binwatch.market.MarketException;
import dev.binwatch.market.MarketQuote;
import dev.binwatch.store.ObservationStore;
import dev.binwatch.store.Records.Trade;
import dev.binwatch.store.Records.TradeStatus;
import dev.binwatch.trade.ProfitSummary;
import dev.binwatch.trade.TradeChatParser;
import dev.binwatch.trade.TradeLedger;
import dev.binwatch.value.FeeModel;
import dev.binwatch.value.FlipCandidate;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketTradeTest {
	static final long T0 = 1_793_310_000_000L;
	static final String FAKE_URL = "https://discord.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyz0123456789ABCD";
	static final String COFL_BODY = "[{\"lbin\":9500000,\"median\":10000000,\"volume\":12.0,"
			+ "\"lbinLink\":\"https://sky.coflnet.com/a/d2017956a933420bb\",\"fastSell\":9000000,\"itemKey\":\"X\"}]";
	final MutableClock clock = new MutableClock(T0);

	static MarketQuote quote(double volume) {
		return new MarketQuote(9_500_000, "d2017956a933420bb", 10_000_000, volume, 9_000_000, "X", T0, "SkyCofl");
	}

	@Test
	void coflReplyIsParsedAndA429PausesFurtherRequests() throws Exception {
		ScriptedTransport t = new ScriptedTransport().enqueue("/api/price/nbt",
				() -> ScriptedTransport.ok(COFL_BODY),
				() -> ScriptedTransport.status(429, Map.of("Retry-After", List.of("30")), ""));
		CoflProvider p = new CoflProvider(t, "https://sky.coflnet.com", clock, 60, Duration.ofSeconds(3));
		MarketQuote q = p.quote("H4sIAAAA");
		assertEquals(9_500_000, q.lowestBin());
		assertEquals(10_000_000, q.median());
		assertEquals(12.0, q.dailySales());
		assertEquals("d2017956a933420bb", q.lowestBinAuctionPrefix());
		assertTrue(t.postedBodies.get(0).contains("\"fullInventoryNbt\":\"H4sIAAAA\""));

		clock.advance(1_000);
		assertThrows(MarketException.class, () -> p.quote("H4sIAAAA"));
		MarketException paused = assertThrows(MarketException.class, () -> p.quote("H4sIAAAA"));
		assertTrue(paused.getMessage().contains("pause"));
		assertEquals(2, t.requests.size(), "no request is sent during the pause");
		assertThrows(MarketException.class, () -> p.quote(null), "no item data, no request");
	}

	@Test
	void aBurstOfFlipsIsCheckedWithoutWaitingUpToTheTenSecondLimit() throws Exception {
		ScriptedTransport t = new ScriptedTransport().whenEmpty("/api/price/nbt", u -> ScriptedTransport.ok(COFL_BODY));
		CoflProvider p = new CoflProvider(t, "https://sky.coflnet.com", clock, 60, Duration.ofSeconds(3));
		for (int i = 0; i < 25; i++) {
			p.quote("H4sIAAAA"); // the clock does not move: any wait would show up as an exception
		}
		MarketException full = assertThrows(MarketException.class, () -> p.quote("H4sIAAAA"));
		assertTrue(full.getMessage().contains("budget"), full.getMessage());
		clock.advance(10_000);
		assertEquals(10_000_000, p.quote("H4sIAAAA").median(), "the window has passed");
		assertEquals(26, t.requests.size());
	}

	@Test
	void coflReplyWithoutPricesIsAnError() {
		ScriptedTransport t = new ScriptedTransport().whenEmpty("/api/price/nbt", u -> ScriptedTransport.ok("[]"));
		CoflProvider p = new CoflProvider(t, "https://sky.coflnet.com/", clock, 60, Duration.ofSeconds(3));
		assertThrows(MarketException.class, () -> p.quote("H4sIAAAA"));
		assertEquals("/api/price/nbt", t.requests.get(0).getPath());
	}

	@Test
	void worthItCheckGivesListingPriceAndDuration() {
		BinWatchConfig cfg = new BinWatchConfig();
		FeeModel fees = new FeeModel(cfg.fees);
		UUID other = UUID.fromString("11111111-2222-3333-4444-555555555555");
		MarketCheck m = MarketCheck.evaluate(quote(12), other, 8_000_000, fees, cfg.market, 500_000, "attr");
		// Lowest BIN (9.5M) is below the median, undercut 1%: 9,405,000; fees 1% listing + 1% claim tax.
		assertEquals(9_405_000, m.listPrice());
		assertEquals(9_405_000 - 188_100 - 8_000_000, m.netProfit());
		assertEquals(6, m.listHours(), "sells every 2 h, listed for at least twice that");
		assertTrue(m.worthIt());

		UUID self = UUID.fromString("d2017956-a933-420b-b000-000000000000");
		MarketCheck own = MarketCheck.evaluate(quote(12), self, 8_000_000, fees, cfg.market, 500_000, "attr");
		assertEquals(9_900_000, own.listPrice(), "the cheapest BIN is this auction, so the median is used");

		MarketCheck slow = MarketCheck.evaluate(quote(0.25), other, 8_000_000, fees, cfg.market, 500_000, "attr");
		assertFalse(slow.worthIt());
		assertEquals(48, slow.listHours());
		assertEquals(2, slow.reasons().size(), "too few sales and too slow to sell: " + slow.reasons());

		MarketCheck thin = MarketCheck.evaluate(quote(12), other, 9_300_000, fees, cfg.market, 500_000, "attr");
		assertFalse(thin.worthIt());
		assertTrue(thin.reasons().get(0).contains("below minimum"));
	}

	@Test
	void alertShowsMarketAdviceAndAttribution() {
		BinWatchConfig cfg = new BinWatchConfig();
		FlipCandidate c = FlipEmbed.sampleCandidate(T0);
		MarketCheck m = MarketCheck.evaluate(quote(12), c.auctionUuid(), 8_000_000, new FeeModel(cfg.fees), cfg.market,
				500_000, "Prices provided by SkyCofl (sky.coflnet.com)");
		String json = FlipEmbed.build(c.withMarket(m), 0x00FF00, T0, 5_000).toString();
		assertTrue(json.contains("Market (SkyCofl)"));
		assertTrue(json.contains("List at 9,405,000 for 6h"));
		assertTrue(json.contains("Prices provided by SkyCofl"));
		String unavailable = FlipEmbed.build(c.withMarket(MarketCheck.unavailable("timeout", "SkyCofl", "attr")), 0, T0, 5_000)
				.toString();
		assertTrue(unavailable.contains("No market data (timeout)"));
	}

	@Test
	void chatParserRecognisesAuctionMessages() {
		assertEquals(new TradeChatParser.Purchased("Heroic Hyperion ✪✪✪✪✪", 900_000_000),
				TradeChatParser.parse("You purchased Heroic Hyperion ✪✪✪✪✪ for 900,000,000 coins!"));
		assertEquals(new TradeChatParser.Sold("[MVP+] Someone", "Juju Shortbow", 12_500_000),
				TradeChatParser.parse("[Auction] [MVP+] Someone bought Juju Shortbow for 12,500,000 coins CLICK"));
		assertEquals(new TradeChatParser.Collected(12_375_000, "Juju Shortbow", "[MVP+] Someone"),
				TradeChatParser.parse("You collected 12,375,000 coins from selling Juju Shortbow to [MVP+] Someone in an auction!"));
		assertInstanceOf(TradeChatParser.Listed.class, TradeChatParser.parse("BIN Auction started for Juju Shortbow!"));
		assertNull(TradeChatParser.parse("You bought Enchanted Bread for 10 coins!"));
		assertEquals("heroic hyperion", TradeChatParser.nameKey("Heroic Hyperion ✪✪✪✪✪"));
		assertEquals("enchanted diamond", TradeChatParser.nameKey("2x Enchanted Diamond"));
	}

	@Test
	void ledgerFollowsAFlipFromPurchaseToCollection() throws Exception {
		try (ObservationStore store = ObservationStore.openInMemory("trades-" + UUID.randomUUID())) {
			BinWatchConfig cfg = new BinWatchConfig();
			TradeLedger ledger = new TradeLedger(store, clock, () -> cfg);
			int[] changes = {0};
			ledger.setOnChange(() -> changes[0]++);
			FlipCandidate alert = FlipEmbed.sampleCandidate(T0);
			ledger.rememberAlert(alert);

			List<String> reply = ledger.onChat("You purchased Heroic Hyperion ✪✪✪✪✪ for 900,000,000 coins!");
			assertTrue(reply.get(0).startsWith("Bought Heroic Hyperion"), reply.toString());
			assertTrue(reply.get(0).contains("List at 1,030,000,000"), reply.toString());
			Trade bought = ledger.trades().get(0);
			assertEquals(TradeStatus.BOUGHT, bought.status());
			assertEquals(alert.auctionUuid(), bought.auctionUuid());
			assertEquals(alert.estimatedNetProfit(), bought.expectedNet());

			ledger.onChat("BIN Auction started for Heroic Hyperion ✪✪✪✪✪!");
			assertEquals(TradeStatus.LISTED, ledger.trades().get(0).status());

			clock.advance(3_600_000);
			ledger.onChat("[Auction] [VIP] Buyer bought Heroic Hyperion ✪✪✪✪✪ for 1,000,000,000 coins CLICK");
			Trade sold = ledger.trades().get(0);
			assertEquals(TradeStatus.SOLD, sold.status());
			// 1B - 2.5% listing fee - 1% claim tax - 900M.
			assertEquals(65_000_000, sold.profit());

			ledger.onChat("You collected 990,000,000 coins from selling Heroic Hyperion ✪✪✪✪✪ to [VIP] Buyer in an auction!");
			Trade done = ledger.trades().get(0);
			assertEquals(TradeStatus.COLLECTED, done.status());
			assertEquals(65_000_000, done.profit());
			assertEquals(4, changes[0]);

			ledger.onChat("You purchased Juju Shortbow for 10,000,000 coins!");
			ledger.onChat("[Auction] Bob bought Dirt for 10 coins");
			assertEquals(3, ledger.trades().size());

			ProfitSummary.Stats s = ProfitSummary.compute(ledger.trades(), clock.millis(), 10);
			assertEquals(65_000_000, s.overall());
			assertEquals(65_000_000, s.lastHour());
			assertEquals(1, s.sold());
			assertEquals(1, s.openCount());
			assertEquals(10_000_000, s.openInvested());
			assertEquals(2, s.top().size(), "untracked sales are not listed");
			assertEquals("Heroic Hyperion ✪✪✪✪✪", s.top().get(0).itemName());
			clock.advance(2 * 3_600_000);
			assertEquals(0, ProfitSummary.compute(ledger.trades(), clock.millis(), 10).lastHour());
		}
	}

	@Test
	void summaryIsPostedOnceThenEditedInPlace() throws Exception {
		try (ObservationStore store = ObservationStore.openInMemory("summary-" + UUID.randomUUID())) {
			BinWatchConfig cfg = new BinWatchConfig();
			ScriptedTransport t = new ScriptedTransport()
					.enqueue("ABCD?wait=true", () -> ScriptedTransport.ok("{\"id\":\"111\"}"),
							() -> ScriptedTransport.ok("{\"id\":\"222\"}"))
					.enqueue("/messages/111", () -> ScriptedTransport.ok("{}"),
							() -> ScriptedTransport.status(404, Map.of(), "{\"message\":\"Unknown Message\"}"))
					.enqueue("/messages/222", () -> ScriptedTransport.ok("{}"));
			List<Trade> trades = new java.util.ArrayList<>();
			SummaryPublisher p = new SummaryPublisher(new dev.binwatch.discord.WebhookClient(t), clock, () -> cfg, () -> FAKE_URL, store, () -> trades, () -> "attr");

			assertTrue(p.publish(false).startsWith("posted"));
			assertEquals("unchanged", p.publish(false), "nothing new: no request");
			trades.add(new Trade(1L, "Juju Shortbow", "juju shortbow", null, 10_000_000L, T0, 12_000_000L, 6, 1_500_000L,
					null, null, null, null, null, null, null, TradeStatus.BOUGHT));
			assertTrue(p.publish(false).startsWith("updated"));
			clock.advance(60_000);
			assertTrue(p.publish(true).startsWith("posted"), "message deleted in Discord: a new one is posted");
			assertTrue(p.publish(true).startsWith("updated"));

			assertEquals(List.of("POST", "PATCH", "PATCH", "POST", "PATCH"), t.methods);
			assertTrue(t.requests.get(1).getPath().endsWith("/messages/111"));
			assertTrue(t.requests.get(4).getPath().endsWith("/messages/222"));
			assertEquals("222", store.getMeta("summary_message_id"));
			assertTrue(t.postedBodies.get(1).contains("Juju Shortbow"));
			assertFalse(store.getMeta("summary_webhook_hash").contains("abcdefghijklmnop"), "only a hash is stored");
		}
	}
}
