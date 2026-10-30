package dev.binwatch.standalone;

import com.google.gson.JsonParseException;
import dev.binwatch.api.EndedAuction;
import dev.binwatch.config.BinWatchConfig;
import dev.binwatch.config.ConfigManager;
import dev.binwatch.store.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Follows every flip that was sent to Discord and finds out whether it was still unsold when Discord received the
 * alert, using the sale times Hypixel publishes in {@code auctions_ended} (each sale appears there within about a
 * minute). A flip is BUYABLE when it sold after the alert, or was still listed in a snapshot taken after the alert; its
 * estimated profit then counts as profit that could have been made. It is SNIPED when it sold before the alert reached
 * Discord. A flip that disappears without a recorded sale and was never seen listed after the alert is UNKNOWN.
 *
 * <p>Times: the alert time is when Discord confirmed the message, converted to Hypixel's clock with the collector's
 * offset estimate, so it can be compared with Hypixel's sale timestamps.
 */
final class ProfitTracker {
	enum Status {
		PENDING, BUYABLE, SNIPED, UNKNOWN
	}

	/**
	 * @param alertedAtMs     local time Discord confirmed the alert
	 * @param alertedServerMs the same moment on Hypixel's clock
	 * @param soldAfterMs     sale time minus alert time (negative: sold before the alert); null when no sale was seen
	 * @param lastListedMs    lastUpdated of the newest complete snapshot that still listed it (Hypixel's clock), or 0
	 * @param goneSinceMs     local time it was first missing from a complete snapshot, or null
	 * @param note            how the result was decided, shown in Discord
	 */
	record Flip(UUID uuid, String name, long price, long netProfit, double profitPercent, boolean marketChecked,
			long alertedAtMs, long alertedServerMs, Long auctionEndMs, Status status, Long soldAfterMs, long lastListedMs,
			Long goneSinceMs, long resolvedAtMs, String note) {

		Flip listedAt(long lastUpdatedMs) {
			return new Flip(uuid, name, price, netProfit, profitPercent, marketChecked, alertedAtMs, alertedServerMs,
					auctionEndMs, status, soldAfterMs, Math.max(lastListedMs, lastUpdatedMs), null, resolvedAtMs, note);
		}

		Flip missingSince(long nowMs) {
			return new Flip(uuid, name, price, netProfit, profitPercent, marketChecked, alertedAtMs, alertedServerMs,
					auctionEndMs, status, soldAfterMs, lastListedMs, goneSinceMs == null ? nowMs : goneSinceMs, resolvedAtMs, note);
		}

		Flip resolve(Status st, Long soldAfter, long nowMs, String why) {
			return new Flip(uuid, name, price, netProfit, profitPercent, marketChecked, alertedAtMs, alertedServerMs,
					auctionEndMs, st, soldAfter, lastListedMs, goneSinceMs, nowMs, why);
		}
	}

	/** Counted once per resolved flip and kept forever (old flips are pruned from the list, not from these). */
	static final class Totals {
		long buyable;
		long sniped;
		long unknown;
		long profit;
	}

	record Window(long buyable, long sniped, long unknown, long profit) {
	}

	record Stats(Window lastHour, Window lastDay, Window allTime, int pending, List<Flip> soldAfterAlert,
			List<Flip> top) {
	}

	/** Wait this long after an auction vanished for its sale to show up in auctions_ended before giving up on it. */
	static final long GONE_GRACE_MS = 3 * 60_000L;
	private static final long RECENT_SALES_MS = 15 * 60_000L;

	private final Supplier<BinWatchConfig.Profit> config;
	private final Map<UUID, Flip> flips = new LinkedHashMap<>();
	/** Sales seen recently, in case a sale is reported before its alert is confirmed. Value: sale time (Hypixel). */
	private final Map<UUID, Long> recentSales = new LinkedHashMap<>();
	private Totals totals = new Totals();

