package dev.binwatch.standalone;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.binwatch.util.TimeFmt;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** The profit tracker's Discord messages: one live summary, and one short line per flip once its result is known. */
final class ProfitEmbed {
	static final String TITLE = "Profit tracker";

	private ProfitEmbed() {
	}

	static JsonObject summary(ProfitTracker.Stats s, long nowMs, boolean stopped, String attribution) {
		JsonObject embed = new JsonObject();
		embed.addProperty("title", TITLE);
		StringBuilder d = new StringBuilder("Flips from the AH Flipper board that were still unsold when the alert reached "
				+ "Discord: the profit you could have made, and how long each stayed buyable.");
		d.append("\n**").append(s.pending()).append("** waiting for a result");
		if (stopped) {
			d.append(" · ⏹️ tracker stopped");
		}
		embed.addProperty("description", d.toString());
		embed.addProperty("color", 0x2ECC71);
		JsonArray fields = new JsonArray();
		fields.add(field("Last hour", window(s.lastHour()), true));
		fields.add(field("Last 24 h", window(s.lastDay()), true));
		fields.add(field("All time", window(s.allTime()), true));
		fields.add(field("How fast they sold (24 h)", speed(s.soldAfterAlert()), false));
		if (!s.top().isEmpty()) {
			StringBuilder t = new StringBuilder();
			for (ProfitTracker.Flip f : s.top()) {
				String line = "✅ **" + (f.name() == null ? "Unknown item" : f.name()) + "** +" + BoardEmbed.coins(f.netProfit())
						+ " (" + String.format(Locale.ROOT, "%.1f%%", f.profitPercent()) + ") · " + shortNote(f) + "\n";
				if (t.length() + line.length() > 1000) {
					break;
				}
				t.append(line);
			}
			fields.add(field("Best flips you could have bought (24 h)", t.toString().trim(), false));
		}
		embed.add("fields", fields);
		JsonObject footer = new JsonObject();
		footer.addProperty("text", "Potential profit = estimated profit after fees, not money made"
				+ (attribution == null ? "" : " · " + attribution));
		embed.add("footer", footer);
		embed.addProperty("timestamp", TimeFmt.iso(nowMs));
		return wrap(embed);
	}

	/** One flip's result, posted once. */
	static JsonObject result(ProfitTracker.Flip f) {
		JsonObject embed = new JsonObject();
		String name = f.name() == null ? "Unknown item" : f.name();
		String head = switch (f.status()) {
			case BUYABLE -> "✅ Could have bought: ";
			case SNIPED -> "❌ Sniped before the alert: ";
			default -> "❔ No result: ";
		};
		embed.addProperty("title", clip(head + name, 256));
		embed.addProperty("description", "Profit **" + (f.netProfit() >= 0 ? "+" : "") + BoardEmbed.coins(f.netProfit())
				+ "** after fees (" + String.format(Locale.ROOT, "%.1f%%", f.profitPercent()) + ") · buy "
				+ BoardEmbed.coins(f.price()) + (f.marketChecked() ? " · SkyCofl checked" : "") + "\n"
				+ capitalize(f.note()) + "\nAlerted <t:" + Math.floorDiv(f.alertedAtMs(), 1000) + ":R>"
				+ "\n`/viewauction " + f.uuid().toString().replace("-", "") + "`");
		embed.addProperty("color", switch (f.status()) {
			case BUYABLE -> 0x2ECC71;
			case SNIPED -> 0xE74C3C;
			default -> 0x95A5A6;
		});
		embed.addProperty("timestamp", TimeFmt.iso(f.resolvedAtMs()));
		return wrap(embed);
	}

	static String window(ProfitTracker.Window w) {
		return "**+" + BoardEmbed.coins(w.profit()) + "**\n" + w.buyable() + " buyable · " + w.sniped() + " sniped"
				+ (w.unknown() > 0 ? " · " + w.unknown() + " unknown" : "");
	}

	static String speed(List<ProfitTracker.Flip> sold) {
		if (sold.isEmpty()) {
			return "No buyable flip has sold yet.";
		}
		long[] after = sold.stream().mapToLong(ProfitTracker.Flip::soldAfterMs).sorted().toArray();
		long median = after[after.length / 2];
		long within5 = Arrays.stream(after).filter(a -> a < 5_000).count();
		long within30 = Arrays.stream(after).filter(a -> a < 30_000).count();
		return "Median **" + ProfitTracker.secs(median) + "** after the alert · fastest " + ProfitTracker.secs(after[0])
				+ "\n" + within5 + " of " + after.length + " sold within 5 s, " + within30 + " within 30 s";
	}

	private static String shortNote(ProfitTracker.Flip f) {
		return f.soldAfterMs() != null ? "sold " + ProfitTracker.secs(f.soldAfterMs()) + " after" : f.note();
	}

	private static JsonObject field(String name, String value, boolean inline) {
		JsonObject f = new JsonObject();
		f.addProperty("name", name);
		f.addProperty("value", clip(value, 1024));
		f.addProperty("inline", inline);
		return f;
	}

	private static JsonObject wrap(JsonObject embed) {
		JsonObject payload = new JsonObject();
		payload.addProperty("username", "AH Flipper");
		JsonArray embeds = new JsonArray();
		embeds.add(embed);
		payload.add("embeds", embeds);
		JsonObject mentions = new JsonObject();
		mentions.add("parse", new JsonArray());
		payload.add("allowed_mentions", mentions);
		return payload;
	}

	private static String capitalize(String s) {
		return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private static String clip(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max - 1) + "…";
	}
}
