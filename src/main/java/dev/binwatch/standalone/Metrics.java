package dev.binwatch.standalone;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Timing and counters of the standalone app. Durations measured inside this process use {@link System#nanoTime()};
 * spans that start at an event recorded as wall-clock time (a page received, Hypixel's lastUpdated) use wall time.
 */
final class Metrics {
	/** The last {@value #CAPACITY} values of one measurement, for medians and percentiles. */
	static final class Samples {
		static final int CAPACITY = 1000;
		private final long[] values = new long[CAPACITY];
		private int size;
		private int next;
		private long count;

		synchronized void add(long v) {
			values[next] = v;
			next = (next + 1) % CAPACITY;
			size = Math.min(size + 1, CAPACITY);
			count++;
		}

		synchronized long count() {
			return count;
		}

		synchronized long last() {
			return size == 0 ? -1 : values[(next - 1 + CAPACITY) % CAPACITY];
		}

		/** {@code p} in 0..100; -1 without samples. */
		synchronized long percentile(double p) {
			if (size == 0) {
				return -1;
			}
			long[] sorted = Arrays.copyOf(values, size);
			Arrays.sort(sorted);
			int i = (int) Math.ceil(p / 100.0 * size) - 1;
			return sorted[Math.max(0, Math.min(size - 1, i))];
		}

		String describe(String unit) {
			if (count() == 0) {
				return "no samples";
			}
			return String.format(Locale.ROOT, "median %d %s, p95 %d %s, max %d %s (%d samples)", percentile(50), unit,
					percentile(95), unit, percentile(100), unit, count());
		}
	}

	/** Hypixel's lastUpdated → page 0 of that snapshot received (server-clock corrected when an offset is known). */
	final Samples snapshotAgeAtDetectionMs = new Samples();
	/**
	 * Hypixel started serving the snapshot (bracketed by the last probe that still got the previous one) → its page 0
	 * received. Only when the bracket was tight enough to be meaningful.
	 */
	final Samples publishToPage0Ms = new Samples();
	/** Page 0 received → every page fetched and processed. */
	final Samples snapshotFetchMs = new Samples();
	/** CPU time spent valuing and filtering the new listings of one snapshot, in microseconds. */
	final Samples processingMicros = new Samples();
	final Samples candidatesPerSnapshot = new Samples();
	/** Page that contained the flip received → flip on the board (includes the SkyCofl check). */
	final Samples detectionToBoardMs = new Samples();
	final Samples embedBuildMicros = new Samples();
	final Samples discordRequestMs = new Samples();
	/** Page that contained the change received → Discord acknowledged the edit. */
	final Samples detectionToDiscordMs = new Samples();

	final AtomicLong snapshots = new AtomicLong();
	final AtomicLong incompleteSnapshots = new AtomicLong();
	final AtomicLong opportunitiesAdded = new AtomicLong();
	final AtomicLong opportunitiesChanged = new AtomicLong();
	final AtomicLong opportunitiesRemoved = new AtomicLong();
	final AtomicLong discordPosts = new AtomicLong();
	final AtomicLong discordEdits = new AtomicLong();
	final AtomicLong discordUnchangedSkips = new AtomicLong();
	final AtomicLong discordErrors = new AtomicLong();
	final AtomicLong discordRateLimits = new AtomicLong();
	final AtomicLong dryRunRenders = new AtomicLong();
}