	ProfitTracker(Supplier<BinWatchConfig.Profit> config) {
		this.config = config;
	}

	/** The flips shown in a board message Discord just confirmed. Each auction is followed from its first alert. */
	synchronized List<Flip> onAlerted(List<FlipBoard.Entry> shown, long ackLocalMs, long ackServerMs) {
		List<Flip> resolved = new ArrayList<>();
		for (FlipBoard.Entry e : shown) {
			if (flips.containsKey(e.uuid())) {
				continue;
			}
			Flip f = new Flip(e.uuid(), e.name(), e.price(), e.netProfit(), e.profitPercent(), e.marketChecked(), ackLocalMs,
					ackServerMs, e.auctionEndMs(), Status.PENDING, null, 0, null, 0, null);
			Long soldAt = recentSales.get(e.uuid());
			if (soldAt != null) {
				f = sold(f, soldAt, ackLocalMs);
				resolved.add(f);
			}
			flips.put(e.uuid(), f);
		}
		return resolved;
	}

	/** New sales from auctions_ended. Returns the followed flips this resolved. */
	synchronized List<Flip> onSold(List<EndedAuction> sales, long nowMs) {
		List<Flip> resolved = new ArrayList<>();
		for (EndedAuction s : sales) {
			recentSales.put(s.auctionId(), s.timestampMs());
			Flip f = flips.get(s.auctionId());
			if (f != null && f.status() == Status.PENDING) {
				f = sold(f, s.timestampMs(), nowMs);
				flips.put(f.uuid(), f);
				resolved.add(f);
			}
		}
		recentSales.values().removeIf(t -> t < nowMs - RECENT_SALES_MS && t > 0);
		while (recentSales.size() > 20_000) {
			recentSales.remove(recentSales.keySet().iterator().next());
		}
		return resolved;
	}

	private Flip sold(Flip f, long saleServerMs, long nowMs) {
		long after = saleServerMs - f.alertedServerMs();
		Flip r = after >= 0 ? f.resolve(Status.BUYABLE, after, nowMs, "sold " + secs(after) + " after the alert")
				: f.resolve(Status.SNIPED, after, nowMs, "sold " + secs(-after) + " before the alert reached Discord");
		count(r);
		return r;
	}

	/**
	 * A processed snapshot. Complete snapshots show which followed auctions are still listed; ones that vanish without
	 * a sale are decided after {@link #GONE_GRACE_MS}, and ones still listed after {@code maxFollowMinutes} count as
	 * buyable. Returns the flips resolved now.
	 */
	synchronized List<Flip> onSnapshot(long lastUpdatedMs, boolean complete, Predicate<UUID> listed, long nowMs) {
		BinWatchConfig.Profit cfg = config.get();
		List<Flip> resolved = new ArrayList<>();
		for (Flip f : List.copyOf(flips.values())) {
			if (f.status() != Status.PENDING) {
				continue;
			}
			Flip u = f;
			boolean ended = u.auctionEndMs() != null && u.auctionEndMs() <= nowMs;
			if (complete && listed.test(u.uuid()) && !ended) {
				u = u.listedAt(lastUpdatedMs);
			} else if (complete || ended) {
				u = u.missingSince(nowMs);
			}
			if (u.goneSinceMs() != null && nowMs - u.goneSinceMs() >= GONE_GRACE_MS) {
				u = u.lastListedMs() >= u.alertedServerMs()
						? u.resolve(Status.BUYABLE, null, nowMs, "gone without a recorded sale (probably cancelled); still listed "
								+ secs(u.lastListedMs() - u.alertedServerMs()) + " after the alert")
						: u.resolve(Status.UNKNOWN, null, nowMs, "gone without a recorded sale, not seen listed after the alert");
				count(u);
				resolved.add(u);
			} else if (u.goneSinceMs() == null && u.lastListedMs() >= u.alertedServerMs()
					&& nowMs - u.alertedAtMs() >= cfg.maxFollowMinutes * 60_000L) {
				u = u.resolve(Status.BUYABLE, null, nowMs, "still unsold " + cfg.maxFollowMinutes + " min after the alert");
				count(u);
				resolved.add(u);
			}
			flips.put(u.uuid(), u);
		}
		long keepFrom = nowMs - cfg.keepDays * 86_400_000L;
		flips.values().removeIf(f -> f.status() != Status.PENDING && f.alertedAtMs() < keepFrom);
		return resolved;
	}

