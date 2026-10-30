package dev.binwatch.client;

import dev.binwatch.config.BinWatchConfig;
import dev.binwatch.discord.FlipEmbed;
import dev.binwatch.market.MarketCheck;
import dev.binwatch.market.MarketQuote;
import dev.binwatch.value.FeeModel;
import dev.binwatch.value.FlipCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlipOverlayTest {
	@Test
	void bannerShowsProfitAndListingAdvice() {
		BinWatchConfig cfg = new BinWatchConfig();
		FlipCandidate c = FlipEmbed.sampleCandidate(1_793_310_000_000L);
		List<String> local = FlipOverlay.lines(c);
		assertEquals("BIN Watch: +93.95M profit after fees", local.get(0));
		assertTrue(local.get(1).contains("no market data"));

		MarketQuote q = new MarketQuote(1_100_000_000, null, 1_050_000_000, 12, 0, "X", 0, "SkyCofl");
		MarketCheck m = MarketCheck.evaluate(q, c.auctionUuid(), c.price(), new FeeModel(cfg.fees), cfg.market, 1_000_000, "a");
		List<String> withMarket = FlipOverlay.lines(c.withMarket(m));
		assertTrue(withMarket.get(0).startsWith("BIN Watch: +"));
		assertTrue(withMarket.get(1).startsWith("List at 1.04B for 6h"), withMarket.get(1));
	}

	@Test
	void viewAuctionArgumentIsParsedWithOrWithoutDashes() {
		UUID u = UUID.fromString("4800a2f5-d1d0-4f6d-84bf-e8ef4d35597d");
		assertEquals(u, FlipOverlay.parseUuid("4800a2f5d1d04f6d84bfe8ef4d35597d"));
		assertEquals(u, FlipOverlay.parseUuid(u.toString()));
		assertNull(FlipOverlay.parseUuid("not-a-uuid"));
	}
}
