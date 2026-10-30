package dev.binwatch.value;

import java.util.List;

/**
 * Result of valuing one listing. An estimate, never a guaranteed resale value.
 *
 * @param estimatedUnitResale estimated per-unit resale price after the configured undercut
 * @param confidence          0..1, how much the estimate is supported by comparable data
 * @param source              human readable description of the data the estimate is based on
 * @param valuationTimestampMs time of the underlying market data (snapshot lastUpdated or newest sale)
 */
public record Valuation(
		boolean valid,
		String invalidReason,
		long estimatedUnitResale,
		double confidence,
		String source,
		Long valuationTimestampMs,
		Long lowestOtherBin,
		Long secondOtherBin,
		int comparableListings,
		Long salesMedian,
		int salesSamples,
		List<String> notes) {

	public static Valuation invalid(String reason, List<String> notes) {
		return new Valuation(false, reason, 0, 0, "none", null, null, null, 0, null, 0, List.copyOf(notes));
	}
}