	private void count(Flip f) {
		switch (f.status()) {
			case BUYABLE -> {
				totals.buyable++;
				totals.profit += f.netProfit();
			}
			case SNIPED -> totals.sniped++;
			case UNKNOWN -> totals.unknown++;
			case PENDING -> {
			}
		}
	}

	synchronized Stats stats(long nowMs, int topCount) {
		int pending = 0;
		List<Flip> day = new ArrayList<>();
		for (Flip f : flips.values()) {
			if (f.status() == Status.PENDING) {
				pending++;
			} else if (f.alertedAtMs() >= nowMs - 86_400_000L) {
				day.add(f);
			}
		}
		List<Flip> hour = day.stream().filter(f -> f.alertedAtMs() >= nowMs - 3_600_000L).toList();
		List<Flip> soldAfter = day.stream().filter(f -> f.status() == Status.BUYABLE && f.soldAfterMs() != null).toList();
		List<Flip> top = day.stream().filter(f -> f.status() == Status.BUYABLE)
				.sorted(Comparator.comparingLong(Flip::netProfit).reversed().thenComparing(Flip::alertedAtMs))
				.limit(topCount).toList();
		return new Stats(window(hour), window(day), new Window(totals.buyable, totals.sniped, totals.unknown, totals.profit),
				pending, soldAfter, top);
	}

	private static Window window(List<Flip> fs) {
		long b = 0;
		long s = 0;
		long u = 0;
		long p = 0;
		for (Flip f : fs) {
			switch (f.status()) {
				case BUYABLE -> {
					b++;
					p += f.netProfit();
				}
				case SNIPED -> s++;
				case UNKNOWN -> u++;
				case PENDING -> {
				}
			}
		}
		return new Window(b, s, u, p);
	}

	synchronized Flip get(UUID uuid) {
		return flips.get(uuid);
	}

	// ---- persistence -------------------------------------------------------------------------------------------

	private record Saved(Totals totals, List<Flip> flips) {
	}

	synchronized void save(Path file) throws IOException {
		AtomicFiles.writeString(file, ConfigManager.GSON.toJson(new Saved(totals, List.copyOf(flips.values()))));
	}

	/** Loads the saved state; pending flips keep being followed. Returns the number of flips loaded. */
	synchronized int restore(Path file) throws IOException {
		if (!Files.exists(file)) {
			return 0;
		}
		Saved s;
		try {
			s = ConfigManager.GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Saved.class);
		} catch (JsonParseException e) {
			throw new IOException("profit state is not valid JSON", e);
		}
		if (s == null) {
			return 0;
		}
		totals = s.totals() == null ? new Totals() : s.totals();
		int n = 0;
		for (Flip f : s.flips() == null ? List.<Flip>of() : s.flips()) {
			if (f != null && f.uuid() != null && f.status() != null) {
				flips.put(f.uuid(), f);
				n++;
			}
		}
		return n;
	}

	/** 0.8 s, 14.2 s, 3m 05s, 1h 02m. */
	static String secs(long ms) {
		long abs = Math.abs(ms);
		if (abs < 60_000) {
			return String.format(java.util.Locale.ROOT, "%.1f s", abs / 1000.0);
		}
		long s = abs / 1000;
		return s < 3600 ? String.format(java.util.Locale.ROOT, "%dm %02ds", s / 60, s % 60)
				: String.format(java.util.Locale.ROOT, "%dh %02dm", s / 3600, (s % 3600) / 60);
	}
}
