package dev.binwatch.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.binwatch.api.AuctionCollector;
import dev.binwatch.config.BinWatchConfig;
import dev.binwatch.item.ItemDetails;
import dev.binwatch.item.NormalizedItem;
import dev.binwatch.market.MarketCheck;
import dev.binwatch.market.MarketQuote;
import dev.binwatch.store.Records;
import dev.binwatch.util.TimeFmt;
import dev.binwatch.util.Timing;
import dev.binwatch.value.FlipCandidate;
import dev.binwatch.value.Valuation;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Builds the Discord webhook payload for a flip alert. Respects Discord limits (title 256, field name 256, field
 * value 1024, 25 fields, 6000 characters total). No URL is ever constructed for an auction: the only "link" given is
 * the in-game {@code /viewauction} command, which uses the real auction UUID. The only URL in an alert is the
 * optional item icon, built from the configured template and the SkyBlock item id.
 */
public final class FlipEmbed {
	public static final String TITLE = "Potential BIN Flip";
	static final String DISCLAIMER = "Estimate only; resale and profit are not guaranteed";
	static final int MAX_FIELD_VALUE = 1024;
	static final int MAX_TOTAL = 6000;
	private static final Pattern SAFE_ID = Pattern.compile("[A-Z0-9_:;\\-]{1,80}");
	private static final Map<String, Integer> RARITY_COLORS = Map.ofEntries(
			Map.entry("COMMON", 0xFFFFFF), Map.entry("UNCOMMON", 0x55FF55), Map.entry("RARE", 0x5555FF),
			Map.entry("EPIC", 0xAA00AA), Map.entry("LEGENDARY", 0xFFAA00), Map.entry("MYTHIC", 0xFF55FF),
			Map.entry("DIVINE", 0x55FFFF), Map.entry("SPECIAL", 0xFF5555), Map.entry("VERY_SPECIAL", 0xFF5555),
			Map.entry("ULTIMATE", 0xAA0000), Map.entry("ADMIN", 0xAA0000));

	/**
	 * Presentation options.
	 *
	 * @param imageUrlTemplate HTTPS template containing {id}, or null for no item icon
	 */
	public record Style(int color, boolean colorByRarity, String imageUrlTemplate) {
		public static Style of(BinWatchConfig.Discord d) {
			return new Style(d.embedColor, d.colorByRarity, d.itemImages ? d.itemImageUrl : null);
		}
	}

	private FlipEmbed() {
	}

	public static JsonObject build(FlipCandidate c, int color, long nowMs, long skewToleranceMs) {
		return build(c, new Style(color, false, null), nowMs, skewToleranceMs);
	}

	public static JsonObject build(FlipCandidate c, Style style, long nowMs, long skewToleranceMs) {
		return build(c, style, nowMs, skewToleranceMs, 0);
	}

	/**
	 * @param nowMs          current time, used to validate timestamps
	 * @param serverOffsetMs estimated Hypixel server time minus local time, to compare the local receive time with the
	 *                       snapshot's server-side lastUpdated
	 */
	public static JsonObject build(FlipCandidate c, Style style, long nowMs, long skewToleranceMs, long serverOffsetMs) {
		return build(c, style, nowMs, skewToleranceMs, serverOffsetMs, null);
	}

