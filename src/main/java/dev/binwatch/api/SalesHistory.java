package dev.binwatch.api;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded in-memory cache of recorded BIN sale prices per comparison key (the database holds the durable copy).
 * Keeps at most {@code perKeyCap} newest sales per key and nothing older than the lookback window.
 */
public final class SalesHistory {
	public record Sample(long endedMs, long unitPrice) {
	}

	private final Map<String, Deque<Sample>> byKey = new HashMap<>();
	private final int perKeyCap;
	private volatile long lookbackMs;

	public SalesHistory(int perKeyCap, long lookbackMs) {
		this.perKeyCap = perKeyCap;
		this.lookbackMs = lookbackMs;
	}

	public void setLookbackMs(long lookbackMs) {
		this.lookbackMs = lookbackMs;
	}

	public synchronized void add(String key, long endedMs, long unitPrice) {
		Deque<Sample> d = byKey.computeIfAbsent(key, k -> new ArrayDeque<>());
		d.addFirst(new Sample(endedMs, unitPrice));
		while (d.size() > perKeyCap) {
			d.removeLast();
		}
	}

	/** Unit prices within the lookback window, newest first. */
	public synchronized List<Long> recent(String key, long nowMs) {
		Deque<Sample> d = byKey.get(key);
		if (d == null) {
			return List.of();
		}
		long cutoff = nowMs - lookbackMs;
		List<Long> out = new ArrayList<>(d.size());
		for (Sample s : d) {
			if (s.endedMs() >= cutoff) {
				out.add(s.unitPrice());
			}
		}
		return out;
	}

	public synchronized Long newest(String key) {
		Deque<Sample> d = byKey.get(key);
		return d == null || d.isEmpty() ? null : d.peekFirst().endedMs();
	}

	public synchronized void prune(long nowMs) {
		long cutoff = nowMs - lookbackMs;
		byKey.values().forEach(d -> d.removeIf(s -> s.endedMs() < cutoff));
		byKey.values().removeIf(Deque::isEmpty);
	}

	public synchronized int keys() {
		return byKey.size();
	}
}
