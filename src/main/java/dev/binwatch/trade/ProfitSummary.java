package dev.binwatch.trade;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.binwatch.store.Records.Trade;
import dev.binwatch.store.Records.TradeStatus;
import dev.binwatch.util.TimeFmt;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Profit figures from the trade ledger, as text for the in-game command and as a Discord embed that is edited in
 * place. Realised profit counts sold or collected flips; open positions show the profit expected from their listing
 * advice, clearly marked as an estimate.
 */
public final class ProfitSummary {
	static final long HOUR = 3_600_000L;

	/**
	 * @param lastHour      realised profit in the last hour
	 * @param lastDay       realised profit in the last 24 hours
	 * @param overall       all realised profit
	 * @param perHour       overall / hours since the first tracked purchase (at least one hour)
	 * @param sold          number of realised flips
	 * @param avgHoldMs     average time from purchase to sale, null when nothing sold yet
	 * @param openCount     bought or listed, not sold yet
	 * @param openInvested  coins tied up in open positions
	 * @param openExpected  expected profit of open positions that have listing advice
	 */
	public record Stats(long lastHour, long lastDay, long overall, long perHour, int sold, long avgProfit, Long avgHoldMs,
			int openCount, long openInvested, long openExpected, List<Trade> top) {
	}

	private ProfitSummary() {
	}

	static boolean realised(Trade t) {
		return (t.status() == TradeStatus.SOLD || t.status() == TradeStatus.COLLECTED) && t.profit() != null
				&& t.boughtPrice() != null;
	}

	static boolean open(Trade t) {
		return t.status() == TradeStatus.BOUGHT || t.status() == TradeStatus.LISTED;
	}

	static long realisedAt(Trade t) {
		return t.soldMs() != null ? t.soldMs() : t.collectedMs() != null ? t.collectedMs() : t.boughtMs();
	}

	/** Profit used for ranking: realised when sold, otherwise the expected profit (0 when unknown). */
	static long rankProfit(Trade t) {
		return realised(t) ? t.profit() : t.expectedNet() != null ? t.expectedNet() : 0;
	}

	public static Stats compute(List<Trade> trades, long nowMs, int topCount) {
		long hour = 0, day = 0, overall = 0, holdSum = 0, invested = 0, expected = 0;
		int sold = 0, holdCount = 0, openCount = 0;
		Long firstBuy = null;
		for (Trade t : trades) {
			if (t.boughtMs() != null && (firstBuy == null || t.boughtMs() < firstBuy)) {
				firstBuy = t.boughtMs();
			}
			if (realised(t)) {
				long at = realisedAt(t);
				overall += t.profit();
				sold++;
				if (nowMs - at <= HOUR) {
					hour += t.profit();
				}
				if (nowMs - at <= 24 * HOUR) {
					day += t.profit();
				}
				if (t.soldMs() != null && t.boughtMs() != null && t.soldMs() >= t.boughtMs()) {
					holdSum += t.soldMs() - t.boughtMs();
					holdCount++;
				}
			} else if (open(t) && t.boughtPrice() != null) {
				openCount++;
				invested += t.boughtPrice();
				if (t.expectedNet() != null) {
					expected += t.expectedNet();
				}
			}
		}
		double hours = firstBuy == null ? 1 : Math.max(1.0, (nowMs - firstBuy) / (double) HOUR);
		List<Trade> top = new ArrayList<>(trades.stream().filter(t -> realised(t) || (open(t) && t.boughtPrice() != null))
				.sorted(Comparator.comparingLong(ProfitSummary::rankProfit).reversed()).limit(Math.max(0, topCount))
				.toList());
		return new Stats(hour, day, overall, Math.round(overall / hours), sold, sold == 0 ? 0 : overall / sold,
				holdCount == 0 ? null : holdSum / holdCount, openCount, invested, expected, top);
	}

	/** Lines for {@code /binwatch profit}. */
	public static List<String> text(Stats s) {
		List<String> out = new ArrayList<>();
		out.add("Profit: last hour " + signed(s.lastHour()) + ", last 24h " + signed(s.lastDay()) + ", overall "
				+ signed(s.overall()) + " (" + signed(s.perHour()) + "/h)");
		out.add(s.sold() + " flip(s) sold, average " + signed(s.avgProfit())
				+ (s.avgHoldMs() == null ? "" : ", held " + TimeFmt.duration(s.avgHoldMs()) + " on average"));
		out.add("Open: " + s.openCount() + " item(s), " + TimeFmt.shortCoins(s.openInvested()) + " invested, est. "
				+ signed(s.openExpected()));
		int i = 1;
		for (Trade t : s.top()) {
			out.add(i++ + ". " + topLine(t, false));
		}
		return out;
	}

