package dev.binwatch;

import dev.binwatch.config.BinWatchConfig;
import dev.binwatch.item.ItemNormalizer;
import dev.binwatch.item.NormalizedItem;
import dev.binwatch.store.Records;
import dev.binwatch.value.FeeModel;
import dev.binwatch.value.FlipCandidate;
import dev.binwatch.value.FlipEvaluator;
import dev.binwatch.value.PriceIndex;
import dev.binwatch.value.Valuation;
import dev.binwatch.value.Valuator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValuationTest {
	static final long NOW = 1_793_310_000_000L;
	final BinWatchConfig cfg = new BinWatchConfig();
	final ItemNormalizer norm = new ItemNormalizer(false, cfg.valuation.valueAttributes);

	PriceIndex index(String key, long... prices) {
		List<PriceIndex.Source> s = new ArrayList<>();
		for (long p : prices) {
			s.add(new PriceIndex.Source(UUID.randomUUID(), key, p));
		}
		return PriceIndex.build(s, NOW - 10_000, NOW - 20_000, "COMPLETE");
	}

	@Test
	void feeModel() {
		FeeModel f = new FeeModel(cfg.fees);
		assertEquals(50_000, f.listingFee(5_000_000));          // 1% below 10M
		assertEquals(400_000, f.listingFee(20_000_000));        // 2% below 100M
		assertEquals(5_000_000, f.listingFee(200_000_000));     // 2.5% above
		assertEquals(0, f.claimTax(1_000_000));                 // not above threshold
		assertEquals(200_000, f.claimTax(20_000_000));          // 1%
		assertEquals(600_000, f.totalCosts(20_000_000));
	}

	@Test
	void profitAndDiscountCalculation() {
		String key = "HYPERION|tier=LEGENDARY";
		Valuation v = new Valuator(cfg).value(key, UUID.randomUUID(), 15_000_000, index(key, 20_000_000, 20_500_000,
				21_000_000, 21_000_000, 22_000_000), List.of(), null, NOW);
		assertTrue(v.valid());
		assertEquals(19_800_000, v.estimatedUnitResale());       // 20M lowest other minus 1% undercut
		assertEquals(0.7, v.confidence(), 1e-9);                 // lowest-BIN only, 5/5 comparables
		NormalizedItem item = norm.normalize(Map.of("id", "HYPERION"), "Hyperion", "LEGENDARY", 1);
		FlipCandidate c = new FlipEvaluator(cfg).evaluate(listing(item, 15_000_000, Records.KIND_NEW), v);
		assertEquals(19_800_000, c.estimatedResale());
		assertEquals(4_800_000, c.estimatedGrossProfit());
		// fees on 19.8M: 2% listing 396,000 + 1% claim 198,000
		assertEquals(594_000, c.estimatedFees());
		assertEquals(4_206_000, c.estimatedNetProfit());
		assertEquals(24.24, c.discountPercent(), 0.01);
		assertTrue(c.passed(), String.valueOf(c.rejectReasons()));
		assertTrue(c.passReason().contains("confidence 0.70"));
		assertTrue(c.dataQualityNote().contains("not guaranteed"));
	}

	@Test
	void excludesSelfFromComparables() {
		String key = "K";
		UUID self = UUID.randomUUID();
		PriceIndex idx = PriceIndex.build(List.of(new PriceIndex.Source(self, key, 100), new PriceIndex.Source(UUID.randomUUID(), key, 500)),
				NOW, NOW, "COMPLETE");
		PriceIndex.Comparables c = idx.comparablesExcluding(key, self);
		assertEquals(List.of(500L), c.unitPrices());
		assertEquals(1, c.totalListings());
	}

	@Test
	void invalidValuationsNeverAlert() {
		Valuator val = new Valuator(cfg);
		assertFalse(val.value(null, UUID.randomUUID(), 100, null, List.of(), null, NOW).valid());
		assertFalse(val.value("K", UUID.randomUUID(), 100, null, List.of(), null, NOW).valid());
		assertFalse(val.value("K", UUID.randomUUID(), 0, index("K", 500), List.of(), null, NOW).valid());
		// Stale index is not used.
		PriceIndex stale = PriceIndex.build(List.of(new PriceIndex.Source(UUID.randomUUID(), "K", 500)), NOW - 3_600_000, NOW, "COMPLETE");
		Valuation v = val.value("K", UUID.randomUUID(), 100, stale, List.of(), null, NOW);
		assertFalse(v.valid());
		assertTrue(v.notes().stream().anyMatch(n -> n.contains("stale")));

		NormalizedItem item = norm.normalize(Map.of("id", "X"), "X", "RARE", 1);
		FlipCandidate c = new FlipEvaluator(cfg).evaluate(listing(item, 100, Records.KIND_NEW), v);
		assertFalse(c.passed());
		assertTrue(c.rejectReasons().get(0).startsWith("valuation invalid"));
	}

	@Test
	void lowConfidenceAndSparseDataAreRejected() {
		String key = "K";
		Valuation sparse = new Valuator(cfg).value(key, UUID.randomUUID(), 10_000_000, index(key, 50_000_000), List.of(), null, NOW);
		assertTrue(sparse.valid());
		assertTrue(sparse.confidence() < cfg.filters.minConfidence, "one comparable gives low confidence");
		NormalizedItem item = norm.normalize(Map.of("id", "K"), "K", null, 1);
		FlipCandidate c = new FlipEvaluator(cfg).evaluate(listing(item, 10_000_000, Records.KIND_NEW), sparse);
		assertFalse(c.passed());
		assertTrue(c.rejectReasons().stream().anyMatch(r -> r.startsWith("confidence")));
	}

	@Test
	void suspiciousDiscountAndSourceDisagreementLowerConfidence() {
		String key = "K";
		List<Long> sales = List.of(1_000_000L, 1_100_000L, 900_000L, 1_000_000L, 1_000_000L, 1_000_000L, 1_000_000L,
				1_000_000L, 1_000_000L, 1_000_000L);
		Valuator val = new Valuator(cfg);
		Valuation agree = val.value(key, UUID.randomUUID(), 700_000, index(key, 1_000_000, 1_000_000, 1_000_000, 1_000_000, 1_000_000), sales, NOW, NOW);
		Valuation disagree = val.value(key, UUID.randomUUID(), 700_000, index(key, 5_000_000, 5_000_000, 5_000_000, 5_000_000, 5_000_000), sales, NOW, NOW);
		assertTrue(agree.confidence() > disagree.confidence());
		assertEquals(990_000, disagree.estimatedUnitResale(), "lower of the two sources is used");
		Valuation bait = val.value(key, UUID.randomUUID(), 10_000, index(key, 1_000_000, 1_000_000, 1_000_000, 1_000_000, 1_000_000), sales, NOW, NOW);
		assertTrue(bait.confidence() < agree.confidence());
		assertTrue(bait.notes().stream().anyMatch(n -> n.contains("bait")));
	}

	@Test
	void filtersAndExclusions() {
		String key = "HYPERION|tier=LEGENDARY";
		Valuation v = new Valuator(cfg).value(key, UUID.randomUUID(), 15_000_000, index(key, 20_000_000, 20_000_000,
				20_000_000, 20_000_000, 20_000_000), List.of(), null, NOW);
		NormalizedItem item = norm.normalize(Map.of("id", "HYPERION"), "Hyperion", "LEGENDARY", 1);
		cfg.filters.excludedItemIds = List.of("hyperion");
		assertFalse(new FlipEvaluator(cfg).evaluate(listing(item, 15_000_000, Records.KIND_NEW), v).passed());
		cfg.filters.excludedItemIds = List.of();
		cfg.filters.maxPurchasePrice = 10_000_000;
		assertFalse(new FlipEvaluator(cfg).evaluate(listing(item, 15_000_000, Records.KIND_NEW), v).passed());
		cfg.filters.maxPurchasePrice = 0;
		assertTrue(new FlipEvaluator(cfg).evaluate(listing(item, 15_000_000, Records.KIND_NEW), v).passed(),
				"0 means no price limit");
		FlipCandidate pre = new FlipEvaluator(cfg).evaluate(listing(item, 15_000_000, Records.KIND_PREEXISTING), v);
		assertFalse(pre.passed(), "pre-existing auctions are not alerted by default");
		cfg.filters.alertOnPreexisting = true;
		assertTrue(new FlipEvaluator(cfg).evaluate(listing(item, 15_000_000, Records.KIND_PREEXISTING), v).passed());
	}

	@Test
	void variantsGetDifferentKeys() {
		NormalizedItem clean = norm.normalize(Map.of("id", "HYPERION"), "Hyperion", "LEGENDARY", 1);
		NormalizedItem recomb = norm.normalize(Map.of("id", "HYPERION", "rarity_upgrades", 1), "Hyperion", "MYTHIC", 1);
		NormalizedItem starred = norm.normalize(Map.of("id", "HYPERION", "upgrade_level", 5), "Hyperion", "LEGENDARY", 1);
		NormalizedItem enchanted = norm.normalize(Map.of("id", "HYPERION", "enchantments", Map.of("sharpness", (byte) 6)), "Hyperion", "LEGENDARY", 1);
		NormalizedItem reforged = norm.normalize(Map.of("id", "HYPERION", "modifier", "heroic"), "Hyperion", "LEGENDARY", 1);
		assertNotEquals(clean.comparisonKey(), recomb.comparisonKey());
		assertNotEquals(clean.comparisonKey(), starred.comparisonKey());
		assertNotEquals(clean.comparisonKey(), enchanted.comparisonKey());
		assertEquals(clean.comparisonKey(), reforged.comparisonKey(), "reforge excluded from key by default");
		assertTrue(starred.attributeSummary().contains("5 stars"));
		NormalizedItem pet = norm.normalize(Map.of("id", "PET", "petInfo", "{\"type\":\"GOLDEN_DRAGON\",\"tier\":\"LEGENDARY\",\"heldItem\":\"PET_ITEM_X\"}"),
				"§7[Lvl 100] §6Golden Dragon", null, 1);
		assertEquals("PET:GOLDEN_DRAGON|tier=LEGENDARY|lvl=100|held=PET_ITEM_X|skin=", pet.comparisonKey());
	}

	@Test
	void marketCheckLetsALooserLocalBarThrough() {
		String key = "HYPERION|tier=LEGENDARY";
		Valuation v = new Valuator(cfg).value(key, UUID.randomUUID(), 18_800_000, index(key, 20_000_000, 20_000_000,
				20_000_000, 20_000_000, 20_000_000), List.of(), null, NOW);
		NormalizedItem item = norm.normalize(Map.of("id", "HYPERION"), "Hyperion", "LEGENDARY", 1);
		// Resale 19.8M, price 18.8M: about +406k after fees and a 5% discount, under the strict 1M / 10% bar.
		FlipCandidate c = new FlipEvaluator(cfg).evaluate(listing(item, 18_800_000, Records.KIND_NEW), v);
		assertTrue(c.passed(), "with the market check on, SkyCofl gets to decide: " + c.rejectReasons());
		assertFalse(FlipEvaluator.meetsStrictFilters(c, cfg.filters), "without market data it would not be alerted");

		FlipEvaluator.Listing synthetic = new FlipEvaluator.Listing(UUID.randomUUID(), item, "fallback", 18_800_000, true,
				NOW - 30_000, NOW + 3_600_000, NOW - 1_000, NOW - 2_000, "COMPLETE", Records.KIND_NEW, true);
		assertFalse(new FlipEvaluator(cfg).evaluate(synthetic, v).passed(), "synthetic data is never market-checked");
		cfg.market.enabled = false;
		assertFalse(new FlipEvaluator(cfg).evaluate(listing(item, 18_800_000, Records.KIND_NEW), v).passed());
	}

	static FlipEvaluator.Listing listing(NormalizedItem item, long price, String kind) {
		return new FlipEvaluator.Listing(UUID.randomUUID(), item, "fallback", price, true, NOW - 30_000, NOW + 3_600_000,
				NOW - 1_000, NOW - 2_000, "COMPLETE", kind, false);
	}
}
