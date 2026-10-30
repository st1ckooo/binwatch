package dev.binwatch.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonIOException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.binwatch.log.BwLog;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client for the documented, key-less public endpoints {@code /v2/skyblock/auctions} and
 * {@code /v2/skyblock/auctions_ended}. Applies bounded retries with exponential backoff and full jitter, honours
 * HTTP 429 and {@code Retry-After}, and validates every response before returning it.
 */
public final class AuctionApiClient {
	public static final URI DEFAULT_BASE = URI.create("https://api.hypixel.net");
	/** Never wait longer than this for a single Retry-After instruction; give up the request instead. */
	static final long MAX_RETRY_AFTER_MS = 300_000;

	public interface Sleeper {
		void sleep(long ms) throws InterruptedException;
	}

	public record RetryPolicy(int maxRetries, long baseDelayMs, long maxDelayMs, Duration requestTimeout) {
	}

	public record EndedResult(long lastUpdatedMs, List<EndedAuction> auctions, int skipped) {
	}

	/**
	 * Result of a conditional page-0 request; {@code page} is null when the server answered 304 Not Modified.
	 * {@code requestStartedMs} and {@code headersAtMs} are local times the request was sent and its headers arrived.
	 */
	public record Probe(AuctionPage page, Long serverDateMs, long receivedAtMs, long requestStartedMs, long headersAtMs) {
		public boolean notModified() {
			return page == null;
		}
	}

	private final HttpTransport http;
	private final URI base;
	private final Clock clock;
	private final Sleeper sleeper;
	private final Random random;
	private volatile RetryPolicy policy;
	private final AtomicLong rateLimitedUntilMs = new AtomicLong();

	public final AtomicLong requests = new AtomicLong();
	public final AtomicLong retries = new AtomicLong();
	public final AtomicLong rateLimitedResponses = new AtomicLong();
	public final AtomicLong failedRequests = new AtomicLong();

	public AuctionApiClient(HttpTransport http, URI base, Clock clock, Sleeper sleeper, Random random, RetryPolicy policy) {
		this.http = http;
		this.base = base;
		this.clock = clock;
		this.sleeper = sleeper;
		this.random = random;
		this.policy = policy;
	}

	public void setPolicy(RetryPolicy policy) {
		this.policy = policy;
	}

	public long rateLimitedUntilMs() {
		return rateLimitedUntilMs.get();
	}

	public AuctionPage fetchPage(int page) throws ApiException {
		URI uri = base.resolve("/v2/skyblock/auctions?page=" + page);
		return withRetries("auctions page " + page, () -> parsePage(page, getChecked(uri)));
	}

	/**
	 * One conditional request for page 0 ({@code If-Modified-Since}), without retries: the caller probes again a
	 * moment later anyway. An unchanged page costs a header-only 304 response instead of the full page.
	 */
	public Probe probePage0(String ifModifiedSince) throws ApiException {
		return probePage0(ifModifiedSince, null);
	}

	/**
	 * Receives the first auctions of page 0 while the rest of the page is still downloading. Hypixel lists the newest
	 * auctions first (observed live: every auction started in the last 60 s within the first ~6% of page 0).
	 *
	 * @param requestStartedMs local time the request was sent
	 */
	public interface PageHead {
		void accept(long lastUpdatedMs, List<ApiAuction> auctions, long atMs, long requestStartedMs);
	}

	/** The head of page 0 ends after this many consecutive auctions that started before the previous snapshot. */
	static final int HEAD_OLD_RUN = 20;
	static final long HEAD_OLD_AGE_MS = 120_000;
	static final int HEAD_MAX = 400;