	/** Discord payload. The embed timestamp is the only part that changes when nothing else did. */
	public static JsonObject payload(Stats s, long nowMs, String attribution) {
		JsonObject embed = new JsonObject();
		embed.addProperty("title", "BIN Watch profit");
		embed.addProperty("color", s.overall() >= 0 ? 0x2ECC71 : 0xE74C3C);
		embed.addProperty("description", "Tracked from your own Auction House chat messages. Updates live.");
		JsonArray fields = new JsonArray();
		field(fields, "Last hour", signed(s.lastHour()), true);
		field(fields, "Last 24h", signed(s.lastDay()), true);
		field(fields, "Overall", signed(s.overall()) + "\n" + signed(s.perHour()) + " per hour", true);
		field(fields, "Sold", s.sold() + " flip(s)\navg " + signed(s.avgProfit())
				+ (s.avgHoldMs() == null ? "" : "\nheld ~" + TimeFmt.duration(s.avgHoldMs())), true);
		field(fields, "Open", s.openCount() + " item(s)\n" + TimeFmt.shortCoins(s.openInvested()) + " invested\nest. "
				+ signed(s.openExpected()), true);
		List<String> lines = new ArrayList<>();
		int i = 1;
		for (Trade t : s.top()) {
			lines.add(i++ + ". " + topLine(t, true));
		}
		String top = lines.isEmpty() ? "No flips tracked yet. Buy one from an alert and it shows up here." : String.join("\n", lines);
		while (top.length() > 1024 && lines.size() > 1) {
			lines.remove(lines.size() - 1);
			top = String.join("\n", lines);
		}
		field(fields, "Top flips (sold ✅ / open ⏳ = expected)", top.length() > 1024 ? top.substring(0, 1021) + "..." : top, false);
		embed.add("fields", fields);
		JsonObject footer = new JsonObject();
		footer.addProperty("text", "Updated" + (attribution == null ? "" : " · " + attribution));
		embed.add("footer", footer);
		embed.addProperty("timestamp", Instant.ofEpochMilli(nowMs).toString());
		JsonObject payload = new JsonObject();
		payload.addProperty("username", "BIN Watch");
		JsonArray embeds = new JsonArray();
		embeds.add(embed);
		payload.add("embeds", embeds);
		JsonObject allowed = new JsonObject();
		allowed.add("parse", new JsonArray());
		payload.add("allowed_mentions", allowed);
		return payload;
	}

	static String topLine(Trade t, boolean markdown) {
		String name = t.itemName() == null ? "Unknown item" : t.itemName();
		if (markdown) {
			name = name.replaceAll("([*_`~|>\\\\])", "\\\\$1");
		}
		if (realised(t)) {
			return (markdown ? "✅ **" + name + "** " : name + " ") + signed(t.profit()) + " (bought "
					+ TimeFmt.shortCoins(t.boughtPrice()) + ", sold " + TimeFmt.shortCoins(t.soldPrice() != null
					? t.soldPrice() : t.collectedCoins() != null ? t.collectedCoins() : 0) + ")";
		}
		String state = t.status() == TradeStatus.LISTED ? "listed" : "bought";
		String exp = t.expectedNet() == null ? "no estimate" : "est. " + signed(t.expectedNet());
		return (markdown ? "⏳ " + name + " " : name + " ") + exp + " (" + state + " " + TimeFmt.shortCoins(t.boughtPrice())
				+ (t.expectedListPrice() == null ? "" : ", list " + TimeFmt.shortCoins(t.expectedListPrice())) + ")";
	}

	static String signed(long coins) {
		return TradeLedger.signed(coins);
	}

	private static void field(JsonArray fields, String name, String value, boolean inline) {
		JsonObject f = new JsonObject();
		f.addProperty("name", name);
		f.addProperty("value", value);
		f.addProperty("inline", inline);
		fields.add(f);
	}
}
