package dev.binwatch.api;

import java.util.UUID;

/**
 * One auction from {@code /v2/skyblock/auctions}. Only documented fields are used.
 *
 * @param itemUuid the auction-level {@code item_uuid} field when present (the per-item UUID, not the auction UUID)
 */
public record ApiAuction(
		UUID uuid,
		String auctioneer,
		Long startMs,
		Long endMs,
		String itemName,
		String tier,
		String category,
		long startingBid,
		long highestBid,
		boolean bin,
		boolean claimed,
		String itemBytes,
		String itemUuid,
		Long lastUpdatedMs) {
}
