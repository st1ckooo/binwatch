package dev.binwatch.value;

import dev.binwatch.config.BinWatchConfig;
import dev.binwatch.util.Stats;
import dev.binwatch.util.TimeFmt;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Transparent, conservative valuation.
 *
 * <ol>
 * <li>LOWEST_BIN: cheapest other BIN listing of the exact same comparison key in the latest usable API snapshot.</li>
 * <li>ENDED_AUCTIONS: median of recorded BIN sales of the same key (from the documented auctions_ended endpoint).</li>
 * <li>The market estimate is the LOWER of the available sources, minus the configured undercut.</li>
 * <li>Confidence grows with the number of comparable listings and recorded sales, and is reduced when sources
 * disagree, the discount is suspiciously large, or data is sparse.</li>
 * </ol>
 */
public final class Valuator {
	private final BinWatchConfig.Valuation cfg;
	private final boolean lowestBinEnabled;
	private final boolean salesEnabled;

	public Valuator(BinWatchConfig config) {
		this.cfg = config.valuation;
		this.lowestBinEnabled = config.sourceEnabled("LOWEST_BIN");
		this.salesEnabled = config.sourceEnabled("ENDED_AUCTIONS");
	}

	public Valuation value(String key, UUID self, long unitPrice, PriceIndex index, List<Long> recentSaleUnitPrices,
			Long newestSaleMs, long nowMs) {
		List<String> notes = new ArrayList<>();
		if (key == null) {
			return Valuation.invalid("item has no SkyBlock id, cannot be compared", notes);
		}
		if (unitPrice <= 0) {
			return Valuation.invalid("listing price is missing or invalid", notes);
		}

		Long lowestOther = null;
		Long secondOther = null;
		int comparables = 0;
		boolean indexUsable = false;
		if (lowestBinEnabled && index != null) {
			long age = nowMs - index.builtAtMs();
			if (age > cfg.maxIndexAgeSeconds * 1000L) {
				notes.add("lowest-BIN index is stale (" + TimeFmt.duration(Math.max(0, age)) + " old), not used");
			} else {
				indexUsable = true;
				PriceIndex.Comparables c = index.comparablesExcluding(key, self);
				comparables = c.totalListings();
				if (!c.unitPrices().isEmpty()) {
					lowestOther = c.unitPrices().get(0);
					secondOther = c.unitPrices().size() > 1 ? c.unitPrices().get(1) : null;
				}
				if (!"COMPLETE".equals(index.snapshotStatus())) {
					notes.add("index built from a " + index.snapshotStatus() + " snapshot");
				}
			}
		}

		Long salesMedian = null;
		int salesSamples = recentSaleUnitPrices == null ? 0 : recentSaleUnitPrices.size();
		if (salesEnabled && salesSamples >= cfg.minSalesSamples) {
			long[] sorted = recentSaleUnitPrices.stream().mapToLong(Long::longValue).sorted().toArray();
			salesMedian = Stats.median(sorted);
		} else if (salesEnabled && salesSamples > 0) {
			notes.add("only " + salesSamples + " recorded sale(s); need " + cfg.minSalesSamples);
		}

		if (lowestOther == null && salesMedian == null) {
			return Valuation.invalid("no comparable market data for this exact variant", notes);
		}

		long market;
		double confidence;
		if (lowestOther != null && salesMedian != null) {
			market = Math.min(lowestOther, salesMedian);
			double agreement = (double) Math.min(lowestOther, salesMedian) / Math.max(lowestOther, salesMedian);
			double cL = Math.min(1.0, comparables / (double) cfg.targetComparableListings);
			double cS = Math.min(1.0, salesSamples / (double) cfg.targetSalesSamples);
			confidence = 0.45 * cL + 0.45 * cS + 0.10 * agreement;
			if (agreement < 0.5) {
				confidence *= 0.6;
				notes.add("lowest BIN and recorded sales disagree by more than 2x");
			}
		} else if (lowestOther != null) {
			market = lowestOther;
			confidence = 0.7 * Math.min(1.0, comparables / (double) cfg.targetComparableListings);
			notes.add("no sales history; lowest-BIN only");
		} else {
			market = salesMedian;
			confidence = 0.6 * Math.min(1.0, salesSamples / (double) cfg.targetSalesSamples);
			notes.add(indexUsable ? "no other BIN listing of this variant; sales history only" : "sales history only");
		}
		if (comparables == 1) {
			notes.add("only one other listing of this variant");
		}
		if (lowestOther != null && secondOther != null && lowestOther < secondOther * 0.8) {
			notes.add("next-cheapest listing is itself well below the one after it");
		}

		long unitResale = (long) Math.floor(market * (1.0 - cfg.undercutPercent / 100.0));
		if (unitResale <= 0) {
			return Valuation.invalid("estimated resale value is not positive", notes);
		}
		double discount = (unitResale - unitPrice) * 100.0 / unitResale;
		if (discount > cfg.suspiciousDiscountPercent) {
			confidence *= 0.5;
			notes.add(String.format(java.util.Locale.ROOT,
					"discount %.0f%% exceeds %.0f%%: possible different variant or bait listing", discount,
					cfg.suspiciousDiscountPercent));
		}

		StringBuilder source = new StringBuilder();
		Long valuationTs = null;
		if (lowestOther != null) {
			source.append("Lowest other BIN of ").append(comparables).append(" listing(s), API snapshot ")
					.append(TimeFmt.utc(index.snapshotLastUpdatedMs(), "unknown"));
			valuationTs = index.snapshotLastUpdatedMs();
		}
		if (salesMedian != null) {
			if (!source.isEmpty()) {
				source.append("; ");
			}
			source.append("median of ").append(salesSamples).append(" recorded BIN sale(s), last ")
					.append(cfg.salesLookbackHours).append("h");
			if (valuationTs == null) {
				valuationTs = newestSaleMs;
			}
		}
		source.append("; lower value used, minus ").append(cfg.undercutPercent).append("% undercut");
		return new Valuation(true, null, unitResale, Math.max(0, Math.min(1, confidence)), source.toString(),
				valuationTs, lowestOther, secondOther, comparables, salesMedian, salesSamples, List.copyOf(notes));
	}
}