	/** @param publish when Hypixel published this auction's snapshot, if known; the latency line is then measured from it */
	public static JsonObject build(FlipCandidate c, Style style, long nowMs, long skewToleranceMs, long serverOffsetMs,
			AuctionCollector.PublishEstimate publish) {
		NormalizedItem item = c.item();
		ItemDetails details = item == null || item.details() == null ? ItemDetails.NONE : item.details();

		JsonObject embed = new JsonObject();
		embed.addProperty("title", c.synthetic() ? "[TEST DATA] " + TITLE : TITLE);
		embed.addProperty("color", accentColor(style, item) & 0xFFFFFF);
		embed.addProperty("description", limit(description(c, item), 4096));
		embed.addProperty("timestamp", Instant.ofEpochMilli(nowMs).toString());
		String image = imageUrl(style, imageId(c));
		if (image != null) {
			JsonObject thumb = new JsonObject();
			thumb.addProperty("url", image);
			embed.add("thumbnail", thumb);
		}
		JsonObject footer = new JsonObject();
		footer.addProperty("text", "Suggested price is an estimate, not guaranteed · BIN Watch"
				+ (c.market() != null && c.market().attribution() != null ? " · " + c.market().attribution() : ""));
		embed.add("footer", footer);

		JsonArray fields = new JsonArray();
		// An ansi code block is the only way to get colored text in an embed: bold green on desktop.
		long net = c.effectiveNetProfit();
		add(fields, "Profit", "```ansi\n\u001b[1;" + (net >= 0 ? "32" : "31") + "m" + (net >= 0 ? "+" : "") + coins(net)
				+ " coins  (" + String.format(Locale.ROOT, "%.1f%%", c.discountPercent()) + ")\u001b[0m\n```", false);
		add(fields, "AH price", coins(c.price()), true);
		add(fields, "Suggested price", coins(c.effectiveResale()), true);
		if (c.market() != null) {
			add(fields, "Market (" + c.market().source() + ")", marketLines(c.market()), false);
		}

		if (item != null && !item.enchantments().isEmpty()) {
			List<String> names = ItemDetails.enchantmentNames(item.enchantments());
			List<String> shown = new ArrayList<>();
			for (String n : names) {
				shown.add(n.startsWith("Ultimate ") ? "**" + n + "**" : n);
			}
			add(fields, "Enchantments", String.join(" · ", shown), false);
		}
		String upgrades = upgrades(item, details);
		if (!upgrades.isEmpty()) {
			add(fields, details.pet() != null ? "Pet" : "Upgrades", upgrades, false);
		} else if (item == null && c.attributes() != null && !c.attributes().isBlank()) {
			add(fields, "Attributes", c.attributes(), false);
		}

		add(fields, "Found on API",
				Timing.apiDelayLabel(Timing.delay(c.auctionStartMs(), c.firstApiObservedMs(), nowMs, skewToleranceMs))
						+ (publish != null ? publishLine(publish, nowMs) : snapshotLagLine(c, serverOffsetMs, skewToleranceMs)),
				false);
		add(fields, "Open in game", c.synthetic()
				? "Test data: no real auction to open"
				: "```/viewauction " + c.auctionUuid().toString().replace("-", "") + "```", false);
		embed.add("fields", fields);

		trimToTotal(embed);
		return wrap(embed);
	}

	/** Market data, listing advice and the worth-it verdict. */
	static String marketLines(MarketCheck m) {
		if (!m.available()) {
			return "No market data (" + m.error() + "); profit above is the local estimate only";
		}
		MarketQuote q = m.quote();
		List<String> lines = new ArrayList<>();
		lines.add("Median " + coins(q.median()) + " · lowest BIN " + (q.lowestBin() > 0 ? coins(q.lowestBin()) : "n/a")
				+ " · ~" + String.format(Locale.ROOT, q.dailySales() >= 10 ? "%.0f" : "%.1f", q.dailySales()) + " sales/day");
		lines.add("**List at " + coins(m.listPrice()) + " for " + m.listHours() + "h** -> est. "
				+ (m.netProfit() >= 0 ? "+" : "") + coins(m.netProfit()) + " after fees"
				+ (m.sellHours() == null ? "" : " (sells in ~" + TimeFmt.duration((long) (m.sellHours() * 3_600_000)) + ")"));
		if (!m.worthIt()) {
			lines.add("Not worth it: " + String.join("; ", m.reasons()));
		}
		return String.join("\n", lines);
	}

	/** Icon id for a candidate: pets use PET_<TYPE>, everything else its SkyBlock id. */
	public static String imageId(FlipCandidate c) {
		ItemDetails d = c.item() == null ? null : c.item().details();
		return d != null && d.imageId() != null ? d.imageId() : c.itemId();
	}

