package dev.binwatch.value;

import dev.binwatch.config.BinWatchConfig;
import dev.binwatch.item.NormalizedItem;
import dev.binwatch.store.Records;
import dev.binwatch.util.TimeFmt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Applies fees and the configured filters to a valued listing. Read-only: it never acts on an auction. */
public final class FlipEvaluator {
	private final BinWatchConfig.Filters filters;
	private final BinWatchConfig.Market market;
	private final FeeModel fees;

	public FlipEvaluator(BinWatchConfig config) {
		this.filters = config.filters;
		this.market = config.market;
		this.fees = new FeeModel(config.fees);
	}

	/** Whether a candidate also clears the strict local filters (used when the market check could not run). */
	public static boolean meetsStrictFilters(FlipCandidate c, BinWatchConfig.Filters f) {
		return c.valuation() != null && c.valuation().confidence() >= f.minConfidence
				&& c.estimatedNetProfit() >= f.minNetProfit && c.discountPercent() >= f.minDiscountPercent;
	}

	public record Listing(UUID auctionUuid, NormalizedItem item, String fallbackName, long price, boolean bin,
			Long auctionStartMs, Long auctionEndMs, long firstApiObservedMs, Long snapshotLastUpdatedMs,
			String snapshotStatus, String observationKind, boolean synthetic) {
	}

	public FlipCandidate evaluate(Listing l, Valuation v) {
		NormalizedItem item = l.item();
		String name = item != null && item.displayName() != null && !item.displayName().isBlank()
				? item.displayName() : l.fallbackName();
		int count = item == null ? 1 : item.count();
		List<String> reject = new ArrayList<>();

		long resale = v.valid() ? v.estimatedUnitResale() * count : 0;
		long gross = resale - l.price();
		long costs = v.valid() ? fees.totalCosts(resale) : 0;
		long net = resale - costs - l.price();
		double discount = resale > 0 ? (resale - l.price()) * 100.0 / resale : 0;

		// With the market check on, the local estimate only pre-selects; the market check makes the final call.
		boolean pre = market.enabled && !l.synthetic();
		double minConfidence = pre ? Math.min(filters.minConfidence, market.prefilterMinConfidence) : filters.minConfidence;
		long minNet = pre ? Math.min(filters.minNetProfit, market.prefilterMinNetProfit) : filters.minNetProfit;
		double minDiscount = pre ? Math.min(filters.minDiscountPercent, market.prefilterMinDiscountPercent)
				: filters.minDiscountPercent;

		if (!l.bin()) {
			reject.add("not a BIN auction");
		}
		if (!v.valid()) {
			reject.add("valuation invalid: " + v.invalidReason());
		} else {
			if (v.confidence() < minConfidence) {
				reject.add(String.format(Locale.ROOT, "confidence %.2f < %.2f", v.confidence(), minConfidence));
			}
			if (net < minNet) {
				reject.add("net profit " + TimeFmt.shortCoins(net) + " < " + TimeFmt.shortCoins(minNet));
			}
			if (discount < minDiscount) {
				reject.add(String.format(Locale.ROOT, "discount %.1f%% < %.1f%%", discount, minDiscount));
			}
		}
		if (l.price() <= 0) {
			reject.add("invalid price");
		}
		if (filters.maxPurchasePrice > 0 && l.price() > filters.maxPurchasePrice) {
			reject.add("price " + TimeFmt.shortCoins(l.price()) + " > max " + TimeFmt.shortCoins(filters.maxPurchasePrice));
		}
		String id = item == null ? null : item.skyblockId();
		if (id != null && filters.excludedItemIds.stream().anyMatch(x -> x.equalsIgnoreCase(id))) {
			reject.add("item id " + id + " is excluded");
		}
		if (name != null) {
			String lower = name.toLowerCase(Locale.ROOT);
			for (String kw : filters.excludedNameKeywords) {
				if (!kw.isBlank() && lower.contains(kw.toLowerCase(Locale.ROOT))) {
					reject.add("name matches excluded keyword '" + kw + "'");
				}
			}
		}
		if (Records.KIND_PREEXISTING.equals(l.observationKind()) && !filters.alertOnPreexisting) {
			reject.add("auction already existed when first observed (alertOnPreexisting=false)");
		}

		String passReason = null;
		if (reject.isEmpty()) {
			passReason = String.format(Locale.ROOT,
					"Net profit %s ≥ %s · discount %.1f%% ≥ %.1f%% · confidence %.2f ≥ %.2f%s\nBased on %d comparable listing(s) and %d recorded sale(s)",
					TimeFmt.shortCoins(net), TimeFmt.shortCoins(minNet), discount,
					minDiscount, v.confidence(), minConfidence,
					filters.maxPurchasePrice > 0 ? " · price ≤ " + TimeFmt.shortCoins(filters.maxPurchasePrice) : "",
					v.comparableListings(), v.salesSamples());
		}

		List<String> quality = new ArrayList<>(v.notes());
		if (l.snapshotStatus() != null && !"COMPLETE".equals(l.snapshotStatus()) && !Records.PAGE_RECEIVED.equals(l.snapshotStatus())) {
			quality.add("observed in a " + l.snapshotStatus() + " API snapshot");
		}
		if (l.synthetic()) {
			quality.add(0, "SYNTHETIC TEST DATA - not a live auction");
		}
		quality.add("Estimate only; resale and profit are not guaranteed");

		return new FlipCandidate(l.auctionUuid(), name, id, item == null ? "" : item.attributeSummary(),
				item == null ? null : item.comparisonKey(), l.price(), count, l.auctionStartMs(), l.auctionEndMs(),
				l.firstApiObservedMs(), l.snapshotLastUpdatedMs(), l.observationKind(), v, resale, gross, costs, net,
				discount, reject.isEmpty(), passReason, List.copyOf(reject), String.join("; ", quality), l.synthetic(), item);
	}
}
