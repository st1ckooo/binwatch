package dev.binwatch.discord;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.binwatch.api.HttpTransport;
import dev.binwatch.config.BinWatchConfig;
import dev.binwatch.log.BwLog;
import dev.binwatch.store.ObservationStore;
import dev.binwatch.store.Records.Trade;
import dev.binwatch.trade.ProfitSummary;
import dev.binwatch.util.TimeFmt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Keeps one profit-summary message in Discord up to date: the first publish creates it, later ones edit it in place
 * (PATCH on the webhook's own message). The message id is remembered in the database together with a hash of the
 * webhook, so a restart keeps editing the same message and a new webhook starts a new one. The webhook URL is never
 * logged.
 */
public final class SummaryPublisher {
	static final String META_ID = "summary_message_id";
	static final String META_HOOK = "summary_webhook_hash";
	/** Minimum gap between edits caused by trade changes, to stay far from Discord's webhook limits. */
	static final long MIN_GAP_MS = 10_000;

	private final WebhookClient discord;
	private final Clock clock;
	private final Supplier<BinWatchConfig> config;
	/** Webhook URL or bot channel ({@code channel:<id>}). */
	private final Supplier<String> webhookUrl;
	private final ObservationStore store;
	private final Callable<List<Trade>> trades;
	private final Supplier<String> attribution;
	private volatile boolean dirty = true;
	private long lastPublishMs;
	private String lastContent;
	private long blockedUntilMs;
	private volatile String lastResult = "not published yet";
	private boolean loggedUpdate;

	public SummaryPublisher(WebhookClient discord, Clock clock, Supplier<BinWatchConfig> config, Supplier<String> webhookUrl,
			ObservationStore store, Callable<List<Trade>> trades, Supplier<String> attribution) {
		this.discord = discord;
		this.clock = clock;
		this.config = config;
		this.webhookUrl = webhookUrl;
		this.store = store;
		this.trades = trades;
		this.attribution = attribution;
	}

	public void markDirty() {
		dirty = true;
	}

	public String lastResult() {
		return lastResult;
	}

	/** Called every few seconds; publishes when something changed (at most every 10 s) or the update period passed. */
	public synchronized void tick() {
		BinWatchConfig.Summary s = config.get().summary;
		String url = webhookUrl.get();
		long now = clock.millis();
		if (!s.enabled || url == null || url.isBlank() || now < blockedUntilMs) {
			return;
		}
		long since = now - lastPublishMs;
		if ((dirty && since >= MIN_GAP_MS) || since >= s.updateSeconds * 1000L) {
			publish(false);
		}
	}

	/** Publishes now (unless nothing changed and {@code force} is false). Returns a short status line. */
	public synchronized String publish(boolean force) {
		String url = webhookUrl.get();
		if (url == null || url.isBlank()) {
			return lastResult = "no webhook configured";
		}
		long now = clock.millis();
		BinWatchConfig cfg = config.get();
		JsonObject payload;
		try {
			payload = ProfitSummary.payload(ProfitSummary.compute(trades.call(), now, cfg.summary.topCount), now,
					attribution.get());
		} catch (Exception e) {
			BwLog.throttled(BwLog.Level.ERROR, "summary-build", "Could not build the profit summary", e);
			return lastResult = "could not build summary: " + e.getClass().getSimpleName();
		}
		String content = withoutTimestamp(payload);
		lastPublishMs = now;
		dirty = false;
		if (!force && content.equals(lastContent)) {
			return lastResult = "unchanged";
		}
		Duration timeout = Duration.ofSeconds(cfg.discord.requestTimeoutSeconds);
		try {
			String hook = hookHash(url);
			String id = hook.equals(store.getMeta(META_HOOK)) ? store.getMeta(META_ID) : null;
			HttpTransport.Response res = null;
			if (id != null) {
				res = discord.edit(url, id, payload, timeout);
				if (res.status() == 404) {
					BwLog.info("Profit summary message was deleted in Discord; posting a new one");
					id = null;
				}
			}
			if (id == null) {
				res = discord.post(url, payload, timeout);
				if (res.status() >= 200 && res.status() < 300) {
					String newId = messageId(res.body());
					if (newId != null) {
						store.setMeta(META_ID, newId);
						store.setMeta(META_HOOK, hook);
					}
				}
			}
			int st = res.status();
			if (st >= 200 && st < 300) {
				lastContent = content;
				if (id == null || !loggedUpdate) {
					BwLog.info("Profit summary " + (id == null ? "posted as a new Discord message" : "updated in Discord"));
					loggedUpdate = id != null;
				}
				return lastResult = (id == null ? "posted" : "updated") + " at " + TimeFmt.utc(now);
			}
			if (st == 429) {
				long wait = WebhookClient.retryAfterMs(res, now);
				blockedUntilMs = now + wait;
				dirty = true;
				return lastResult = "Discord rate limit; retrying in " + Math.max(1, wait / 1000) + " s";
			}
			blockedUntilMs = now + 60_000;
			dirty = true;
			BwLog.throttled(BwLog.Level.WARN, "summary-http", "Profit summary not published: HTTP " + st, null);
			return lastResult = "Discord answered HTTP " + st;
		} catch (IOException e) {
			dirty = true;
			BwLog.throttled(BwLog.Level.WARN, "summary-io", "Profit summary not published: " + e.getClass().getSimpleName(), null);
			return lastResult = "network error: " + e.getClass().getSimpleName();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return lastResult = "interrupted";
		} catch (SQLException e) {
			BwLog.throttled(BwLog.Level.ERROR, "summary-db", "Profit summary message id could not be stored", e);
			return lastResult = "database error";
		} catch (IllegalArgumentException e) {
			return lastResult = "webhook URL or channel id is invalid";
		}
	}

	static String withoutTimestamp(JsonObject payload) {
		JsonObject copy = payload.deepCopy();
		copy.getAsJsonArray("embeds").get(0).getAsJsonObject().remove("timestamp");
		return copy.toString();
	}

	static String messageId(String body) {
		try {
			JsonElement el = JsonParser.parseString(body == null ? "" : body);
			if (el.isJsonObject() && el.getAsJsonObject().has("id")) {
				String id = el.getAsJsonObject().get("id").getAsString();
				return id.matches("\\d{1,30}") ? id : null;
			}
		} catch (RuntimeException ignored) {
			// No id: the next publish posts again.
		}
		return null;
	}

	/** Short SHA-256 prefix of a webhook URL: tells webhooks apart without storing the URL. */
	public static String hookHash(String url) {
		try {
			byte[] d = MessageDigest.getInstance("SHA-256").digest(url.trim().getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(d, 0, 8);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