	/**
	 * As {@link #probePage0(String)}, parsing the page while it downloads; {@code head} (if not null) is called on this
	 * thread with the leading, newest auctions as soon as they have been read.
	 */
	public Probe probePage0(String ifModifiedSince, PageHead head) throws ApiException {
		waitForRateLimit();
		URI uri = base.resolve("/v2/skyblock/auctions?page=0");
		Map<String, String> headers = ifModifiedSince == null ? Map.of() : Map.of("If-Modified-Since", ifModifiedSince);
		JsonObject[] parsed = {null};
		RuntimeException[] parseError = {null};
		long requestStarted = clock.millis();
		Received r = checked(headers, true, requestStarted, () -> http.getStreaming(uri, policy.requestTimeout(), headers,
				(res, in) -> {
					if (res.status() != 200) {
						return new HttpTransport.Response(res.status(), res.headers(),
								new String(in.readAllBytes(), StandardCharsets.UTF_8), res.headersAtMs());
					}
					try {
						parsed[0] = readPage(new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8)), head,
								requestStarted);
					} catch (com.google.gson.stream.MalformedJsonException e) {
						parseError[0] = new IllegalStateException(e);
					} catch (JsonIOException e) {
						if (e.getCause() instanceof IOException io) {
							throw io;
						}
						parseError[0] = e;
					} catch (RuntimeException e) {
						parseError[0] = e;
					}
					return res;
				}));
		Long date = parseHttpDate(r.response().header("Date"));
		if (r.response().status() == 304) {
			return new Probe(null, date, r.receivedAtMs(), r.requestStartedMs(), r.headersAtMs());
		}
		if (parsed[0] == null) {
			throw new ApiException(ApiException.Kind.MALFORMED, 200, "response is not valid JSON", parseError[0]);
		}
		return new Probe(parsePage(0, checkSuccess(parsed[0]), r), date, r.receivedAtMs(), r.requestStartedMs(),
				r.headersAtMs());
	}

	/**
	 * Streams a page object into a {@link JsonObject}, handing the head of its auctions array to {@code head} once it
	 * is complete. Fields before {@code auctions} (success, page, lastUpdated) have been read by then.
	 */
	private JsonObject readPage(JsonReader jr, PageHead head, long requestStarted) throws IOException {
		JsonObject o = new JsonObject();
		jr.beginObject();
		while (jr.hasNext()) {
			String name = jr.nextName();
			if (!name.equals("auctions") || jr.peek() != JsonToken.BEGIN_ARRAY) {
				o.add(name, JsonParser.parseReader(jr));
				continue;
			}
			Long lu = longField(o, "lastUpdated");
			Long page = longField(o, "page");
			JsonElement success = o.get("success");
			boolean pending = head != null && lu != null && page != null && page == 0
					&& success != null && success.isJsonPrimitive() && success.getAsBoolean();
			List<ApiAuction> first = new ArrayList<>();
			int oldRun = 0;
			JsonArray arr = new JsonArray();
			jr.beginArray();
			while (jr.hasNext()) {
				JsonElement el = JsonParser.parseReader(jr);
				arr.add(el);
				if (!pending) {
					continue;
				}
				ApiAuction a = el.isJsonObject() ? parseAuction(el.getAsJsonObject()) : null;
				if (a != null) {
					first.add(a);
					oldRun = a.startMs() != null && a.startMs() < lu - HEAD_OLD_AGE_MS ? oldRun + 1 : 0;
				}
				if (oldRun >= HEAD_OLD_RUN || first.size() >= HEAD_MAX) {
					pending = false;
					head.accept(lu, List.copyOf(first), clock.millis(), requestStarted);
				}
			}
			jr.endArray();
			if (pending) {
				head.accept(lu, List.copyOf(first), clock.millis(), requestStarted);
			}
			o.add(name, arr);
		}
		jr.endObject();
		return o;
	}

	/** Waits on the client's sleeper (a fake clock in tests). */
	public void pause(long ms) throws ApiException {
		sleep(ms);
	}

	public EndedResult fetchEnded() throws ApiException {
		URI uri = base.resolve("/v2/skyblock/auctions_ended");
		return withRetries("auctions_ended", () -> parseEnded(getChecked(uri)));
	}

	private interface Attempt<T> {
		T run() throws ApiException;
	}

	private <T> T withRetries(String what, Attempt<T> attempt) throws ApiException {
		RetryPolicy p = policy;
		for (int i = 0; ; i++) {
			waitForRateLimit();
			try {
				T result = attempt.run();
				BwLog.resetThrottle("api-fail-" + what);
				return result;
			} catch (ApiException e) {
				if (!e.retryable() || i >= p.maxRetries()) {
					failedRequests.incrementAndGet();
					throw e;
				}
				long delay = backoffDelay(i, p);
				retries.incrementAndGet();
				BwLog.throttled(BwLog.Level.WARN, "api-fail-" + what, "API " + what + " failed (" + e.kind() + ": "
						+ e.getMessage() + "), retry " + (i + 1) + "/" + p.maxRetries() + " in " + delay + " ms", null);
				sleep(delay);
			}
		}
	}

	/** Exponential backoff with full jitter in [d/2, d], where d = min(max, base * 2^attempt). */
	public long backoffDelay(int attempt, RetryPolicy p) {
		long exp = p.baseDelayMs() << Math.min(attempt, 20);
		long capped = Math.max(1, Math.min(p.maxDelayMs(), exp));
		long half = capped / 2;
		return half + (long) (random.nextDouble() * (capped - half + 1));
	}

	private void waitForRateLimit() throws ApiException {
		long wait = rateLimitedUntilMs.get() - clock.millis();
		if (wait > 0) {
			sleep(wait);
		}
	}

	private void sleep(long ms) throws ApiException {
		try {
			sleeper.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ApiException(ApiException.Kind.INTERRUPTED, "interrupted");
		}
	}

	private record Received(HttpTransport.Response response, long requestStartedMs, long receivedAtMs, long headersAtMs) {
	}

	private Received getChecked(URI uri) throws ApiException {
		return getChecked(uri, Map.of());
	}

	private interface Request {
		HttpTransport.Response send() throws IOException, InterruptedException;
	}

	private Received getChecked(URI uri, Map<String, String> headers) throws ApiException {
		return checked(headers, false, clock.millis(),
				() -> headers.isEmpty() ? http.get(uri, policy.requestTimeout()) : http.get(uri, policy.requestTimeout(), headers));
	}

	/** Sends a request and maps transport failures and HTTP errors; {@code streamed} bodies were consumed already. */
	private Received checked(Map<String, String> headers, boolean streamed, long requestStarted, Request request)
			throws ApiException {
		HttpTransport.Response res;
		requests.incrementAndGet();
		try {
			res = request.send();
		} catch (HttpTimeoutException e) {
			throw new ApiException(ApiException.Kind.TIMEOUT, 0, "request timed out", e);
		} catch (IOException e) {
			throw new ApiException(ApiException.Kind.IO, 0, e.getClass().getSimpleName() + ": " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ApiException(ApiException.Kind.INTERRUPTED, "interrupted");
		}
		long receivedAt = clock.millis();
		long headersAt = res.headersAtMs() > 0 ? res.headersAtMs() : receivedAt;
		int status = res.status();
		if (status == 304 && !headers.isEmpty()) {
			return new Received(res, requestStarted, receivedAt, headersAt);
		}
		if (status == 429) {
			rateLimitedResponses.incrementAndGet();
			long retryAfter = parseRetryAfterMs(res.header("Retry-After"), receivedAt);
			if (retryAfter > MAX_RETRY_AFTER_MS) {
				rateLimitedUntilMs.accumulateAndGet(receivedAt + MAX_RETRY_AFTER_MS, Math::max);
				throw new ApiException(ApiException.Kind.API_ERROR, 429, "rate limited with Retry-After of "
						+ retryAfter / 1000 + " s; pausing", null);
			}
			if (retryAfter > 0) {
				rateLimitedUntilMs.accumulateAndGet(receivedAt + retryAfter, Math::max);
			}
			throw new ApiException(ApiException.Kind.RATE_LIMITED, 429, "HTTP 429 rate limited"
					+ (retryAfter > 0 ? " (Retry-After " + retryAfter + " ms)" : ""), null);
		}
		if (status == 404 || status == 422) {
			throw new ApiException(ApiException.Kind.MISSING_PAGE, status, "page does not exist (HTTP " + status + ")", null);
		}
		if (status == 503) {
			throw new ApiException(ApiException.Kind.NOT_POPULATED, status, "data not yet populated (HTTP 503)", null);
		}
		if (status < 200 || status >= 300) {
			throw new ApiException(ApiException.Kind.HTTP, status, "HTTP " + status, null);
		}
		if (!streamed && (res.body() == null || res.body().isEmpty())) {
			throw new ApiException(ApiException.Kind.MALFORMED, status, "empty response body", null);
		}
		return new Received(res, requestStarted, receivedAt, headersAt);
	}

	/** Parses {@code Retry-After} as delta-seconds or an HTTP date. Returns 0 when absent or unparseable. */
	public static long parseRetryAfterMs(String value, long nowMs) {
		if (value == null || value.isBlank()) {
			return 0;
		}
		String v = value.trim();
		try {
			double seconds = Double.parseDouble(v);
			return seconds <= 0 ? 0 : (long) Math.ceil(seconds * 1000);
		} catch (NumberFormatException ignored) {
			// Fall through to HTTP-date parsing.
		}
		try {
			long at = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
			return Math.max(0, at - nowMs);
		} catch (RuntimeException e) {
			return 0;
		}
	}

	private static Long parseHttpDate(String value) {
		if (value == null) {
			return null;
		}
		try {
			return ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static JsonObject parseObject(Received r) throws ApiException {
		JsonObject o;
		try {
			JsonElement el = JsonParser.parseString(r.response().body());
			if (!el.isJsonObject()) {
				throw new ApiException(ApiException.Kind.MALFORMED, "response is not a JSON object");
			}
			o = el.getAsJsonObject();
		} catch (RuntimeException e) {
			throw new ApiException(ApiException.Kind.MALFORMED, 200, "response is not valid JSON", e);
		}
		return checkSuccess(o);
	}

	private static JsonObject checkSuccess(JsonObject o) throws ApiException {
		JsonElement success = o.get("success");
		if (success == null || !success.isJsonPrimitive() || !success.getAsBoolean()) {
			String cause = o.has("cause") && o.get("cause").isJsonPrimitive() ? o.get("cause").getAsString() : "success=false";
			throw new ApiException(ApiException.Kind.API_ERROR, 200, "API reported failure: " + cause, null);
		}
		return o;
	}

	AuctionPage parsePage(int requestedPage, Received r) throws ApiException {
		return parsePage(requestedPage, parseObject(r), r);
	}

	private AuctionPage parsePage(int requestedPage, JsonObject o, Received r) throws ApiException {
		Long page = longField(o, "page");
		Long totalPages = longField(o, "totalPages");
		Long totalAuctions = longField(o, "totalAuctions");
		Long lastUpdated = longField(o, "lastUpdated");
		JsonElement arr = o.get("auctions");
		if (page == null || totalPages == null || lastUpdated == null || arr == null || !arr.isJsonArray()) {
			throw new ApiException(ApiException.Kind.MALFORMED, "page response is missing page/totalPages/lastUpdated/auctions");
		}
		if (page != requestedPage) {
			throw new ApiException(ApiException.Kind.MALFORMED, "requested page " + requestedPage + " but got page " + page);
		}
		if (totalPages < 0 || totalPages > 10_000) {
			throw new ApiException(ApiException.Kind.MALFORMED, "implausible totalPages " + totalPages);
		}
		List<ApiAuction> auctions = new ArrayList<>();
		int skipped = 0;
		for (JsonElement el : arr.getAsJsonArray()) {
			ApiAuction a = el.isJsonObject() ? parseAuction(el.getAsJsonObject()) : null;
			if (a == null) {
				skipped++;
			} else {
				auctions.add(a);
			}
		}
		Integer age = null;
		String ageHeader = r.response().header("Age");
		if (ageHeader != null) {
			try {
				age = Integer.parseInt(ageHeader.trim());
			} catch (NumberFormatException ignored) {
				// Diagnostic only.
			}
		}
		return new AuctionPage(page.intValue(), totalPages.intValue(), totalAuctions == null ? -1 : totalAuctions.intValue(),
				lastUpdated, List.copyOf(auctions), r.receivedAtMs(), age, parseHttpDate(r.response().header("Date")), skipped,
				r.response().header("Last-Modified"));
	}

	/** Returns null when the entry lacks a valid UUID; other missing fields are kept as null/defaults. */
	static ApiAuction parseAuction(JsonObject a) {
		UUID uuid = parseHexUuid(stringField(a, "uuid"));
		if (uuid == null) {
			return null;
		}
		Long startingBid = longField(a, "starting_bid");
		Long highest = longField(a, "highest_bid_amount");
		return new ApiAuction(
				uuid,
				stringField(a, "auctioneer"),
				longField(a, "start"),
				longField(a, "end"),
				stringField(a, "item_name"),
				stringField(a, "tier"),
				stringField(a, "category"),
				startingBid == null ? -1 : startingBid,
				highest == null ? 0 : highest,
				boolField(a, "bin"),
				boolField(a, "claimed"),
				itemBytes(a.get("item_bytes")),
				stringField(a, "item_uuid"),
				longField(a, "last_updated"));
	}

	EndedResult parseEnded(Received r) throws ApiException {
		JsonObject o = parseObject(r);
		Long lastUpdated = longField(o, "lastUpdated");
		JsonElement arr = o.get("auctions");
		if (lastUpdated == null || arr == null || !arr.isJsonArray()) {
			throw new ApiException(ApiException.Kind.MALFORMED, "auctions_ended response is missing lastUpdated/auctions");
		}
		List<EndedAuction> list = new ArrayList<>();
		int skipped = 0;
		for (JsonElement el : arr.getAsJsonArray()) {
			if (!el.isJsonObject()) {
				skipped++;
				continue;
			}
			JsonObject e = el.getAsJsonObject();
			UUID id = parseHexUuid(stringField(e, "auction_id"));
			Long ts = longField(e, "timestamp");
			Long price = longField(e, "price");
			String bytes = itemBytes(e.get("item_bytes"));
			if (id == null || ts == null || price == null || price <= 0 || bytes == null) {
				skipped++;
				continue;
			}
			list.add(new EndedAuction(id, ts, price, boolField(e, "bin"), bytes));
		}
		return new EndedResult(lastUpdated, List.copyOf(list), skipped);
	}

	/** item_bytes is documented as a string; older responses wrapped it as {type, data}. Accept both. */
	private static String itemBytes(JsonElement el) {
		if (el == null || el.isJsonNull()) {
			return null;
		}
		if (el.isJsonPrimitive()) {
			return el.getAsString();
		}
		if (el.isJsonObject() && el.getAsJsonObject().has("data")) {
			JsonElement d = el.getAsJsonObject().get("data");
			return d.isJsonPrimitive() ? d.getAsString() : null;
		}
		return null;
	}

	public static UUID parseHexUuid(String s) {
		if (s == null) {
			return null;
		}
		String hex = s.replace("-", "").trim();
		if (hex.length() != 32) {
			return null;
		}
		try {
			long hi = Long.parseUnsignedLong(hex.substring(0, 16), 16);
			long lo = Long.parseUnsignedLong(hex.substring(16), 16);
			return new UUID(hi, lo);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	public static String hexUuid(UUID uuid) {
		return uuid.toString().replace("-", "");
	}

	private static String stringField(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	private static Long longField(JsonObject o, String key) {
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonPrimitive()) {
			return null;
		}
		try {
			return e.getAsJsonPrimitive().isNumber() ? e.getAsLong() : Long.parseLong(e.getAsString().trim());
		} catch (RuntimeException ex) {
			return null;
		}
	}

	private static boolean boolField(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
	}

	@SuppressWarnings("unused")
	private static JsonArray emptyArray() {
		return new JsonArray();
	}
}
