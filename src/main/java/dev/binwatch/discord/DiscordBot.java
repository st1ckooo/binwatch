package dev.binwatch.discord;

import com.google.gson.JsonObject;
import dev.binwatch.api.HttpTransport;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Discord bot REST calls (API v10) with the bot token from secrets.json. A bot channel is addressed as
 * {@code channel:<id>} wherever a webhook URL is accepted, so alerts and the summary can go to either. The token is
 * only ever placed in the Authorization header; it is never logged or returned.
 */
public final class DiscordBot {
	public static final String API = "https://discord.com/api/v10";
	public static final String TARGET_PREFIX = "channel:";
	/** Discord asks bots to identify themselves in this format. */
	static final String USER_AGENT = "DiscordBot (https://github.com/binwatch, 1.0)";

	private final HttpTransport http;
	private final Supplier<String> token;

	public DiscordBot(HttpTransport http, Supplier<String> token) {
		this.http = http;
		this.token = token;
	}

	public boolean configured() {
		String t = token.get();
		return t != null && !t.isBlank();
	}

	/** {@code channel:<id>} when the bot has a token and the channel id is set, otherwise null. */
	public String target(String channelId) {
		return configured() && channelId != null && channelId.trim().matches("\\d{15,25}") ? TARGET_PREFIX + channelId.trim()
				: null;
	}

	public static boolean isTarget(String target) {
		return target != null && target.startsWith(TARGET_PREFIX);
	}

	static String channelOf(String target) {
		String id = target.substring(TARGET_PREFIX.length());
		if (!id.matches("\\d{15,25}")) {
			throw new IllegalArgumentException("bad channel id");
		}
		return id;
	}

	public HttpTransport.Response createMessage(String target, JsonObject payload, Duration timeout)
			throws IOException, InterruptedException {
		return request("POST", "/channels/" + channelOf(target) + "/messages", forBot(payload).toString(), timeout);
	}

	public HttpTransport.Response editMessage(String target, String messageId, JsonObject payload, Duration timeout)
			throws IOException, InterruptedException {
		if (!messageId.matches("\\d{1,30}")) {
			throw new IllegalArgumentException("bad message id");
		}
		return request("PATCH", "/channels/" + channelOf(target) + "/messages/" + messageId, forBot(payload).toString(),
				timeout);
	}

	public HttpTransport.Response request(String method, String path, String json, Duration timeout)
			throws IOException, InterruptedException {
		String t = token.get();
		if (t == null || t.isBlank()) {
			throw new IOException("no bot token configured");
		}
		return http.send(method, URI.create(API + path), json, timeout,
				Map.of("Authorization", "Bot " + t.trim(), "User-Agent", USER_AGENT));
	}

	/** Bots cannot choose a display name per message; webhook-only fields are removed. */
	static JsonObject forBot(JsonObject payload) {
		JsonObject p = payload.deepCopy();
		p.remove("username");
		p.remove("avatar_url");
		return p;
	}
}
