package dev.binwatch.store;

import java.util.UUID;

/** Row types persisted by {@link ObservationStore}. All timestamps are epoch milliseconds, UTC. */
public final class Records {
	private Records() {
	}

	/** Source-status values written to {@code api_obs.source_status}. */
	public static final String PAGE_RECEIVED = "PAGE_RECEIVED";

	/** Observation kinds: whether the auction was created while this collector was continuously watching. */
	public static final String KIND_NEW = "NEW_DURING_MONITORING";
	public static final String KIND_PREEXISTING = "PREEXISTING_AT_FIRST_SIGHT";
	public static final String KIND_SYNTHETIC = "SYNTHETIC_TEST_DATA";

	public record ApiObservation(
			UUID auctionUuid,
			String itemUuid,
			String itemId,
			String itemName,
			String normKey,
			String tier,
			boolean bin,
			long startingBid,
			int count,
			Long auctionStartMs,
			Long auctionEndMs,
			long firstApiObservedMs,
			Long snapshotLastUpdatedMs,
			String snapshotId,
			long pollId,
			int page,
			String sourceStatus,
			String observationKind) {
	}

	/** Life cycle of one flip you made by hand, as seen in your chat. */
	public enum TradeStatus {
		/** Bought; not listed yet (or listed while BIN Watch was not running). */
		BOUGHT,
		LISTED,
		/** Sold; coins not collected yet. */
		SOLD,
		COLLECTED,
		/** A sale or collection for an item whose purchase was never seen: no profit can be worked out. */
		UNTRACKED
	}

	/**
	 * One flip. {@code nameKey} matches chat messages to each other (item names without colour, symbols or case).
	 * Money in coins; {@code profit} is null until the sale is known and the purchase price is known.
	 */
	public record Trade(
			Long id,
			String itemName,
			String nameKey,
			UUID auctionUuid,
			Long boughtPrice,
			Long boughtMs,
			Long expectedListPrice,
			Integer expectedListHours,
			Long expectedNet,
			Long listedMs,
			Long soldPrice,
			String buyer,
			Long soldMs,
			Long collectedCoins,
			Long collectedMs,
			Long profit,
			TradeStatus status) {
	}

	public enum AlertStatus {
		QUEUED, SENT, FAILED, DROPPED, EXPIRED
	}

	public record Alert(
			UUID auctionUuid,
			String normKey,
			AlertStatus status,
			long createdMs,
			Long lastAttemptMs,
			int attempts,
			Long deliveredMs,
			String itemName,
			long price,
			long estimatedNetProfit,
			String detail) {
	}

	public record Sale(UUID auctionId, String normKey, long unitPrice, long endedMs) {
	}
}
