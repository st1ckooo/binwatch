package dev.binwatch.discord;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.binwatch.api.AuctionApiClient;
import dev.binwatch.api.HttpTransport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * Sends one payload to a Discord webhook and classifies the result. A message counts as delivered only when Discord
 * answers 2xx; {@code ?wait=true} makes Discord confirm that the message was actually created. The webhook URL is
 * never included in any returned message.
 */
public final class WebhookClient {
	public enum Outcome {
		DELIVERED, RATE_LIMITED, RETRYABLE_ERROR, PERMANENT_ERROR
	}

	public record Result(Outcome outcome, int httpStatus, long retryAfterMs, String detail) {
		public boolean delivered() {
			return outcome == Outcome.DELIVERED;
		}
	}

	private final HttpTransport http;
	private final DiscordBot bot;

	public WebhookClient(HttpTransport http) {
		this(http, null);
	}

	/** @param bot used for {@code channel:<id>} targets; null when only webhooks are used */
	public WebhookClient(HttpTransport http, DiscordBot bot) {
		this.http = http;
		this.bot = bot;
	}

	/** Creates a message at a webhook URL or a bot channel ({@code channel:<id>}); Discord's reply includes its id. */
	public HttpTransport.Response post(String target, JsonObject payload, Duration timeout)
			throws IOException, InterruptedException {
		if (DiscordBot.isTarget(target)) {
			if (bot == null) {
				throw new IOException("bot channel given but no bot configured");
			}
			return bot.createMessage(target, payload, timeout);
		}
		return http.postJson(withWait(target), payload.toString(), timeout);
	}

	/** Edits a message this webhook or bot created earlier. */
	public HttpTransport.Response edit(String target, String messageId, JsonObject payload, Duration timeout)
			throws IOException, InterruptedException {
		if (DiscordBot.isTarget(target)) {
			if (bot == null) {
				throw new IOException("bot channel given but no bot configured");
			}
			return bot.editMessage(target, messageId, payload, timeout);
		}
		return http.patchJson(messageUri(target, messageId), payload.toString(), timeout);
	}

	/** {@code https://discord.com/api/webhooks/<id>/<token>?thread_id=1} → {@code .../messages/<msg>?thread_id=1}. */
	static URI messageUri(String webhookUrl, String messageId) {
		if (!messageId.matches("\\d{1,30}")) {
			throw new IllegalArgumentException("bad message id");
		}
		URI base = URI.create(webhookUrl.trim());
		String path = base.getRawPath().replaceAll("/+$", "") + "/messages/" + messageId;
		String q = base.getRawQuery();
		return URI.create(base.getScheme() + "://" + base.getRawAuthority() + path + (q == null || q.isEmpty() ? "" : "?" + q));
	}

	public Result send(String target, JsonObject payload, Duration timeout, long nowMs) {
		HttpTransport.Response res;
		try {
			res = post(target, payload, timeout);
		} catch (IllegalArgumentException e) {
			return new Result(Outcome.PERMANENT_ERROR, 0, 0, DiscordBot.isTarget(target) ? "channel id is invalid"
					: "webhook URL is invalid");
		} catch (HttpTimeoutException e) {
			return new Result(Outcome.RETRYABLE_ERROR, 0, 0, "request timed out");
		} catch (IOException e) {
			return new Result(Outcome.RETRYABLE_ERROR, 0, 0, "network error: " + e.getClass().getSimpleName());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new Result(Outcome.RETRYABLE_ERROR, 0, 0, "interrupted");
		}
		int s = res.status();
		if (s >= 200 && s < 300) {
			return new Result(Outcome.DELIVERED, s, 0, "HTTP " + s);
		}
		if (s == 429) {
			return new Result(Outcome.RATE_LIMITED, s, retryAfterMs(res, nowMs), "HTTP 429 from Discord");
		}
		String detail = "HTTP " + s + discordMessage(res.body());
		if (s >= 500 || s == 408) {
			return new Result(Outcome.RETRYABLE_ERROR, s, 0, detail);
		}
		return new Result(Outcome.PERMANENT_ERROR, s, 0, detail);
	}

	static URI withWait(String webhookUrl) {
		URI base = URI.create(webhookUrl.trim());
		String q = base.getRawQuery();
		String query = q == null || q.isEmpty() ? "wait=true" : q.contains("wait=") ? q : q + "&wait=true";
		return URI.create(base.getScheme() + "://" + base.getRawAuthority() + base.getRawPath() + "?" + query);
	}

	/** Discord sends {@code retry_after} (seconds, fractional) in the JSON body and usually a Retry-After header. */
	static long retryAfterMs(HttpTransport.Response res, long nowMs) {
		long fromBody = 0;
		try {
			JsonElement el = JsonParser.parseString(res.body() == null ? "" : res.body());
			if (el.isJsonObject() && el.getAsJsonObject().has("retry_after")) {
				fromBody = (long) Math.ceil(el.getAsJsonObject().get("retry_after").getAsDouble() * 1000);
			}
		} catch (RuntimeException ignored) {
			// Fall back to the header.
		}
		long fromHeader = AuctionApiClient.parseRetryAfterMs(res.header("Retry-After"), nowMs);
		long v = Math.max(fromBody, fromHeader);
		return v > 0 ? v : 1000;
	}

	private static String discordMessage(String body) {
		try {
			JsonElement el = JsonParser.parseString(body == null ? "" : body);
			if (el.isJsonObject() && el.getAsJsonObject().has("message")) {
				String m = el.getAsJsonObject().get("message").getAsString();
				return ": " + (m.length() > 200 ? m.substring(0, 200) : m);
			}
		} catch (RuntimeException ignored) {
			// Non-JSON body.
		}
		return "";
	}
}
