package dev.binwatch.standalone;

import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import dev.binwatch.config.BinWatchConfig;
import dev.binwatch.config.ConfigManager;
import dev.binwatch.market.MarketCheck;
import dev.binwatch.store.AtomicFiles;
import dev.binwatch.value.FlipCandidate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The current opportunities: flips that passed the existing filters and market check and are still listed. Keyed by
 * auction UUID, so the same auction is never shown twice. An entry leaves the board when a COMPLETE snapshot no longer
 * lists the auction (bought, cancelled or expired) or its end time has passed; partial snapshots cannot prove an
 * auction is gone, so they remove nothing.
 */
final class FlipBoard {
	/**
	 * One opportunity as displayed. Money values are the existing engine's: {@link FlipCandidate#effectiveResale()} and
	 * {@link FlipCandidate#effectiveNetProfit()} (SkyCofl's figures when the market check ran, else the local estimate).
	 *
	 * @param detectedAtMs          when the page containing the auction was received (wall clock)
	 * @param verifiedLastUpdatedMs lastUpdated of the newest snapshot known to still list it
	 * @param restored              loaded from disk after a restart and not yet seen in a snapshot of this run
	 */
	record Entry(UUID uuid, String name, String itemId, long price, long resale, long netProfit, double profitPercent,
			boolean marketChecked, Integer listHours, Long auctionEndMs, long detectedAtMs, long verifiedLastUpdatedMs,
			boolean restored) {

		Entry verifiedAt(long lastUpdatedMs) {
			return new Entry(uuid, name, itemId, price, resale, netProfit, profitPercent, marketChecked, listHours,
					auctionEndMs, detectedAtMs, Math.max(verifiedLastUpdatedMs, lastUpdatedMs), false);
		}

		Entry asRestored() {
			return new Entry(uuid, name, itemId, price, resale, netProfit, profitPercent, marketChecked, listHours,
					auctionEndMs, detectedAtMs, verifiedLastUpdatedMs, true);
		}

		boolean sameDisplay(Entry o) {
			return price == o.price && resale == o.resale && netProfit == o.netProfit && marketChecked == o.marketChecked
					&& restored == o.restored && java.util.Objects.equals(listHours, o.listHours)
					&& java.util.Objects.equals(name, o.name);
		}
	}

	record Diff(List<Entry> added, List<Entry> changed, List<Entry> removed) {
		static final Diff NONE = new Diff(List.of(), List.of(), List.of());

		boolean any() {
			return !added.isEmpty() || !changed.isEmpty() || !removed.isEmpty();
		}
	}

	/** Most profitable first, then the higher percentage, then a stable order. */
	static final Comparator<Entry> RANKING = Comparator.comparingLong(Entry::netProfit).reversed()
			.thenComparing(Comparator.comparingDouble(Entry::profitPercent).reversed())
			.thenComparing(Entry::uuid);

	private final Map<UUID, Entry> entries = new HashMap<>();
	private final Supplier<BinWatchConfig.Board> config;

	FlipBoard(Supplier<BinWatchConfig.Board> config) {
		this.config = config;
	}

	static Entry entryOf(FlipCandidate c) {
		MarketCheck m = c.market();
		boolean checked = m != null && m.available();
		long net = c.effectiveNetProfit();
		double pct = c.price() > 0 ? net * 100.0 / c.price() : 0;
		long verified = c.snapshotLastUpdatedMs() == null ? 0 : c.snapshotLastUpdatedMs();
		return new Entry(c.auctionUuid(), c.itemName(), c.itemId(), c.price(), c.effectiveResale(), net, pct, checked,
				checked ? m.listHours() : null, c.auctionEndMs(), c.firstApiObservedMs(), verified, false);
	}

