package dev.binwatch.item;

import java.util.List;
import java.util.SortedMap;

/**
 * Parsed identity of a SkyBlock item.
 *
 * @param skyblockId     ExtraAttributes.id (e.g. HYPERION); null if the item carries no SkyBlock id
 * @param itemUuid       ExtraAttributes.uuid, the per-item UUID (NOT the auction UUID); null for stackable items
 * @param comparisonKey  key used to compare only materially equivalent items; null when the item cannot be valued
 * @param attributeNotes human readable list of important attributes (stars, enchants, recomb...)
 * @param details        display-only extras (icon id, upgrades, gems, attributes, pet); never used for valuation
 */
public record NormalizedItem(
		String skyblockId,
		String itemUuid,
		String displayName,
		String tier,
		int count,
		String reforge,
		Integer stars,
		boolean recombobulated,
		SortedMap<String, Integer> enchantments,
		String comparisonKey,
		List<String> attributeNotes,
		ItemDetails details) {

	public String attributeSummary() {
		return String.join(", ", attributeNotes);
	}
}
