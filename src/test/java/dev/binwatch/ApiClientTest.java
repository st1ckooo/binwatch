package dev.binwatch;

import dev.binwatch.api.ApiException;
import dev.binwatch.api.AuctionApiClient;
import dev.binwatch.api.AuctionPage;
import dev.binwatch.api.ScriptedTransport;
import dev.binwatch.service.SyntheticData;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiClientTest {
	static final long NOW = 1_793_310_186_275L;
	final MutableClock clock = new MutableClock(NOW);
	final List<Long> sleeps = new ArrayList<>();

	AuctionApiClient client(ScriptedTransport t, int retries) {
		return new AuctionApiClient(t, URI.create("https://api.example.invalid"), clock, ms -> {
			sleeps.add(ms);
			clock.advance(ms);
		}, new Random(42), new AuctionApiClient.RetryPolicy(retries, 1000, 30_000, Duration.ofSeconds(5)));
	}

	String page(int p, int total) {
		return SyntheticData.pageJson(p, total, NOW - 1000, List.of(SyntheticData.Auction.bin("X", 100, NOW - 5000)));
	}

	@Test
	void parsesValidPage() throws Exception {
		ScriptedTransport t = new ScriptedTransport().enqueue("page=0", () -> ScriptedTransport.ok(page(0, 3)));
		AuctionPage p = client(t, 0).fetchPage(0);
		assertEquals(0, p.page());
		assertEquals(3, p.totalPages());
		assertEquals(NOW - 1000, p.lastUpdatedMs());
		assertEquals(1, p.auctions().size());
		assertEquals(NOW, p.receivedAtMs());
		assertTrue(p.auctions().get(0).bin());
	}

	@Test
	void retriesTimeoutThenSucceedsWithBackoff() throws Exception {
		ScriptedTransport t = new ScriptedTransport().enqueue("page=0",
				() -> { throw new HttpTimeoutException("timeout"); },
				() -> ScriptedTransport.status(500, Map.of(), "oops"),
				() -> ScriptedTransport.ok(page(0, 1)));
		AuctionApiClient c = client(t, 3);
		AuctionPage p = c.fetchPage(0);
		assertEquals(0, p.page());
		assertEquals(2, c.retries.get());
		assertEquals(2, sleeps.size());
		// Full jitter in [d/2, d]: attempt 0 -> [500,1000], attempt 1 -> [1000,2000]
		assertTrue(sleeps.get(0) >= 500 && sleeps.get(0) <= 1000, "first backoff " + sleeps.get(0));
		assertTrue(sleeps.get(1) >= 1000 && sleeps.get(1) <= 2000, "second backoff " + sleeps.get(1));
	}

	@Test
	void retriesAreBounded() {
		ScriptedTransport t = new ScriptedTransport().whenEmpty("page=0", u -> ScriptedTransport.status(502, Map.of(), ""));
		AuctionApiClient c = client(t, 2);
		ApiException e = assertThrows(ApiException.class, () -> c.fetchPage(0));
		assertEquals(ApiException.Kind.HTTP, e.kind());
		assertEquals(3, t.requests.size());
		assertEquals(1, c.failedRequests.get());
	}

	@Test
	void malformedJsonIsRetriedThenReported() {
		ScriptedTransport t = new ScriptedTransport().whenEmpty("page=0", u -> ScriptedTransport.ok("{not json"));
		ApiException e = assertThrows(ApiException.class, () -> client(t, 1).fetchPage(0));
		assertEquals(ApiException.Kind.MALFORMED, e.kind());
		assertEquals(2, t.requests.size());
	}

	@Test
	void missingFieldsAndWrongPageAreMalformed() {
		ScriptedTransport t = new ScriptedTransport()
				.enqueue("page=0", () -> ScriptedTransport.ok("{\"success\":true,\"page\":0}"))
				.enqueue("page=1", () -> ScriptedTransport.ok(page(2, 3)));
		AuctionApiClient c = client(t, 0);
		assertEquals(ApiException.Kind.MALFORMED, assertThrows(ApiException.class, () -> c.fetchPage(0)).kind());
		assertEquals(ApiException.Kind.MALFORMED, assertThrows(ApiException.class, () -> c.fetchPage(1)).kind());
	}

	@Test
	void malformedAuctionEntriesAreSkippedNotFatal() throws Exception {
		String body = "{\"success\":true,\"page\":0,\"totalPages\":1,\"totalAuctions\":2,\"lastUpdated\":" + NOW
				+ ",\"auctions\":[{\"uuid\":\"nope\"},{\"uuid\":\"5e6ae994da8944b0ae26c63e4f023589\",\"starting_bid\":5,\"bin\":true}]}";
		ScriptedTransport t = new ScriptedTransport().enqueue("page=0", () -> ScriptedTransport.ok(body));
		AuctionPage p = client(t, 0).fetchPage(0);
		assertEquals(1, p.auctions().size());
		assertEquals(1, p.skipped());
	}

	@Test
	void successFalseIsApiError() {
		ScriptedTransport t = new ScriptedTransport().enqueue("page=0",
				() -> ScriptedTransport.ok("{\"success\":false,\"cause\":\"Invalid page\"}"));
		ApiException e = assertThrows(ApiException.class, () -> client(t, 3).fetchPage(0));
		assertEquals(ApiException.Kind.API_ERROR, e.kind());
		assertEquals(1, t.requests.size(), "non-retryable errors are not retried");
	}

	@Test
	void honoursRetryAfterOn429() throws Exception {
		ScriptedTransport t = new ScriptedTransport().enqueue("page=0",
				() -> ScriptedTransport.status(429, Map.of("Retry-After", List.of("7")), "{}"),
				() -> ScriptedTransport.ok(page(0, 1)));
		AuctionApiClient c = client(t, 3);
		c.fetchPage(0);
		assertEquals(1, c.rateLimitedResponses.get());
		long totalSlept = sleeps.stream().mapToLong(Long::longValue).sum();
		assertTrue(totalSlept >= 7000, "must wait at least Retry-After, slept " + totalSlept);
	}

	@Test
	void hugeRetryAfterStopsInsteadOfBlocking() {
		ScriptedTransport t = new ScriptedTransport().enqueue("page=0",
				() -> ScriptedTransport.status(429, Map.of("Retry-After", List.of("3600")), "{}"));
		AuctionApiClient c = client(t, 3);
		ApiException e = assertThrows(ApiException.class, () -> c.fetchPage(0));
		assertEquals(429, e.httpStatus());
		assertTrue(c.rateLimitedUntilMs() > NOW);
	}

	@Test
	void missingPageAndNotPopulated() {
		ScriptedTransport t = new ScriptedTransport()
				.enqueue("page=5", () -> ScriptedTransport.status(404, Map.of(), "{}"))
				.enqueue("page=0", () -> ScriptedTransport.status(503, Map.of(), "{}"), () -> ScriptedTransport.ok(page(0, 1)));
		AuctionApiClient c = client(t, 2);
		assertEquals(ApiException.Kind.MISSING_PAGE, assertThrows(ApiException.class, () -> c.fetchPage(5)).kind());
		try {
			assertEquals(0, c.fetchPage(0).page());
		} catch (ApiException e) {
			throw new AssertionError(e);
		}
	}

	@Test
	void retryAfterParsing() {
		assertEquals(7000, AuctionApiClient.parseRetryAfterMs("7", NOW));
		assertEquals(1500, AuctionApiClient.parseRetryAfterMs("1.5", NOW));
		assertEquals(0, AuctionApiClient.parseRetryAfterMs("garbage", NOW));
		assertEquals(0, AuctionApiClient.parseRetryAfterMs(null, NOW));
		long date = AuctionApiClient.parseRetryAfterMs("Thu, 01 Jan 2099 00:00:00 GMT", NOW);
		assertTrue(date > 0);
	}
}
