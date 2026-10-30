package dev.binwatch.item;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.binwatch.nbt.Nbt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a {@link NormalizedItem} from Hypixel's ExtraAttributes data. The comparison key is deliberately strict
 * (id, rarity, all enchantments, stars, recombobulation, and every configured value attribute) so materially
 * different variants are never compared as if identical. Strict keys mean fewer comparables, which lowers valuation
 * confidence; that trade-off is intentional.
 */
public final class ItemNormalizer {
	private static final Pattern FORMATTING = Pattern.compile("§.");
	private static final Pattern PET_LEVEL = Pattern.compile("\\[Lvl (\\d{1,3})]");

	private final boolean includeReforge;
	private final List<String> valueAttributes;

	public ItemNormalizer(boolean includeReforge, Collection<String> valueAttributes) {
		this.includeReforge = includeReforge;
		this.valueAttributes = valueAttributes.stream().sorted().distinct().toList();
	}

	public static String stripFormatting(String s) {
		return s == null ? null : FORMATTING.matcher(s).replaceAll("");
	}

	/**
	 * Hypixel used to nest SkyBlock data under {@code ExtraAttributes}; newer item formats may put it directly in the
	 * custom data compound. Accept either.
	 */
	public static Map<String, Object> extraAttributes(Map<String, Object> tagOrCustomData) {
		if (tagOrCustomData == null) {
			return null;
		}
		Map<String, Object> nested = Nbt.compound(tagOrCustomData, "ExtraAttributes");
		return nested != null ? nested : tagOrCustomData;
	}

	/**
	 * Decodes an API {@code item_bytes} root compound ({@code {i:[{id,Count,tag:{ExtraAttributes,display}}]}}) into
	 * the item tag and count of its first item.
	 */
	public record ItemBytes(Map<String, Object> tag, int count, String tooltipTier) {
	}

	public static ItemBytes firstItem(Map<String, Object> root) {
		List<Object> items = Nbt.list(root, "i");
		if (items == null || items.isEmpty() || !(items.get(0) instanceof Map<?, ?>)) {
			return null;
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> first = (Map<String, Object>) items.get(0);
		Long count = Nbt.number(first, "Count");
		if (count == null) {
			count = Nbt.number(first, "count");
		}
		// Observed in live data: components {"minecraft:tooltip_style": "hypixel_skyblock:mythic"} carries the rarity.
		String tooltipTier = null;
		String style = Nbt.string(Nbt.compound(first, "components"), "minecraft:tooltip_style");
		if (style != null && style.startsWith("hypixel_skyblock:")) {
			tooltipTier = style.substring("hypixel_skyblock:".length()).toUpperCase(Locale.ROOT);
		}
		return new ItemBytes(Nbt.compound(first, "tag"), count == null ? 1 : (int) Math.max(1, Math.min(count, 64 * 64)),
				tooltipTier);
	}

	public NormalizedItem normalize(Map<String, Object> extra, String displayName, String tier, int count) {
		String name = stripFormatting(displayName);
		String id = Nbt.string(extra, "id");
		String itemUuid = Nbt.string(extra, "uuid");
		String upperTier = tier == null || tier.isBlank() ? null : tier.trim().toUpperCase(Locale.ROOT);
		String reforge = Nbt.string(extra, "modifier");
		Long starsRaw = Nbt.number(extra, "upgrade_level");
		if (starsRaw == null) {
			starsRaw = Nbt.number(extra, "dungeon_item_level");
		}
		Integer stars = starsRaw == null ? null : starsRaw.intValue();
		Long recomb = Nbt.number(extra, "rarity_upgrades");
		boolean recombobulated = recomb != null && recomb > 0;

		SortedMap<String, Integer> enchants = new TreeMap<>();
		Map<String, Object> enchantMap = Nbt.compound(extra, "enchantments");
		if (enchantMap != null) {
			for (Map.Entry<String, Object> e : enchantMap.entrySet()) {
				if (e.getValue() instanceof Number n) {
					enchants.put(e.getKey().toLowerCase(Locale.ROOT), n.intValue());
				}
			}
		}

		List<String> notes = new ArrayList<>();
		if (upperTier != null) {
			notes.add(upperTier);
		}
		if (stars != null && stars > 0) {
			notes.add(stars + " stars");
		}
		if (recombobulated) {
			notes.add("Recombobulated");
		}
		if (reforge != null && !reforge.isBlank()) {
			notes.add("Reforge: " + reforge);
		}
		Long hpb = Nbt.number(extra, "hot_potato_count");
		if (hpb != null && hpb > 0) {
			notes.add("Potato books: " + hpb);
		}
		if (!enchants.isEmpty()) {
			StringBuilder sb = new StringBuilder("Enchants: ");
			int shown = 0;
			for (Map.Entry<String, Integer> e : enchants.entrySet()) {
				if (shown++ > 0) {
					sb.append(", ");
				}
				if (shown > 8) {
					sb.append("+").append(enchants.size() - 8).append(" more");
					break;
				}
				sb.append(e.getKey()).append(' ').append(e.getValue());
			}
			notes.add(sb.toString());
		}
		if (count > 1) {
			notes.add("Count: " + count);
		}

		String key = null;
		if (id != null && !id.isBlank()) {
			StringBuilder k = new StringBuilder();
			if ("PET".equals(id)) {
				k.append(petKey(extra, name, notes));
			} else {
				k.append(id.toUpperCase(Locale.ROOT));
			}
			if (upperTier != null && !"PET".equals(id)) {
				k.append("|tier=").append(upperTier);
			}
			if (!enchants.isEmpty()) {
				k.append("|ench=");
				enchants.forEach((e, lvl) -> k.append(e).append(':').append(lvl).append(','));
			}
			if (includeReforge && reforge != null && !reforge.isBlank()) {
				k.append("|reforge=").append(reforge.toLowerCase(Locale.ROOT));
			}
			for (String attr : valueAttributes) {
				Object v = extra == null ? null : extra.get(attr);
				if (v != null) {
					k.append('|').append(attr).append('=').append(Nbt.canonical(v));
				}
			}
			key = k.toString();
		}
		return new NormalizedItem(id, itemUuid, name, upperTier, Math.max(1, count), reforge, stars, recombobulated,
				enchants, key, List.copyOf(notes), ItemDetails.from(extra, id, reforge, hpb));
	}

	private static String petKey(Map<String, Object> extra, String name, List<String> notes) {
		String petInfo = Nbt.string(extra, "petInfo");
		String type = "UNKNOWN";
		String tier = "UNKNOWN";
		String held = "";
		String skin = "";
		if (petInfo != null) {
			try {
				JsonElement parsed = JsonParser.parseString(petInfo);
				if (parsed.isJsonObject()) {
					JsonObject o = parsed.getAsJsonObject();
					type = str(o, "type", type);
					tier = str(o, "tier", tier);
					held = str(o, "heldItem", "");
					skin = str(o, "skin", "");
				}
			} catch (RuntimeException ignored) {
				// Unparseable petInfo: the key stays at UNKNOWN, which keeps it apart from real comparables.
			}
		}
		Matcher m = name == null ? null : PET_LEVEL.matcher(name);
		String level = m != null && m.find() ? m.group(1) : "?";
		notes.add("Pet: " + type + " " + tier + " Lvl " + level + (held.isEmpty() ? "" : ", held " + held));
		return "PET:" + type + "|tier=" + tier + "|lvl=" + level + "|held=" + held + "|skin=" + skin;
	}

	private static String str(JsonObject o, String key, String fallback) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
	}
}
