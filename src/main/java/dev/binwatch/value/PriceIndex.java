package dev.binwatch.value;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable index of the cheapest BIN listings per comparison key, built from one API snapshot. Keeps the
 * {@value #KEEP} cheapest listings per key so a candidate can be compared against listings other than itself.
 */
public final class PriceIndex {
	static final int KEEP = 6;

	public record Listing(UUID auctionUuid, long unitPrice) {
	}

	public record Entry(List<Listing> cheapest, int totalListings) {
	}

	/** Other listings for a key, excluding the candidate, cheapest first. */
	public record Comparables(List<Long> unitPrices, int totalListings) {
		public static final Comparables NONE = new Comparables(List.of(), 0);
	}

	public record Source(UUID auctionUuid, String key, long unitPrice) {
	}

	private final Map<String, Entry> entries;
	private final long builtAtMs;
	private final long snapshotLastUpdatedMs;
	private final String snapshotStatus;

	private PriceIndex(Map<String, Entry> entries, long builtAtMs, long snapshotLastUpdatedMs, String snapshotStatus) {
		this.entries = entries;
		this.builtAtMs = builtAtMs;
		this.snapshotLastUpdatedMs = snapshotLastUpdatedMs;
		this.snapshotStatus = snapshotStatus;
	}

	public static PriceIndex build(Collection<Source> binListings, long builtAtMs, long snapshotLastUpdatedMs, String snapshotStatus) {
		Map<String, List<Listing>> grouped = new HashMap<>();
		for (Source s : binListings) {
			if (s.key() == null || s.unitPrice() <= 0) {
				continue;
			}
			grouped.computeIfAbsent(s.key(), k -> new ArrayList<>()).add(new Listing(s.auctionUuid(), s.unitPrice()));
		}
		Map<String, Entry> entries = new HashMap<>(grouped.size() * 2);
		grouped.forEach((key, list) -> {
			list.sort((a, b) -> Long.compare(a.unitPrice(), b.unitPrice()));
			entries.put(key, new Entry(List.copyOf(list.subList(0, Math.min(KEEP, list.size()))), list.size()));
		});
		return new PriceIndex(Collections.unmodifiableMap(entries), builtAtMs, snapshotLastUpdatedMs, snapshotStatus);
	}

	public Comparables comparablesExcluding(String key, UUID self) {
		Entry e = key == null ? null : entries.get(key);
		if (e == null) {
			return Comparables.NONE;
		}
		List<Long> prices = new ArrayList<>(e.cheapest().size());
		int total = e.totalListings();
		for (Listing l : e.cheapest()) {
			if (l.auctionUuid().equals(self)) {
				total--;
				continue;
			}
			prices.add(l.unitPrice());
		}
		return new Comparables(List.copyOf(prices), total);
	}

	public int keys() {
		return entries.size();
	}

	public long builtAtMs() {
		return builtAtMs;
	}

	public long snapshotLastUpdatedMs() {
		return snapshotLastUpdatedMs;
	}

	public String snapshotStatus() {
		return snapshotStatus;
	}
}
