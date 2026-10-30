package dev.binwatch.util;

import java.util.regex.Pattern;

/** Removes secrets (Discord webhook credentials and bot tokens) from any text before it is logged or displayed. */
public final class Redactor {
	private static final Pattern WEBHOOK = Pattern.compile(
			"(?i)(https?://)?([a-z]+\\.)?discord(app)?\\.com/api(/v\\d+)?/webhooks/[^\\s\"'<>]+");

	/** Discord bot token shape: three base64url parts separated by dots. */
	private static final Pattern BOT_TOKEN = Pattern.compile("[A-Za-z0-9_\\-]{20,}\\.[A-Za-z0-9_\\-]{4,}\\.[A-Za-z0-9_\\-]{20,}");

	private Redactor() {
	}

	public static String redact(String text) {
		if (text == null) {
			return null;
		}
		return BOT_TOKEN.matcher(WEBHOOK.matcher(text).replaceAll("[REDACTED-WEBHOOK]")).replaceAll("[REDACTED-TOKEN]");
	}
}
