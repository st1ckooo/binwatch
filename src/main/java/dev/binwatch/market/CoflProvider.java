package dev.binwatch.market;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.binwatch.api.AuctionApiClient;
import dev.binwatch.api.HttpTransport;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * SkyCofl ({@code sky.coflnet.com}) price estimate for one exact item: {@code POST /api/price/nbt} with the auction's
 * item NBT returns the lowest BIN, median sale, volume and fast-sell price for items with the same relevant
 * attributes. Requests stay inside SkyCofl's published limits (100 per minute and 30 per 10 s per IP) without spacing
 * out a burst of flips from one snapshot, and a 429 pauses all requests for its Retry-After.
 */
public final class CoflProvider implements PriceProvider {
	/** SkyCofl allows 30 requests per 10 s per IP; bursts up to this many go out at once, so flips are not queued. */
	static final int BURST_LIMIT = 25;
	static final long BURST_WINDOW_MS = 10_000;

	private final HttpTransport http;
	private final URI endpoint;
	private final Clock clock;
	private final int maxPerMinute;
	private final Duration timeout;
	private final Deque<Long> sent = new ArrayDeque<>();
	private long blockedUntilMs;

	public CoflProvider(HttpTransport http, String baseUrl, Clock clock, int maxPerMinute, Duration timeout) {
		this.http = http;
		this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/api/price/nbt");
		this.clock = clock;
		this.maxPerMinute = maxPerMinute;
		this.timeout = timeout;
	}

	@Override
	public String name() {
		return "SkyCofl";
	}

	@Override
	public String attribution() {
		return "Prices provided by SkyCofl (sky.coflnet.com)";
	}

	@Override
	public MarketQuote quote(String itemBytes) throws MarketException {
		if (itemBytes == null || itemBytes.isBlank()) {
			throw new MarketException("auction has no item data");
		}
		reserveSlot();
		JsonObject body = new JsonObject();
		body.addProperty("fullInventoryNbt", itemBytes);
		HttpTransport.Response res;
		try {
			res = http.postJson(endpoint, body.toString(), timeout);
		} catch (IOException e) {
			throw new MarketException("SkyCofl request failed: " + e.getClass().getSimpleName(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new MarketException("interrupted", e);
		}
		long now = clock.millis();
		if (res.status() == 429) {
			long wait = Math.max(5_000, AuctionApiClient.parseRetryAfterMs(res.header("Retry-After"), now));
			synchronized (this) {
				blockedUntilMs = Math.max(blockedUntilMs, now + wait);
			}
			throw new MarketException("SkyCofl rate limit reached; pausing " + wait / 1000 + " s");
		}
		if (res.status() < 200 || res.status() >= 300) {
			throw new MarketException("SkyCofl answered HTTP " + res.status());
		}
		return parse(res.body(), now);
	}

	/** Waits for the 10-second and per-minute budgets; fails fast while a 429 pause is active. */
	private void reserveSlot() throws MarketException {
		while (true) {
			long waitMs;
			synchronized (this) {
				long now = clock.millis();
				if (now < blockedUntilMs) {
					throw new MarketException("SkyCofl rate limit pause for another " + (blockedUntilMs - now) / 1000 + " s");
				}
				while (!sent.isEmpty() && now - sent.peekFirst() >= 60_000) {
					sent.pollFirst();
				}
				long burst = 0;
				int inWindow = 0;
				for (var it = sent.descendingIterator(); it.hasNext(); ) {
					long t = it.next();
					if (now - t >= BURST_WINDOW_MS) {
						break;
					}
					if (++inWindow == BURST_LIMIT) {
						burst = BURST_WINDOW_MS - (now - t);
					}
				}
				long budget = sent.size() < maxPerMinute ? 0 : 60_000 - (now - sent.peekFirst());
				waitMs = Math.max(burst, budget);
				if (waitMs <= 0) {
					sent.addLast(now);
					return;
				}
				if (waitMs > timeout.toMillis()) {
					throw new MarketException("request budget used up; next slot in " + waitMs / 1000 + " s");
				}
			}
			try {
				Thread.sleep(waitMs);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new MarketException("interrupted", e);
			}
		}
	}

	static MarketQuote parse(String body, long now) throws MarketException {
		JsonObject o;
		try {
			JsonElement el = JsonParser.parseString(body == null ? "" : body);
			if (el.isJsonArray()) {
				JsonArray a = el.getAsJsonArray();
				if (a.isEmpty() || !a.get(0).isJsonObject()) {
					throw new MarketException("SkyCofl returned no estimate for this item");
				}
				o = a.get(0).getAsJsonObject();
			} else if (el.isJsonObject()) {
				o = el.getAsJsonObject();
			} else {
				throw new MarketException("SkyCofl reply is not an object");
			}
		} catch (RuntimeException e) {
			throw new MarketException("SkyCofl reply is not valid JSON", e);
		}
		long lbin = num(o, "lbin");
		long median = num(o, "median");
		if (lbin <= 0 && median <= 0) {
			throw new MarketException("SkyCofl has no price data for this item");
		}
		double volume = o.has("volume") && o.get("volume").isJsonPrimitive() ? o.get("volume").getAsDouble() : 0;
		return new MarketQuote(lbin, auctionPrefix(str(o, "lbinLink")), median, volume, num(o, "fastSell"),
				str(o, "itemKey"), now, "SkyCofl");
	}

	/** {@code https://sky.coflnet.com/a/d2017956a933420bb} → {@code d2017956a933420bb}. */
	static String auctionPrefix(String link) {
		if (link == null) {
			return null;
		}
		int i = link.lastIndexOf('/');
		String id = (i >= 0 ? link.substring(i + 1) : link).trim().toLowerCase(Locale.ROOT);
		return id.matches("[0-9a-f]{8,32}") ? id : null;
	}

	private static long num(JsonObject o, String k) {
		try {
			return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsLong() : 0;
		} catch (RuntimeException e) {
			return 0;
		}
	}

	private static String str(JsonObject o, String k) {
		return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
	}
}
