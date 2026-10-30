package dev.binwatch.trade;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises Hypixel's own Auction House chat messages about your trades. Pure text matching on messages the server
 * already sent you; nothing is sent back. Unknown wording simply does not match (and is logged by the caller).
 */
public final class TradeChatParser {
	public sealed interface Event {
		String item();
	}

	public record Purchased(String item, long price) implements Event {
	}

	public record Listed(String item) implements Event {
	}

	public record Sold(String buyer, String item, long price) implements Event {
	}

	public record Collected(long coins, String item, String buyer) implements Event {
	}

	public record Cancelled(String item) implements Event {
	}

	private static final Pattern PURCHASED = Pattern.compile("^You purchased (.+?) for ([\\d,]+) coins!?$");
	private static final Pattern SOLD = Pattern.compile("^\\[Auction] (.+?) bought (.+?) for ([\\d,]+) coins!?(?: CLICK)?$");
	private static final Pattern COLLECTED = Pattern.compile(
			"^You collected ([\\d,]+) coins? from selling (.+?) to (.+?) in an auction!?$");
	private static final Pattern LISTED = Pattern.compile("^(?:BIN )?Auction started for (.+?)!?$");
	private static final Pattern CANCELLED = Pattern.compile("^You cancell?ed (?:your|the) auction for (.+?)!?$",
			Pattern.CASE_INSENSITIVE);

	private TradeChatParser() {
	}

	/** Parses one chat line (formatting codes already removed); null when it is not a trade message. */
	public static Event parse(String line) {
		if (line == null) {
			return null;
		}
		String s = line.trim();
		Matcher m = PURCHASED.matcher(s);
		if (m.matches()) {
			return new Purchased(m.group(1).trim(), coins(m.group(2)));
		}
		m = SOLD.matcher(s);
		if (m.matches()) {
			return new Sold(m.group(1).trim(), m.group(2).trim(), coins(m.group(3)));
		}
		m = COLLECTED.matcher(s);
		if (m.matches()) {
			return new Collected(coins(m.group(1)), m.group(2).trim(), m.group(3).trim());
		}
		m = LISTED.matcher(s);
		if (m.matches()) {
			return new Listed(m.group(1).trim());
		}
		m = CANCELLED.matcher(s);
		if (m.matches()) {
			return new Cancelled(m.group(1).trim());
		}
		return null;
	}

	/** True for lines that look auction-related but did not match, so new wording can be spotted in the log. */
	public static boolean looksAuctionRelated(String line) {
		String l = line == null ? "" : line.toLowerCase(Locale.ROOT);
		return l.contains("[auction]") || l.contains("you purchased") || l.contains("from selling")
				|| l.contains("auction started") || l.contains("auction for");
	}

	/**
	 * Key that lets chat names and API names of the same item meet: no stack-size prefix ("2x "), no symbols (stars,
	 * frag marks), lower case, single spaces.
	 */
	public static String nameKey(String name) {
		if (name == null) {
			return "";
		}
		String s = name.replaceFirst("^\\d+x\\s+", "").toLowerCase(Locale.ROOT);
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < s.length(); ) {
			int cp = s.codePointAt(i);
			b.appendCodePoint(Character.isLetterOrDigit(cp) || cp == '\'' ? cp : ' ');
			i += Character.charCount(cp);
		}
		return b.toString().trim().replaceAll("\\s+", " ");
	}

	private static long coins(String s) {
		return Long.parseLong(s.replace(",", ""));
	}
}
