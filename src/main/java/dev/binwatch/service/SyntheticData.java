package dev.binwatch.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.binwatch.nbt.NbtWriter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builders for synthetic API payloads. Every synthetic item name is prefixed with {@value #MARK} so it can never be
 * mistaken for a live auction. Used by {@code /binwatch testmode} and the unit tests.
 */
public final class SyntheticData {
	public static final String MARK = "[TEST] ";

	private SyntheticData() {
	}

	public record Auction(UUID uuid, String skyblockId, String name, String tier, long price, boolean bin, long startMs,
			long endMs, String itemUuid, int count, Map<String, Object> extra) {
		public static Auction bin(String id, long price, long startMs) {
			return new Auction(UUID.randomUUID(), id, MARK + id, "LEGENDARY", price, true, startMs, startMs + 6 * 3_600_000L,
					UUID.randomUUID().toString(), 1, Map.of());
		}

		public Auction withUuid(UUID u) {
			return new Auction(u, skyblockId, name, tier, price, bin, startMs, endMs, itemUuid, count, extra);
		}
	}

	public static String itemBytes(Auction a) {
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put("id", a.skyblockId());
		if (a.itemUuid() != null) {
			extra.put("uuid", a.itemUuid());
		}
		extra.putAll(a.extra());
		Map<String, Object> display = new LinkedHashMap<>();
		display.put("Name", a.name());
		display.put("Lore", List.of("§f§6§l" + a.tier()));
		Map<String, Object> tag = new LinkedHashMap<>();
		tag.put("ExtraAttributes", extra);
		tag.put("display", display);
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("id", (short) 1);
		item.put("Count", (byte) a.count());
		item.put("tag", tag);
		item.put("components", Map.of("minecraft:tooltip_style", "hypixel_skyblock:" + a.tier().toLowerCase(java.util.Locale.ROOT)));
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("i", List.of(item));
		return NbtWriter.base64Gzip(root);
	}

	public static JsonObject auctionJson(Auction a) {
		JsonObject o = new JsonObject();
		o.addProperty("uuid", a.uuid().toString().replace("-", ""));
		o.addProperty("auctioneer", "00000000000000000000000000000000");
		o.addProperty("start", a.startMs());
		o.addProperty("end", a.endMs());
		o.addProperty("item_name", a.name());
		o.addProperty("tier", a.tier());
		o.addProperty("category", "misc");
		o.addProperty("starting_bid", a.price());
		o.addProperty("highest_bid_amount", 0);
		if (a.bin()) {
			o.addProperty("bin", true);
		}
		o.addProperty("claimed", false);
		o.addProperty("item_bytes", itemBytes(a));
		if (a.itemUuid() != null) {
			o.addProperty("item_uuid", a.itemUuid().replace("-", ""));
		}
		o.addProperty("last_updated", a.startMs());
		return o;
	}

	public static String pageJson(int page, int totalPages, long lastUpdated, List<Auction> auctions) {
		JsonObject o = new JsonObject();
		o.addProperty("success", true);
		o.addProperty("page", page);
		o.addProperty("totalPages", totalPages);
		o.addProperty("totalAuctions", auctions.size());
		o.addProperty("lastUpdated", lastUpdated);
		JsonArray arr = new JsonArray();
		auctions.forEach(a -> arr.add(auctionJson(a)));
		o.add("auctions", arr);
		return o.toString();
	}

	public static String endedJson(long lastUpdated, List<Auction> sold, long soldAtMs) {
		JsonObject o = new JsonObject();
		o.addProperty("success", true);
		o.addProperty("lastUpdated", lastUpdated);
		JsonArray arr = new JsonArray();
		for (Auction a : sold) {
			JsonObject e = new JsonObject();
			e.addProperty("auction_id", a.uuid().toString().replace("-", ""));
			e.addProperty("timestamp", soldAtMs);
			e.addProperty("price", a.price());
			e.addProperty("bin", a.bin());
			e.addProperty("item_bytes", itemBytes(a));
			arr.add(e);
		}
		o.add("auctions", arr);
		return o.toString();
	}
}
