package dev.binwatch.value;

import dev.binwatch.item.NormalizedItem;
import dev.binwatch.market.MarketCheck;

import java.util.List;
import java.util.UUID;

/**
 * A scored BIN listing. All money values are estimates in coins.
 *
 * @param synthetic true for test-mode data; synthetic candidates are never sent to Discord
 * @param item      parsed item, used for display (enchantments, upgrades, icon); null if the item could not be parsed
 */
public record FlipCandidate(
		UUID auctionUuid,
		String itemName,
		String itemId,
		String attributes,
		String normKey,
		long price,
		int count,
		Long auctionStartMs,
		Long auctionEndMs,
		long firstApiObservedMs,
		Long snapshotLastUpdatedMs,
		String observationKind,
		Valuation valuation,
		long estimatedResale,
		long estimatedGrossProfit,
		long estimatedFees,
		long estimatedNetProfit,
		double discountPercent,
		boolean passed,
		String passReason,
		List<String> rejectReasons,
		String dataQualityNote,
		boolean synthetic,
		NormalizedItem item,
		MarketCheck market) {

	public FlipCandidate(UUID auctionUuid, String itemName, String itemId, String attributes, String normKey, long price,
			int count, Long auctionStartMs, Long auctionEndMs, long firstApiObservedMs, Long snapshotLastUpdatedMs,
			String observationKind, Valuation valuation, long estimatedResale, long estimatedGrossProfit,
			long estimatedFees, long estimatedNetProfit, double discountPercent, boolean passed, String passReason,
			List<String> rejectReasons, String dataQualityNote, boolean synthetic, NormalizedItem item) {
		this(auctionUuid, itemName, itemId, attributes, normKey, price, count, auctionStartMs, auctionEndMs,
				firstApiObservedMs, snapshotLastUpdatedMs, observationKind, valuation, estimatedResale, estimatedGrossProfit,
				estimatedFees, estimatedNetProfit, discountPercent, passed, passReason, rejectReasons, dataQualityNote,
				synthetic, item, null);
	}

	/** Same candidate with a market check attached. */
	public FlipCandidate withMarket(MarketCheck m) {
		return new FlipCandidate(auctionUuid, itemName, itemId, attributes, normKey, price, count, auctionStartMs,
				auctionEndMs, firstApiObservedMs, snapshotLastUpdatedMs, observationKind, valuation, estimatedResale,
				estimatedGrossProfit, estimatedFees, estimatedNetProfit, discountPercent, passed, passReason, rejectReasons,
				dataQualityNote, synthetic, item, m);
	}

	/** Suggested resale price: the market check's when available, else the local estimate. */
	public long effectiveResale() {
		return market != null && market.available() ? market.listPrice() : estimatedResale;
	}

	/** Expected net profit: the market check's when available, else the local estimate. */
	public long effectiveNetProfit() {
		return market != null && market.available() ? market.netProfit() : estimatedNetProfit;
	}
}
