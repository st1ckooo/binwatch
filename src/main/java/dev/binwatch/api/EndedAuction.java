package dev.binwatch.api;

import java.util.UUID;

/** One entry of {@code /v2/skyblock/auctions_ended} (auctions that ended in roughly the last 60 seconds). */
public record EndedAuction(UUID auctionId, long timestampMs, long price, boolean bin, String itemBytes) {
}
