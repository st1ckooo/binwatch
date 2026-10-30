package dev.binwatch.discord;

import com.google.gson.JsonObject;
import dev.binwatch.api.AuctionApiClient;
import dev.binwatch.api.AuctionCollector;
import dev.binwatch.config.BinWatchConfig;
import dev.binwatch.log.BwLog;
import dev.binwatch.store.ObservationStore;
import dev.binwatch.store.Records;
import dev.binwatch.store.Records.AlertStatus;
import dev.binwatch.value.FlipCandidate;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Queues and delivers flip alerts. Bounded queue (lowest-profit alert is dropped first), per-auction deduplication
 * and per-item cooldowns persisted in the database, per-minute and per-hour limits, and Discord 429 handling.
 * Runs on its own thread so Discord problems never block the collectors.
 */
public final class AlertDispatcher {
	public enum OfferResult {
		QUEUED, DUPLICATE, COOLDOWN, DISABLED, NO_WEBHOOK, SYNTHETIC_NOT_SENT, NOT_PASSED, QUEUE_FULL, ERROR
	}

	private record Pending(FlipCandidate candidate, long queuedAtMs) {
	}

	private final ObservationStore store;
	private final WebhookClient webhook;
	private final Clock clock;
	private final AuctionApiClient.Sleeper sleeper;
	private final Supplier<String> webhookUrl;
	private volatile BinWatchConfig config;
	private final PriorityBlockingQueue<Pending> queue = new PriorityBlockingQueue<>(64,
			Comparator.comparingLong((Pending p) -> -p.candidate().estimatedNetProfit()));
	private final Deque<Long> sentLastMinute = new ArrayDeque<>();
	private volatile long discordRateLimitedUntilMs;
	/** Senders: alerts from one snapshot usually arrive together, so a second sender keeps them from queuing behind one POST. */
	static final int SENDERS = 2;
	private volatile Thread[] workers;
	private volatile boolean running;
	private volatile String lastDeliveryError;
	private volatile long lastDeliveredMs;
	/** Hypixel server time minus local time, used to express latency against the snapshot's lastUpdated. */
	private volatile LongSupplier serverClockOffsetMs = () -> 0;
	/** Snapshot lastUpdated to its estimated publish time, to express latency from the moment Hypixel published. */
	private volatile LongFunction<AuctionCollector.PublishEstimate> publishEstimates = lu -> null;

	public final AtomicLong delivered = new AtomicLong();
	public final AtomicLong failed = new AtomicLong();
	public final AtomicLong dropped = new AtomicLong();
	public final AtomicLong rateLimited = new AtomicLong();

	public AlertDispatcher(ObservationStore store, WebhookClient webhook, Clock clock, AuctionApiClient.Sleeper sleeper,
			BinWatchConfig config, Supplier<String> webhookUrl) {
		this.store = store;
		this.webhook = webhook;
		this.clock = clock;
		this.sleeper = sleeper;
		this.config = config;
		this.webhookUrl = webhookUrl;
	}

	public void applyConfig(BinWatchConfig cfg) {
		this.config = cfg;
	}

	public void setServerClockOffset(LongSupplier offsetMs) {
		this.serverClockOffsetMs = offsetMs;
	}

	public void setPublishEstimates(LongFunction<AuctionCollector.PublishEstimate> estimates) {
		this.publishEstimates = estimates;
	}

	public synchronized void start() {
		if (running) {
			return;
		}
		running = true;
		workers = new Thread[SENDERS];
		for (int i = 0; i < SENDERS; i++) {
			workers[i] = new Thread(this::loop, "BINWatch-discord-" + (i + 1));
			workers[i].setDaemon(true);
			workers[i].start();
		}
	}

	public synchronized void stop() {
		running = false;
		if (workers != null) {
			for (Thread w : workers) {
				w.interrupt();
			}
		}
	}

