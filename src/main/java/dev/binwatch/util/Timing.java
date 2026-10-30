package dev.binwatch.util;

/**
 * Delay and observation-difference calculations. All inputs are epoch milliseconds (UTC).
 *
 * <p>A delay is only {@link Status#VALID} when both timestamps are plausible and the observation is not earlier than
 * the recorded auction start. Results are never rendered as negative durations: an observation that precedes the
 * recorded start (clock skew) is reported as invalid instead.
 */
public final class Timing {
	/** 2010-01-01T00:00:00Z. Anything earlier cannot be a real SkyBlock auction timestamp. */
	public static final long MIN_PLAUSIBLE_EPOCH_MS = 1_262_304_000_000L;

	public enum Status {
		VALID,
		MISSING_START,
		MISSING_OBSERVATION,
		INVALID_TIMESTAMP,
		START_IN_FUTURE,
		OBSERVED_BEFORE_START
	}

	public record Delay(Status status, long millis) {
		public boolean valid() {
			return status == Status.VALID;
		}

		public Long millisOrNull() {
			return valid() ? millis : null;
		}

		public static Delay invalid(Status status) {
			return new Delay(status, 0);
		}
	}

	private Timing() {
	}

	public static boolean plausible(Long epochMs, long nowMs, long futureToleranceMs) {
		return epochMs != null && epochMs >= MIN_PLAUSIBLE_EPOCH_MS && epochMs <= nowMs + futureToleranceMs;
	}

	/**
	 * Time from recorded auction start to an observation.
	 *
	 * @param nowMs current time, used to reject timestamps that lie in the future
	 * @param futureToleranceMs how far in the future a timestamp may be before it is considered invalid (clock skew)
	 */
	public static Delay delay(Long auctionStartMs, Long observedMs, long nowMs, long futureToleranceMs) {
		if (auctionStartMs == null || auctionStartMs <= 0) {
			return Delay.invalid(Status.MISSING_START);
		}
		if (observedMs == null || observedMs <= 0) {
			return Delay.invalid(Status.MISSING_OBSERVATION);
		}
		if (auctionStartMs < MIN_PLAUSIBLE_EPOCH_MS || observedMs < MIN_PLAUSIBLE_EPOCH_MS) {
			return Delay.invalid(Status.INVALID_TIMESTAMP);
		}
		if (auctionStartMs > nowMs + futureToleranceMs) {
			return Delay.invalid(Status.START_IN_FUTURE);
		}
		if (observedMs > nowMs + futureToleranceMs) {
			return Delay.invalid(Status.INVALID_TIMESTAMP);
		}
		if (observedMs < auctionStartMs) {
			return Delay.invalid(Status.OBSERVED_BEFORE_START);
		}
		return new Delay(Status.VALID, observedMs - auctionStartMs);
	}

	public static String apiDelayLabel(Delay delay) {
		return delay.valid()
				? "API observed " + TimeFmt.duration(delay.millis()) + " after recorded auction start"
				: "Unavailable (" + describe(delay.status()) + ")";
	}

	public static String describe(Status status) {
		return switch (status) {
			case VALID -> "valid";
			case MISSING_START -> "auction start time missing";
			case MISSING_OBSERVATION -> "not observed";
			case INVALID_TIMESTAMP -> "invalid timestamp";
			case START_IN_FUTURE -> "auction start lies in the future";
			case OBSERVED_BEFORE_START -> "observation precedes recorded start; clock skew suspected";
		};
	}
}