	/** A clearly marked sample alert so the layout can be checked without waiting for a real flip. */
	/**
	 * How long after the snapshot's lastUpdated stamp the page holding this auction was received. Hypixel itself
	 * publishes each snapshot several seconds after that stamp, so this cannot reach zero; it shows how quickly BIN
	 * Watch picked the snapshot up. Empty when the snapshot time is unknown or the result is implausible.
	 */
	static String snapshotLagLine(FlipCandidate c, long serverOffsetMs, long skewToleranceMs) {
		if (c.snapshotLastUpdatedMs() == null) {
			return "";
		}
		long lag = c.firstApiObservedMs() + serverOffsetMs - c.snapshotLastUpdatedMs();
		if (lag < -skewToleranceMs || lag > 600_000) {
			return "";
		}
		return String.format(Locale.ROOT, "\nFetched %.1fs after its API snapshot (lastUpdated)", Math.max(0, lag) / 1000.0);
	}

	/**
	 * How long after Hypixel published the snapshot this alert was sent, plus how late Hypixel itself published it
	 * relative to its lastUpdated stamp. Empty when the numbers are implausible.
	 */
	static String publishLine(AuctionCollector.PublishEstimate p, long nowMs) {
		long since = nowMs - p.publishedLocalMs();
		if (since < -p.uncertaintyMs() || since > 600_000) {
			return "";
		}
		String hypixel = p.hypixelLagMs() != null && p.hypixelLagMs() >= 0 && p.hypixelLagMs() < 600_000
				? String.format(Locale.ROOT, " (Hypixel published it %.1fs after its lastUpdated)", p.hypixelLagMs() / 1000.0)
				: "";
		return String.format(Locale.ROOT, "\nSent %.1fs after Hypixel published the snapshot%s", Math.max(0, since) / 1000.0,
				hypixel);
	}

	public static JsonObject samplePayload(Style style, long nowMs) {
		return build(sampleCandidate(nowMs), style, nowMs, 5_000);
	}

	/** Synthetic candidate used by {@code /binwatch testwebhook preview}; marked synthetic everywhere it is shown. */
	public static FlipCandidate sampleCandidate(long nowMs) {
		TreeMap<String, Integer> enchants = new TreeMap<>(Map.of("ultimate_wise", 5, "sharpness", 7, "critical", 6,
				"giant_killer", 6, "ender_slayer", 6, "vampirism", 6, "smite", 7, "luck", 6, "looting", 4, "scavenger", 5));
		ItemDetails details = new ItemDetails("HYPERION",
				List.of("Reforge: Heroic", "10 Hot Potato + 5 Fuming", "Art of War",
						"Scrolls: Implosion, Shadow Warp, Wither Shield", "Power Scroll: Sapphire"),
				List.of("Perfect Sapphire"), List.of(), null);
		NormalizedItem item = new NormalizedItem("HYPERION", null, "Heroic Hyperion ✪✪✪✪✪", "LEGENDARY", 1, "heroic", 5,
				true, enchants, "HYPERION|sample", List.of(), details);
		long start = nowMs - 72_000;
		Valuation v = new Valuation(true, null, 1_030_000_000, 0.70,
				"Lowest other BIN of 6 listing(s), API snapshot (sample)", nowMs - 20_000, 1_051_000_000L,
				1_060_000_000L, 6, null, 0, List.of());
		return new FlipCandidate(UUID.nameUUIDFromBytes("binwatch-sample".getBytes(StandardCharsets.UTF_8)),
				item.displayName(), "HYPERION", "", item.comparisonKey(), 900_000_000, 1, start, start + 86_400_000L,
				nowMs - 12_000, nowMs - 26_000, Records.KIND_SYNTHETIC, v, 1_030_000_000, 130_000_000, 36_050_000,
				93_950_000, 12.6, true, "Sample layout only, not a real evaluation", List.of(), "SYNTHETIC TEST DATA - not a live auction; "
				+ DISCLAIMER, true, item);
	}

	public static JsonObject testPayload(long nowMs) {
		JsonObject embed = new JsonObject();
		embed.addProperty("title", "BIN Watch webhook test");
		embed.addProperty("description", "This is a test message requested with /binwatch testwebhook. It is not a flip alert.");
		embed.addProperty("color", 0x3498DB);
		embed.addProperty("timestamp", Instant.ofEpochMilli(nowMs).toString());
		return wrap(embed);
	}