	public OfferResult offer(FlipCandidate c) {
		if (c.synthetic()) {
			return OfferResult.SYNTHETIC_NOT_SENT;
		}
		if (!c.passed()) {
			return OfferResult.NOT_PASSED;
		}
		BinWatchConfig.Discord d = config.discord;
		if (!d.enabled) {
			return OfferResult.DISABLED;
		}
		String url = webhookUrl.get();
		if (url == null || url.isBlank()) {
			return OfferResult.NO_WEBHOOK;
		}
		long now = clock.millis();
		try {
			if (c.normKey() != null && d.itemCooldownSeconds > 0) {
				Long last = store.lastAlertForKey(c.normKey());
				if (last != null && now - last < d.itemCooldownSeconds * 1000L) {
					return OfferResult.COOLDOWN;
				}
			}
			Records.Alert row = new Records.Alert(c.auctionUuid(), c.normKey(), AlertStatus.QUEUED, now, null, 0, null,
					c.itemName(), c.price(), c.estimatedNetProfit(), "queued");
			if (!store.tryCreateAlert(row)) {
				return OfferResult.DUPLICATE;
			}
		} catch (SQLException e) {
			BwLog.throttled(BwLog.Level.ERROR, "db-alert", "Could not persist alert state; alert not queued", e);
			return OfferResult.ERROR;
		}
		queue.add(new Pending(c, now));
		while (queue.size() > d.queueCapacity) {
			Pending lowest = null;
			for (Pending p : queue) {
				if (lowest == null || p.candidate().estimatedNetProfit() < lowest.candidate().estimatedNetProfit()) {
					lowest = p;
				}
			}
			if (lowest == null || !queue.remove(lowest)) {
				break;
			}
			dropped.incrementAndGet();
			mark(lowest.candidate(), AlertStatus.DROPPED, 0, null, "dropped: queue full (capacity " + d.queueCapacity + ")");
			if (lowest.candidate() == c) {
				return OfferResult.QUEUE_FULL;
			}
		}
		BwLog.info("Alert queued for " + c.auctionUuid() + " " + c.itemName() + " (est. net " + c.estimatedNetProfit() + ")");
		return OfferResult.QUEUED;
	}

