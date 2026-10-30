package dev.binwatch.config;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Secret values loaded from {@code config/binwatch/secrets.json} (never committed) or the
 * {@code BINWATCH_DISCORD_WEBHOOK_URL} environment variable. {@link #toString()} never reveals the values.
 */
public final class Secrets {
	public static final String ENV_WEBHOOK = "BINWATCH_DISCORD_WEBHOOK_URL";
	private static final Pattern BOT_TOKEN = Pattern.compile("^[A-Za-z0-9_\\-]{20,}\\.[A-Za-z0-9_\\-]{4,}\\.[A-Za-z0-9_\\-]{20,}$");
	private static final Pattern WEBHOOK_PATH = Pattern.compile("^/api(/v\\d+)?/webhooks/\\d{5,30}/[A-Za-z0-9_\\-]{20,200}/?$");

	public String discordWebhookUrl = "";
	/** Optional separate webhook (e.g. its own channel) for the live profit summary; empty uses the alert webhook. */
	public String discordSummaryWebhookUrl = "";
	/** Standalone app: webhook for the profit tracker (flips that were still buyable when alerted). Optional. */
	public String discordProfitWebhookUrl = "";
	/** Discord bot token (Developer Portal → your application → Bot → Reset Token). Optional; never logged. */
	public String discordBotToken = "";

	public boolean hasWebhook() {
		return discordWebhookUrl != null && !discordWebhookUrl.isBlank();
	}

	public boolean hasProfitWebhook() {
		return discordProfitWebhookUrl != null && !discordProfitWebhookUrl.isBlank();
	}

	public boolean hasBotToken() {
		return discordBotToken != null && !discordBotToken.isBlank();
	}

	/** Returns null when the token has the shape of a Discord bot token, otherwise a reason that does not echo it. */
	public static String validateBotToken(String token) {
		if (token == null || token.isBlank()) {
			return "no bot token configured";
		}
		if (!BOT_TOKEN.matcher(token.trim()).matches()) {
			return "bot token does not look like a Discord bot token (three parts separated by dots)";
		}
		return null;
	}

	/** Webhook used for the profit summary. */
	public String summaryWebhookUrl() {
		return discordSummaryWebhookUrl != null && !discordSummaryWebhookUrl.isBlank() ? discordSummaryWebhookUrl : discordWebhookUrl;
	}

	/** Returns null when the URL looks like a Discord webhook, otherwise a reason that does not echo the URL. */
	public static String validateWebhookUrl(String url) {
		if (url == null || url.isBlank()) {
			return "no webhook URL configured";
		}
		URI uri;
		try {
			uri = URI.create(url.trim());
		} catch (IllegalArgumentException e) {
			return "webhook URL is not a valid URI";
		}
		if (!"https".equalsIgnoreCase(uri.getScheme())) {
			return "webhook URL must use https";
		}
		String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
		if (!(host.equals("discord.com") || host.equals("discordapp.com") || host.equals("canary.discord.com")
				|| host.equals("ptb.discord.com"))) {
			return "webhook host must be discord.com";
		}
		if (uri.getPath() == null || !WEBHOOK_PATH.matcher(uri.getPath()).matches()) {
			return "webhook URL path must look like /api/webhooks/<id>/<token>";
		}
		return null;
	}

	@Override
	public String toString() {
		return "Secrets{discordWebhookUrl=" + (hasWebhook() ? "<set>" : "<empty>") + ", discordSummaryWebhookUrl="
				+ (discordSummaryWebhookUrl != null && !discordSummaryWebhookUrl.isBlank() ? "<set>" : "<empty>")
				+ ", discordProfitWebhookUrl=" + (hasProfitWebhook() ? "<set>" : "<empty>")
				+ ", discordBotToken=" + (hasBotToken() ? "<set>" : "<empty>") + "}";
	}
}
