package dev.binwatch.standalone;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.binwatch.util.TimeFmt;

import java.util.List;
import java.util.Locale;

/**
 * Builds the "AH Flipper" Discord message. Times use Discord's {@code <t:…:R>} markup, which every viewer's client
 * renders as a live "x minutes ago", so ages stay correct without editing the message. No web link is invented for an
 * auction: the only reliable way to open one is the in-game {@code /viewauction <uuid>} command, which is shown.
 */
final class BoardEmbed {
	static final String TITLE = "AH Flipper";
	/** Discord's limits: 25 fields, 256 characters per field name, 1024 per value, 6000 for the whole embed. */
	static final int MAX_FIELDS = 25;
	private static final int EMBED_BUDGET = 5800;

	enum State {
		STARTING, SCANNING, DEGRADED, STOPPED
	}

	/**
	 * @param snapshotLastUpdatedMs Hypixel's lastUpdated of the newest processed snapshot (null before the first)
	 * @param snapshotStatus        COMPLETE, INCONSISTENT or INCOMPLETE
	 * @param problem               short reason shown when the state is DEGRADED
	 */
	record Status(State state, Long snapshotLastUpdatedMs, String snapshotStatus, String problem, long nowMs,
			String attribution) {
	}

	private BoardEmbed() {
	}

	static JsonObject payload(List<FlipBoard.Entry> ranked, int maxFlips, Status st) {
		JsonObject embed = new JsonObject();
		embed.addProperty("title", TITLE);
		int shown = Math.min(Math.min(maxFlips, MAX_FIELDS), ranked.size());
		StringBuilder d = new StringBuilder();
		d.append(switch (st.state()) {
			case STARTING -> "🟡 **Starting** · waiting for the first auction snapshot";
			case SCANNING -> "🟢 **Scanning**";
			case DEGRADED -> "🟠 **Data problem** · " + (st.problem() == null ? "see the console" : st.problem());
			case STOPPED -> "⏹️ **Stopped** · the list below is no longer being checked";
		});
		if (st.snapshotLastUpdatedMs() != null) {
			d.append("\nLatest snapshot ").append(rel(st.snapshotLastUpdatedMs()));
			if (st.snapshotStatus() != null && !"COMPLETE".equals(st.snapshotStatus())) {
				d.append(" (").append(st.snapshotStatus().toLowerCase(Locale.ROOT)).append(")");
			}
		}
		d.append("\n**").append(ranked.size()).append("** opportunit").append(ranked.size() == 1 ? "y" : "ies")
				.append(" tracked");
		if (ranked.size() > shown) {
			d.append(" · top ").append(shown).append(" shown");
		}
		if (ranked.isEmpty()) {
			d.append("\n\n").append(st.state() == State.STOPPED ? "No opportunities were open when it stopped."
					: "No opportunities right now. New listings are checked as each snapshot arrives (about once a minute).");
		}
		embed.addProperty("description", d.toString());
		embed.addProperty("color", switch (st.state()) {
			case STARTING -> 0xF1C40F;
			case SCANNING -> ranked.isEmpty() ? 0x95A5A6 : 0x2ECC71;
			case DEGRADED -> 0xE67E22;
			case STOPPED -> 0x7F8C8D;
		});
		JsonArray fields = new JsonArray();
		int used = TITLE.length() + d.length();
		for (int i = 0; i < shown; i++) {
			FlipBoard.Entry e = ranked.get(i);
			String name = clip((i + 1) + ". " + (e.name() == null || e.name().isBlank() ? "Unknown item" : e.name()), 256);
			String value = clip(fieldValue(e), 1024);
			if (used + name.length() + value.length() > EMBED_BUDGET) {
				break;
			}
			used += name.length() + value.length();
			JsonObject f = new JsonObject();
			f.addProperty("name", name);
			f.addProperty("value", value);
			f.addProperty("inline", false);
			fields.add(f);
		}
		embed.add("fields", fields);
		JsonObject footer = new JsonObject();
		footer.addProperty("text", clip("Estimates only, not guaranteed · read-only, never buys"
				+ (st.attribution() == null ? "" : " · " + st.attribution()), 2048));
		embed.add("footer", footer);
		embed.addProperty("timestamp", TimeFmt.iso(st.nowMs()));
		JsonObject payload = new JsonObject();
		payload.addProperty("username", "AH Flipper");
		JsonArray embeds = new JsonArray();
		embeds.add(embed);
		payload.add("embeds", embeds);
		// The message never pings anyone.
		JsonObject mentions = new JsonObject();
		mentions.add("parse", new JsonArray());
		payload.add("allowed_mentions", mentions);
		return payload;
	}

	static String fieldValue(FlipBoard.Entry e) {
		StringBuilder v = new StringBuilder();
		v.append("Buy **").append(coins(e.price())).append("** → resale **").append(coins(e.resale())).append("**\n");
		v.append("Profit **").append(e.netProfit() >= 0 ? "+" : "").append(coins(e.netProfit())).append("** after fees (")
				.append(String.format(Locale.ROOT, "%.1f%%", e.profitPercent())).append(")");
		v.append(e.marketChecked() ? " · ✅ SkyCofl" + (e.listHours() == null ? "" : ", list " + e.listHours() + "h")
				: " · local estimate");
		v.append("\nSeen ").append(rel(e.detectedAtMs()));
		if (e.auctionEndMs() != null) {
			v.append(" · ends ").append(rel(e.auctionEndMs()));
		}
		if (e.restored()) {
			v.append("\n⚠️ From before a restart, not re-checked yet");
		}
		v.append("\n`/viewauction ").append(e.uuid().toString().replace("-", "")).append("`");
		return v.toString();
	}

	/** The message without its timestamp, to tell whether an edit would change anything visible. */
	static String contentKey(JsonObject payload) {
		JsonObject copy = payload.deepCopy();
		copy.getAsJsonArray("embeds").get(0).getAsJsonObject().remove("timestamp");
		return copy.toString();
	}

	/** Compact coins: 850, 12.5K, 850K, 12.5M, 520M, 1.25B. */
	static String coins(long coins) {
		long abs = Math.abs(coins);
		String sign = coins < 0 ? "-" : "";
		String s;
		if (abs >= 1_000_000_000L) {
			s = trim(String.format(Locale.ROOT, "%.2f", abs / 1e9)) + "B";
		} else if (abs >= 1_000_000L) {
			s = scaled(abs / 1e6) + "M";
		} else if (abs >= 1_000L) {
			s = scaled(abs / 1e3) + "K";
		} else {
			s = Long.toString(abs);
		}
		return sign + s;
	}

	private static String scaled(double v) {
		// One decimal below 100 (12.5M), none above (520M). 999.95K rounds to "1000K", which is still readable.
		return v < 99.95 ? trim(String.format(Locale.ROOT, "%.1f", v)) : String.format(Locale.ROOT, "%.0f", v);
	}

	private static String trim(String s) {
		return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
	}

	private static String rel(long epochMs) {
		return "<t:" + Math.floorDiv(epochMs, 1000) + ":R>";
	}

	private static String clip(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max - 1) + "…";
	}
}