	private static JsonObject wrap(JsonObject embed) {
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

	private static String description(FlipCandidate c, NormalizedItem item) {
		StringBuilder sb = new StringBuilder();
		if (c.synthetic()) {
			sb.append("Synthetic test data, not a live auction.\n\n");
		}
		String name = c.itemName() == null || c.itemName().isBlank() ? "Unknown item" : c.itemName().trim();
		sb.append("**").append(name).append("**");
		List<String> tags = new ArrayList<>();
		if (item != null && item.tier() != null) {
			tags.add(ItemDetails.titleCase(item.tier()));
		}
		if (item != null && item.recombobulated()) {
			tags.add("Recombobulated");
		}
		if (item != null && item.stars() != null && item.stars() > 0 && !name.contains("✪")) {
			tags.add(item.stars() + " stars");
		}
		if (c.count() > 1) {
			tags.add(c.count() + "x");
		}
		if (!tags.isEmpty()) {
			sb.append('\n').append(String.join(" · ", tags));
		}
		return sb.toString();
	}

	private static String upgrades(NormalizedItem item, ItemDetails d) {
		List<String> lines = new ArrayList<>();
		if (d.pet() != null) {
			lines.add(d.pet());
		}
		if (!d.upgrades().isEmpty()) {
			lines.add(String.join(" · ", d.upgrades()));
		}
		if (!d.gems().isEmpty()) {
			lines.add("Gems: " + String.join(", ", d.gems()));
		}
		if (!d.attributes().isEmpty()) {
			lines.add("Attributes: " + String.join(", ", d.attributes()));
		}
		return String.join("\n", lines);
	}

	public static int accentColor(Style style, NormalizedItem item) {
		if (style.colorByRarity() && item != null && item.tier() != null) {
			Integer rarity = RARITY_COLORS.get(item.tier().toUpperCase(Locale.ROOT));
			if (rarity != null) {
				return rarity;
			}
		}
		return style.color();
	}

	public static String imageUrl(Style style, String id) {
		String template = style.imageUrlTemplate();
		if (template == null || !template.startsWith("https://") || !template.contains("{id}") || id == null) {
			return null;
		}
		String upper = id.toUpperCase(Locale.ROOT);
		if (!SAFE_ID.matcher(upper).matches()) {
			return null;
		}
		return template.replace("{id}", URLEncoder.encode(upper, StandardCharsets.UTF_8));
	}

	private static String coins(long v) {
		return TimeFmt.coins(v);
	}

	private static void add(JsonArray fields, String name, String value, boolean inline) {
		if (fields.size() >= 25) {
			return;
		}
		JsonObject f = new JsonObject();
		f.addProperty("name", limit(name, 256));
		f.addProperty("value", limit(value == null || value.isBlank() ? "n/a" : value, MAX_FIELD_VALUE));
		f.addProperty("inline", inline);
		fields.add(f);
	}

	static String limit(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max - 3) + "...";
	}

	/** Shortens the longest field values until the embed fits Discord's 6000 character total. */
	private static void trimToTotal(JsonObject embed) {
		JsonArray fields = embed.getAsJsonArray("fields");
		while (totalChars(embed) > MAX_TOTAL) {
			JsonObject longest = null;
			for (int i = 0; i < fields.size(); i++) {
				JsonObject f = fields.get(i).getAsJsonObject();
				if (longest == null || f.get("value").getAsString().length() > longest.get("value").getAsString().length()) {
					longest = f;
				}
			}
			String v = longest.get("value").getAsString();
			if (v.length() <= 20) {
				break;
			}
			longest.addProperty("value", limit(v, Math.max(20, v.length() - 200)));
		}
	}

	static int totalChars(JsonObject embed) {
		int total = embed.get("title").getAsString().length();
		if (embed.has("description")) {
			total += embed.get("description").getAsString().length();
		}
		if (embed.has("footer")) {
			total += embed.getAsJsonObject("footer").get("text").getAsString().length();
		}
		JsonArray fields = embed.getAsJsonArray("fields");
		if (fields != null) {
			for (int i = 0; i < fields.size(); i++) {
				JsonObject f = fields.get(i).getAsJsonObject();
				total += f.get("name").getAsString().length() + f.get("value").getAsString().length();
			}
		}
		return total;
	}
}