	private void loop() {
		while (running) {
			try {
				processNext(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (RuntimeException e) {
				BwLog.throttled(BwLog.Level.ERROR, "discord-loop", "Discord dispatcher error", e);
			}
		}
	}

	/** Takes one alert (waiting up to {@code waitMs}) and attempts delivery. Returns true if one was processed. */
	public boolean processNext(long waitMs) throws InterruptedException {
		Pending p = queue.poll(waitMs, TimeUnit.MILLISECONDS);
		if (p == null) {
			return false;
		}
		BinWatchConfig.Discord d = config.discord;
		FlipCandidate c = p.candidate();
		if (clock.millis() - p.queuedAtMs() > d.maxQueuedAgeSeconds * 1000L) {
			mark(c, AlertStatus.EXPIRED, 0, null, "expired in queue after " + d.maxQueuedAgeSeconds + " s");
			dropped.incrementAndGet();
			return true;
		}
		try {
			if (store.countDeliveredSince(clock.millis() - 3_600_000L) >= d.maxAlertsPerHour) {
				mark(c, AlertStatus.DROPPED, 0, null, "dropped: hourly alert limit " + d.maxAlertsPerHour + " reached");
				dropped.incrementAndGet();
				return true;
			}
		} catch (SQLException e) {
			BwLog.throttled(BwLog.Level.ERROR, "db-alert-count", "Could not count alerts", e);
		}
		waitForMinuteBudget(d.maxAlertsPerMinute);

		AuctionCollector.PublishEstimate pub = c.snapshotLastUpdatedMs() == null ? null
				: publishEstimates.apply(c.snapshotLastUpdatedMs());
		JsonObject payload = FlipEmbed.build(c, FlipEmbed.Style.of(d), clock.millis(), config.api.clockSkewToleranceMs,
				serverClockOffsetMs.getAsLong(), pub);
		int attempts = 0;
		int rateLimitWaits = 0;
		while (true) {
			waitForDiscordRateLimit();
			String url = webhookUrl.get();
			if (url == null || url.isBlank()) {
				mark(c, AlertStatus.FAILED, attempts, null, "webhook URL removed before delivery");
				failed.incrementAndGet();
				return true;
			}
			attempts++;
			long postStarted = clock.millis();
			WebhookClient.Result r = webhook.send(url, payload, Duration.ofSeconds(d.requestTimeoutSeconds), clock.millis());
			switch (r.outcome()) {
				case DELIVERED -> {
					long now = clock.millis();
					lastDeliveredMs = now;
					lastDeliveryError = null;
					delivered.incrementAndGet();
					mark(c, AlertStatus.SENT, attempts, now, r.detail());
					BwLog.info("Discord alert delivered for " + c.auctionUuid() + " (" + r.detail() + ")"
							+ (c.snapshotLastUpdatedMs() == null ? "" : "; latency " + AuctionCollector.secs(
									now + serverClockOffsetMs.getAsLong() - c.snapshotLastUpdatedMs())
									+ " after snapshot lastUpdated, " + AuctionCollector.secs(now - c.firstApiObservedMs())
									+ " after the page arrived")
							+ (pub == null ? "" : "; since Hypixel published (+/-" + pub.uncertaintyMs() + " ms): page +"
									+ (c.firstApiObservedMs() - pub.publishedLocalMs()) + " ms, queued +"
									+ (p.queuedAtMs() - pub.publishedLocalMs()) + " ms, POST sent +"
									+ (postStarted - pub.publishedLocalMs()) + " ms, delivered +"
									+ (now - pub.publishedLocalMs()) + " ms (POST took " + (now - postStarted) + " ms)"));
					return true;
				}
				case RATE_LIMITED -> {
					rateLimited.incrementAndGet();
					discordRateLimitedUntilMs = clock.millis() + r.retryAfterMs();
					BwLog.throttled(BwLog.Level.WARN, "discord-429", "Discord rate limited; waiting " + r.retryAfterMs() + " ms", null);
					attempts--;
					if (++rateLimitWaits > 5) {
						mark(c, AlertStatus.FAILED, attempts, null, "repeatedly rate limited by Discord");
						failed.incrementAndGet();
						return true;
					}
				}
				case RETRYABLE_ERROR -> {
					lastDeliveryError = r.detail();
					if (attempts >= d.maxSendAttempts) {
						mark(c, AlertStatus.FAILED, attempts, null, "failed after " + attempts + " attempts: " + r.detail());
						failed.incrementAndGet();
						BwLog.throttled(BwLog.Level.WARN, "discord-fail", "Discord delivery failed: " + r.detail(), null);
						return true;
					}
					sleeper.sleep(Math.min(30_000, 1000L << attempts));
				}
				case PERMANENT_ERROR -> {
					lastDeliveryError = r.detail();
					mark(c, AlertStatus.FAILED, attempts, null, r.detail());
					failed.incrementAndGet();
					BwLog.throttled(BwLog.Level.WARN, "discord-fail", "Discord rejected alert: " + r.detail(), null);
					return true;
				}
			}
		}
	}

	private void waitForMinuteBudget(int maxPerMinute) throws InterruptedException {
		while (true) {
			long now = clock.millis();
			long waitMs;
			synchronized (sentLastMinute) {
				while (!sentLastMinute.isEmpty() && now - sentLastMinute.peekFirst() >= 60_000) {
					sentLastMinute.pollFirst();
				}
				if (sentLastMinute.size() < maxPerMinute) {
					// Reserve the slot now so concurrent senders cannot both take the last one.
					sentLastMinute.addLast(now);
					return;
				}
				waitMs = 60_000 - (now - sentLastMinute.peekFirst());
			}
			sleeper.sleep(Math.max(50, waitMs));
		}
	}

	private void waitForDiscordRateLimit() throws InterruptedException {
		long wait = discordRateLimitedUntilMs - clock.millis();
		if (wait > 0) {
			sleeper.sleep(wait);
		}
	}

	/** Sends the webhook test message asynchronously. Never called automatically. */
	public CompletableFuture<WebhookClient.Result> sendTest() {
		return sendTestPayload(false);
	}

	/** Sends a sample alert marked [TEST DATA] so the embed layout can be checked. Never called automatically. */
	public CompletableFuture<WebhookClient.Result> sendPreview() {
		return sendTestPayload(true);
	}

	private CompletableFuture<WebhookClient.Result> sendTestPayload(boolean preview) {
		return CompletableFuture.supplyAsync(() -> {
			String url = webhookUrl.get();
			if (url == null || url.isBlank()) {
				return new WebhookClient.Result(WebhookClient.Outcome.PERMANENT_ERROR, 0, 0,
						"no webhook URL configured in secrets.json");
			}
			long wait = discordRateLimitedUntilMs - clock.millis();
			if (wait > 0) {
				return new WebhookClient.Result(WebhookClient.Outcome.RATE_LIMITED, 429, wait,
						"Discord rate limit active; try again in " + (wait / 1000 + 1) + " s");
			}
			JsonObject payload = preview ? FlipEmbed.samplePayload(FlipEmbed.Style.of(config.discord), clock.millis())
					: FlipEmbed.testPayload(clock.millis());
			WebhookClient.Result r = webhook.send(url, payload, Duration.ofSeconds(config.discord.requestTimeoutSeconds), clock.millis());
			if (r.outcome() == WebhookClient.Outcome.RATE_LIMITED) {
				discordRateLimitedUntilMs = clock.millis() + r.retryAfterMs();
			}
			BwLog.info((preview ? "Webhook preview: " : "Webhook test: ") + r.outcome() + " (" + r.detail() + ")");
			return r;
		});
	}

	private void mark(FlipCandidate c, AlertStatus status, int attempts, Long deliveredMs, String detail) {
		try {
			store.updateAlert(c.auctionUuid(), status, clock.millis(), attempts, deliveredMs, detail);
		} catch (SQLException e) {
			BwLog.throttled(BwLog.Level.ERROR, "db-alert-update", "Could not update alert state", e);
		}
	}

	public int queued() {
		return queue.size();
	}

	public String lastDeliveryError() {
		return lastDeliveryError;
	}

	public long lastDeliveredMs() {
		return lastDeliveredMs;
	}
}