	/** A flip the engine accepted. Re-offering an auction already on the board only counts when what is shown changed. */
	synchronized Diff offer(FlipCandidate c) {
		Entry e = entryOf(c);
		BinWatchConfig.Board cfg = config.get();
		Entry old = entries.get(e.uuid());
		if (e.netProfit() <= 0 || e.profitPercent() < cfg.minProfitPercent) {
			if (old != null) {
				entries.remove(e.uuid());
				return new Diff(List.of(), List.of(), List.of(old));
			}
			return Diff.NONE;
		}
		if (old == null) {
			entries.put(e.uuid(), e);
			List<Entry> dropped = trim(cfg.maxTracked);
			if (dropped.remove(e)) {
				return dropped.isEmpty() ? Diff.NONE : new Diff(List.of(), List.of(), dropped);
			}
			return new Diff(List.of(e), List.of(), dropped);
		}
		Entry merged = new Entry(e.uuid(), e.name(), e.itemId(), e.price(), e.resale(), e.netProfit(), e.profitPercent(),
				e.marketChecked(), e.listHours(), e.auctionEndMs(), old.detectedAtMs(),
				Math.max(old.verifiedLastUpdatedMs(), e.verifiedLastUpdatedMs()), false);
		entries.put(e.uuid(), merged);
		return merged.sameDisplay(old) ? Diff.NONE : new Diff(List.of(), List.of(merged), List.of());
	}

	/**
	 * Applies a processed snapshot. Expired auctions always leave; only a COMPLETE snapshot can show that an auction
	 * is no longer listed.
	 */
	synchronized Diff onSnapshot(long lastUpdatedMs, boolean complete, Predicate<UUID> listed, long nowMs) {
		List<Entry> removed = new ArrayList<>();
		List<Entry> changed = new ArrayList<>();
		for (Entry e : List.copyOf(entries.values())) {
			if (e.auctionEndMs() != null && e.auctionEndMs() <= nowMs) {
				entries.remove(e.uuid());
				removed.add(e);
			} else if (complete) {
				if (listed.test(e.uuid())) {
					Entry v = e.verifiedAt(lastUpdatedMs);
					entries.put(e.uuid(), v);
					if (e.restored()) {
						changed.add(v);
					}
				} else {
					entries.remove(e.uuid());
					removed.add(e);
				}
			}
		}
		return removed.isEmpty() && changed.isEmpty() ? Diff.NONE : new Diff(List.of(), changed, removed);
	}

	synchronized List<Entry> ranked() {
		return entries.values().stream().sorted(RANKING).toList();
	}

	synchronized int size() {
		return entries.size();
	}

	private List<Entry> trim(int max) {
		List<Entry> dropped = new ArrayList<>();
		if (entries.size() <= max) {
			return dropped;
		}
		List<Entry> sorted = entries.values().stream().sorted(RANKING).toList();
		for (Entry e : sorted.subList(max, sorted.size())) {
			entries.remove(e.uuid());
			dropped.add(e);
		}
		return dropped;
	}

	// ---- persistence (restart recovery) ------------------------------------------------------------------------

	synchronized void save(Path file) throws IOException {
		AtomicFiles.writeString(file, ConfigManager.GSON.toJson(ranked()));
	}

	/**
	 * Loads a saved board. Entries come back marked as restored (shown as not yet re-verified) until the first
	 * COMPLETE snapshot of this run confirms or removes them; already-ended auctions are dropped. Returns the number
	 * restored.
	 */
	synchronized int restore(Path file, long nowMs) throws IOException {
		if (!Files.exists(file)) {
			return 0;
		}
		List<Entry> saved;
		try {
			saved = ConfigManager.GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
					new TypeToken<List<Entry>>() {
					}.getType());
		} catch (JsonParseException e) {
			throw new IOException("board state is not valid JSON", e);
		}
		int n = 0;
		for (Entry e : saved == null ? List.<Entry>of() : saved) {
			if (e == null || e.uuid() == null || (e.auctionEndMs() != null && e.auctionEndMs() <= nowMs)) {
				continue;
			}
			entries.put(e.uuid(), e.asRestored());
			n++;
		}
		trim(config.get().maxTracked);
		return Math.min(n, entries.size());
	}
}
