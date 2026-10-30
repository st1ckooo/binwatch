package dev.binwatch.api;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Tracks the auction snapshot timeline so the collector can sleep until just before the next snapshot publishes.
 * Pure bookkeeping (no I/O, no clock of its own) so the scheduling arithmetic is unit tested with fixed times.
 *
 * <p>Times: {@code lastUpdated} values are Hypixel server time; receive times are local. The offset between the two
 * is estimated from the HTTP {@code Date} header. Date is truncated to whole seconds and stamped before the response
 * travels, so each sample {@code date - receivedAt} underestimates the true offset by up to one second plus latency;
 * the maximum over a sliding window converges on the true offset minus latency.
 */
public final class SnapshotClock {
	static final long DEFAULT_INTERVAL_MS = 60_000;
	static final int OFFSET_WINDOW = 240;
	static final int PUBLISH_WINDOW = 10;

	private final Deque<Long> offsetSamples = new ArrayDeque<>();
	private final Deque<Long> publishDelays = new ArrayDeque<>();
	private Long currentSnapshot;
	private double avgIntervalMs = DEFAULT_INTERVAL_MS;
	/** Snapshots expected since {@link #currentSnapshot} that never appeared (Hypixel occasionally skips one). */
	private int missed;

	/** Records one {@code Date} header sample (server ms, 1 s resolution) received at local time {@code receivedAtMs}. */
	public synchronized void onDateSample(long serverDateMs, long receivedAtMs) {
		offsetSamples.addLast(serverDateMs - receivedAtMs);
		while (offsetSamples.size() > OFFSET_WINDOW) {
			offsetSamples.removeFirst();
		}
	}

	/** Estimated server time minus local time; 0 until the first sample. */
	public synchronized long offsetMs() {
		long best = Long.MIN_VALUE;
		for (long s : offsetSamples) {
			best = Math.max(best, s);
		}
		return best == Long.MIN_VALUE ? 0 : best;
	}

	public synchronized boolean hasOffset() {
		return !offsetSamples.isEmpty();
	}

	public long toServer(long localMs) {
		return localMs + offsetMs();
	}

	/**
	 * Records a new snapshot. {@code detectedAtMs} is the local time page 0 of it was received; {@code tight} says the
	 * collector was already probing when it appeared, so that time bounds the publish moment closely (a poll that
	 * merely happened to land later must not teach the clock that publishing is slow).
	 */
	public synchronized void onSnapshot(long lastUpdatedMs, long detectedAtMs, boolean tight) {
		if (currentSnapshot != null && lastUpdatedMs > currentSnapshot) {
			long delta = lastUpdatedMs - currentSnapshot;
			// One cadence step only; gaps after skipped snapshots are multiples and would inflate the average
			// (observed live: one 120 s gap pushed it to 72 s and every later wake-up was 12 s late).
			if (delta >= avgIntervalMs * 0.5 && delta <= avgIntervalMs * 1.5) {
				avgIntervalMs = avgIntervalMs * 0.8 + delta * 0.2;
			}
		}
		if (currentSnapshot == null || lastUpdatedMs > currentSnapshot) {
			currentSnapshot = lastUpdatedMs;
			missed = 0;
		}
		if (tight) {
			long delay = toServer(detectedAtMs) - lastUpdatedMs;
			if (delay >= 0 && delay <= 60_000) {
				publishDelays.addLast(delay);
				while (publishDelays.size() > PUBLISH_WINDOW) {
					publishDelays.removeFirst();
				}
			}
		}
	}

	public synchronized Long currentSnapshot() {
		return currentSnapshot;
	}

	public synchronized long avgIntervalMs() {
		return Math.round(avgIntervalMs);
	}

	/** Shortest recently observed gap between a snapshot's lastUpdated and its availability; 0 until measured. */
	public synchronized long minPublishDelayMs() {
		return publishDelays.stream().mapToLong(Long::longValue).min().orElse(0);
	}

	/** The expected snapshot did not appear: expect the one after it instead. */
	public synchronized void onMissed() {
		missed++;
	}

	/** Server time at which the next snapshot is expected to become available. */
	public synchronized long nextExpectedServerMs() {
		return currentSnapshot + Math.round(avgIntervalMs * (1 + missed)) + minPublishDelayMs();
	}

	/** Local time at which to start probing for the next snapshot. */
	public synchronized long wakeAtLocalMs(long preWakeMs) {
		return nextExpectedServerMs() - offsetMs() - preWakeMs;
	}

	/**
	 * True when the clock is more than one interval behind (e.g. after the computer slept): probing for "the next"
	 * snapshot would be pointless because several were published meanwhile, so the collector re-syncs instead.
	 */
	public synchronized boolean behind(long nowLocalMs) {
		return currentSnapshot == null || toServer(nowLocalMs) > nextExpectedServerMs() + Math.round(avgIntervalMs);
	}
}
