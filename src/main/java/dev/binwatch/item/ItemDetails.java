package dev.binwatch.item;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.binwatch.nbt.Nbt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;

/**
 * Display-ready extras of an item, used only for presentation (Discord embeds). Valuation never reads this; it uses
 * {@link NormalizedItem#comparisonKey()}.
 *
 * @param imageId    SkyBlock id used to look up an item icon (pets use {@code PET_<TYPE>}); null if unknown
 * @param upgrades   readable upgrades such as reforge, potato books, scrolls, dye, skin
 * @param gems       applied gemstones, e.g. "Perfect Jasper"
 * @param attributes Kuudra/Crimson attributes, e.g. "Mana Pool 5"
 * @param pet        pet summary line, or null for non-pets
 */
public record ItemDetails(String imageId, List<String> upgrades, List<String> gems, List<String> attributes,
		String pet) {
	public static final ItemDetails NONE = new ItemDetails(null, List.of(), List.of(), List.of(), null);

	static ItemDetails from(Map<String, Object> extra, String id, String reforge, Long potatoBooks) {
		if (extra == null || id == null) {
			return NONE;
		}
		String imageId = id.toUpperCase(Locale.ROOT);
		String pet = null;
		if ("PET".equals(imageId)) {
			JsonObject info = petInfo(extra);
			String type = info == null ? null : str(info, "type");
			imageId = type == null ? null : "PET_" + type.toUpperCase(Locale.ROOT);
			pet = info == null ? null : petLine(info);
		}

		List<String> upgrades = new ArrayList<>();
		if (reforge != null && !reforge.isBlank()) {
			upgrades.add("Reforge: " + titleCase(reforge));
		}
		if (potatoBooks != null && potatoBooks > 0) {
			long hot = Math.min(potatoBooks, 10);
			upgrades.add(hot + " Hot Potato" + (potatoBooks > 10 ? " + " + (potatoBooks - 10) + " Fuming" : ""));
		}
		Long artOfWar = Nbt.number(extra, "art_of_war_count");
		if (artOfWar != null && artOfWar > 0) {
			upgrades.add("Art of War");
		}
		Long peace = Nbt.number(extra, "artOfPeaceApplied");
		if (peace != null && peace > 0) {
			upgrades.add("Art of Peace");
		}
		Long ether = Nbt.number(extra, "ethermerge");
		if (ether != null && ether > 0) {
			upgrades.add("Etherwarp");
		}
		Long manaDis = Nbt.number(extra, "mana_disintegrator_count");
		if (manaDis != null && manaDis > 0) {
			upgrades.add(manaDis + " Mana Disintegrator");
		}
		Long tuners = Nbt.number(extra, "tuned_transmission");
		if (tuners != null && tuners > 0) {
			upgrades.add(tuners + " Transmission Tuner");
		}
		Long ffd = Nbt.number(extra, "farming_for_dummies_count");
		if (ffd != null && ffd > 0) {
			upgrades.add(ffd + " Farming for Dummies");
		}
		Long jalapeno = Nbt.number(extra, "jalapeno_count");
		if (jalapeno != null && jalapeno > 0) {
			upgrades.add("Jalapeno Book");
		}
		Long wood = Nbt.number(extra, "wood_singularity_count");
		if (wood != null && wood > 0) {
			upgrades.add("Wood Singularity");
		}
		List<Object> scrolls = Nbt.list(extra, "ability_scroll");
		if (scrolls != null && !scrolls.isEmpty()) {
			List<String> names = new ArrayList<>();
			for (Object s : scrolls) {
				if (s instanceof String str) {
					names.add(titleCase(str.replace("_SCROLL", "")));
				}
			}
			if (!names.isEmpty()) {
				upgrades.add("Scrolls: " + String.join(", ", names));
			}
		}
		addNamed(upgrades, "Power Scroll", Nbt.string(extra, "power_ability_scroll"), "_POWER_SCROLL");
		addNamed(upgrades, "Dye", Nbt.string(extra, "dye_item"), null);
		addNamed(upgrades, "Skin", Nbt.string(extra, "skin"), null);
		addNamed(upgrades, "Enrichment", Nbt.string(extra, "talisman_enrichment"), null);
		Map<String, Object> runes = Nbt.compound(extra, "runes");
		if (runes != null) {
			runes.forEach((type, level) -> {
				if (level instanceof Number n) {
					upgrades.add(titleCase(type) + " Rune " + roman(n.intValue()));
				}
			});
		}

		List<String> gems = new ArrayList<>();
		Map<String, Object> gemMap = Nbt.compound(extra, "gems");
		if (gemMap != null) {
			for (Map.Entry<String, Object> e : gemMap.entrySet()) {
				String slot = e.getKey();
				if (slot.endsWith("_gem") || slot.equals("unlocked_slots")) {
					continue;
				}
				String quality = e.getValue() instanceof String q ? q
						: e.getValue() instanceof Map<?, ?> ? Nbt.string(Nbt.compound(gemMap, slot), "quality") : null;
				if (quality == null) {
					continue;
				}
				String type = Nbt.string(gemMap, slot + "_gem");
				if (type == null) {
					int cut = slot.lastIndexOf('_');
					type = cut > 0 ? slot.substring(0, cut) : slot;
				}
				gems.add(titleCase(quality) + " " + titleCase(type));
			}
		}

		List<String> attributes = new ArrayList<>();
		Map<String, Object> attrMap = Nbt.compound(extra, "attributes");
		if (attrMap != null) {
			attrMap.forEach((name, level) -> {
				if (level instanceof Number n) {
					attributes.add(titleCase(name) + " " + n.intValue());
				}
			});
		}
		return new ItemDetails(imageId, List.copyOf(upgrades), List.copyOf(gems), List.copyOf(attributes), pet);
	}

	/**
	 * Enchantments as "Ultimate Wise V, Sharpness VII, ...": ultimate enchantments first, then by level (highest
	 * first), then by name.
	 */
	public static List<String> enchantmentNames(SortedMap<String, Integer> enchants) {
		List<Map.Entry<String, Integer>> list = new ArrayList<>(enchants.entrySet());
		list.sort((a, b) -> {
			boolean ua = a.getKey().startsWith("ultimate_");
			boolean ub = b.getKey().startsWith("ultimate_");
			if (ua != ub) {
				return ua ? -1 : 1;
			}
			int byLevel = Integer.compare(b.getValue(), a.getValue());
			return byLevel != 0 ? byLevel : a.getKey().compareTo(b.getKey());
		});
		List<String> out = new ArrayList<>();
		for (Map.Entry<String, Integer> e : list) {
			out.add(titleCase(e.getKey()) + " " + roman(e.getValue()));
		}
		return out;
	}

	/** "ULTIMATE_WISE" or "ultimate_wise" becomes "Ultimate Wise". */
	public static String titleCase(String raw) {
		StringBuilder sb = new StringBuilder();
		for (String word : raw.trim().toLowerCase(Locale.ROOT).split("[_\\s]+")) {
			if (word.isEmpty()) {
				continue;
			}
			if (!sb.isEmpty()) {
				sb.append(' ');
			}
			sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return sb.toString();
	}

	public static String roman(int n) {
		if (n < 1 || n > 20) {
			return Integer.toString(n);
		}
		String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
		return n <= 10 ? r[n] : "X" + r[n - 10];
	}

	private static void addNamed(List<String> out, String label, String value, String stripSuffix) {
		if (value == null || value.isBlank()) {
			return;
		}
		String v = stripSuffix != null && value.endsWith(stripSuffix)
				? value.substring(0, value.length() - stripSuffix.length()) : value;
		if (label.equals("Dye") && v.startsWith("DYE_")) {
			v = v.substring(4);
		}
		out.add(label + ": " + titleCase(v));
	}

	private static JsonObject petInfo(Map<String, Object> extra) {
		String raw = Nbt.string(extra, "petInfo");
		if (raw == null) {
			return null;
		}
		try {
			JsonElement e = JsonParser.parseString(raw);
			return e.isJsonObject() ? e.getAsJsonObject() : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String petLine(JsonObject info) {
		List<String> parts = new ArrayList<>();
		String held = str(info, "heldItem");
		if (held != null) {
			parts.add("Held: " + titleCase(held.replace("PET_ITEM_", "")));
		}
		String skin = str(info, "skin");
		if (skin != null) {
			parts.add("Skin: " + titleCase(skin));
		}
		JsonElement candy = info.get("candyUsed");
		if (candy != null && candy.isJsonPrimitive() && candy.getAsInt() > 0) {
			parts.add(candy.getAsInt() + " Pet Candy");
		}
		return parts.isEmpty() ? null : String.join(" · ", parts);
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() && !e.getAsString().isBlank() ? e.getAsString() : null;
	}
}
