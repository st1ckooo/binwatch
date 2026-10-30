package dev.binwatch.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.binwatch.log.BwLog;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Posts informational messages (trades, log lines) to a Discord channel in the background, in order. The queue is
 * bounded and drops the oldest message when full, so a Discord outage can never hold up the mod. Failures are logged
 * with a "Discord feed" prefix, which the log channel itself ignores to avoid a loop.
 */
public final class DiscordFeed {
	public static final String LOG_PREFIX = "Discord feed";

	private final WebhookClient discord;
	private final LongSupplier clock;
	private final Duration timeout;
	private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
			new ArrayBlockingQueue<>(100), r -> {
				Thread t = new Thread(r, "BINWatch-discord-feed");
				t.setDaemon(true);
				return t;
			}, new ThreadPoolExecutor.DiscardOldestPolicy());

	public DiscordFeed(WebhookClient discord, LongSupplier clock, Duration timeout) {
		this.discord = discord;
		this.clock = clock;
		this.timeout = timeout;
	}

	/** Queues one message for {@code target} (a webhook URL or {@code channel:<id>}); null target does nothing. */
	public void post(String target, JsonObject payload) {
		if (target == null || target.isBlank()) {
			return;
		}
		worker.execute(() -> send(target, payload));
	}

	private void send(String target, JsonObject payload) {
		for (int attempt = 1; attempt <= 3; attempt++) {
			WebhookClient.Result r = discord.send(target, payload, timeout, clock.getAsLong());
			switch (r.outcome()) {
				case DELIVERED -> {
					return;
				}
				case RATE_LIMITED -> sleep(Math.min(r.retryAfterMs(), 30_000));
				case RETRYABLE_ERROR -> sleep(1000L * attempt);
				case PERMANENT_ERROR -> {
					BwLog.throttled(BwLog.Level.WARN, "discord-feed", LOG_PREFIX + ": message not accepted: " + r.detail(), null);
					return;
				}
			}
		}
		BwLog.throttled(BwLog.Level.WARN, "discord-feed", LOG_PREFIX + ": message dropped after 3 attempts", null);
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	public void shutdown() {
		worker.shutdown();
		try {
			worker.awaitTermination(2, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		worker.shutdownNow();
	}

	/** A one-embed message with no mentions. */
	public static JsonObject embed(String title, String description, int color, long nowMs) {
		JsonObject embed = new JsonObject();
		embed.addProperty("title", FlipEmbed.limit(title, 256));
		embed.addProperty("description", FlipEmbed.limit(description == null || description.isBlank() ? "-" : description, 4000));
		embed.addProperty("color", color & 0xFFFFFF);
		embed.addProperty("timestamp", Instant.ofEpochMilli(nowMs).toString());
		JsonObject payload = new JsonObject();
		payload.addProperty("username", "BIN Watch");
		JsonArray embeds = new JsonArray();
		embeds.add(embed);
		payload.add("embeds", embeds);
		JsonObject allowed = new JsonObject();
		allowed.add("parse", new JsonArray());
		payload.add("allowed_mentions", allowed);
		return payload;
	}
}
