package dev.binwatch.view;

import java.util.List;
import java.util.Locale;

/**
 * Reads what an opened /viewauction screen says about a listing. Pure text rules so they are unit tested and easy to
 * correct from recorded live data: every check stores the raw button and lore next to the result.
 *
 * <p>Known Hypixel states of the purchase button (slot 31 of the "BIN Auction View" menu): a gold nugget "Buy Item
 * Right Now" while it can be bought, a poisonous potato when you cannot afford it, and a bed during the short delay
 * before a freshly listed BIN can be bought. A listing that has been bought or has ended says so in the lore, and one
 * that no longer exists is answered in chat instead of opening a menu.
 */
public final class ViewClassifier {
	public enum Result {
		BUYABLE(true), CANT_AFFORD(true), PURCHASE_DELAY(true),
		SOLD(false), ENDED(false), NOT_FOUND(false),
		OWN_AUCTION(null), NOT_BIN(null), COMMAND_REFUSED(null), UNKNOWN(null), NO_RESPONSE(null), SKIPPED_STALE(null);

		/** True: still for sale when we looked. False: already gone. Null: says nothing either way. */
		public final Boolean stillForSale;

		Result(Boolean stillForSale) {
			this.stillForSale = stillForSale;
		}
	}

	private ViewClassifier() {
	}

	/**
	 * @param title        menu title, formatting removed
	 * @param buttonItemId registry id of the purchase button, e.g. {@code minecraft:gold_nugget} (null if none)
	 * @param buttonName   its display name
	 * @param buttonLore   its lore lines
	 * @param itemLore     lore of the auctioned item (status lines such as "Status: Sold!" appear there)
	 */
	public static Result classifyView(String title, String buttonItemId, String buttonName, List<String> buttonLore,
			List<String> itemLore) {
		String t = lower(title);
		String name = lower(buttonName);
		String text = name + "\n" + lower(String.join("\n", buttonLore)) + "\n" + lower(String.join("\n", itemLore));
		if (containsAny(text, "status: sold", "sold!", "already been sold", "already been bought", "already been purchased",
				"already sold", "has been sold")) {
			return Result.SOLD;
		}
		if (containsAny(text, "status: ended", "status: expired", "auction ended", "has ended", "has expired")) {
			return Result.ENDED;
		}
		if (containsAny(name, "collect auction", "cancel auction", "claim")) {
			return Result.OWN_AUCTION;
		}
		if (!t.contains("auction view")) {
			return Result.UNKNOWN;
		}
		if (!t.contains("bin")) {
			return Result.NOT_BIN;
		}
		String id = lower(buttonItemId);
		if (id.endsWith("_bed") || id.endsWith(":bed")) {
			return Result.PURCHASE_DELAY;
		}
		if (id.endsWith("poisonous_potato") || containsAny(text, "cannot afford", "can't afford", "not enough coins")) {
			return Result.CANT_AFFORD;
		}
		if (id.endsWith("gold_nugget") || text.contains("click to purchase")) {
			return Result.BUYABLE;
		}
		return Result.UNKNOWN;
	}

	/** Classifies a chat reply to /viewauction; null when the message is unrelated. */
	public static Result classifyChat(String message) {
		String m = lower(message);
		if (containsAny(m, "wasn't found", "was not found", "couldn't find", "could not find", "doesn't exist",
				"does not exist", "no longer exists", "invalid auction")) {
			return Result.NOT_FOUND;
		}
		if (containsAny(m, "already been sold", "already been bought", "already sold")) {
			return Result.SOLD;
		}
		if (containsAny(m, "unknown command", "you can't use", "you cannot use", "you need the cookie", "booster cookie",
				"only use this command", "please wait", "slow down", "on cooldown")) {
			return Result.COMMAND_REFUSED;
		}
		return null;
	}

	private static boolean containsAny(String s, String... needles) {
		for (String n : needles) {
			if (s.contains(n)) {
				return true;
			}
		}
		return false;
	}

	private static String lower(String s) {
		return s == null ? "" : s.toLowerCase(Locale.ROOT);
	}
}
