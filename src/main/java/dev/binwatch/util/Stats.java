package dev.binwatch.util;

import java.util.Arrays;
import java.util.Collection;

/** Order statistics over samples of milliseconds or coin values. */
public final class Stats {
	private Stats() {
	}

	public record Summary(int count, Long min, Long median, Long p90, Long max) {
		public static final Summary EMPTY = new Summary(0, null, null, null, null);
	}

	public static Summary summarize(Collection<Long> values) {
		if (values == null || values.isEmpty()) {
			return Summary.EMPTY;
		}
		long[] sorted = values.stream().mapToLong(Long::longValue).toArray();
		Arrays.sort(sorted);
		return new Summary(sorted.length, sorted[0], median(sorted), percentile(sorted, 90), sorted[sorted.length - 1]);
	}

	/** Median of a sorted array; averages the middle pair for even sizes (rounded toward negative infinity). */
	public static long median(long[] sorted) {
		int n = sorted.length;
		if (n == 0) {
			throw new IllegalArgumentException("empty");
		}
		if (n % 2 == 1) {
			return sorted[n / 2];
		}
		return Math.floorDiv(sorted[n / 2 - 1] + sorted[n / 2], 2);
	}

	/** Nearest-rank percentile of a sorted array. */
	public static long percentile(long[] sorted, double pct) {
		int n = sorted.length;
		if (n == 0) {
			throw new IllegalArgumentException("empty");
		}
		int rank = (int) Math.ceil(pct / 100.0 * n);
		return sorted[Math.min(n, Math.max(1, rank)) - 1];
	}
}
