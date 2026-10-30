package dev.binwatch;

import dev.binwatch.api.AuctionApiClient;
import dev.binwatch.api.AuctionCollector;
import dev.binwatch.api.HttpTransport;
import dev.binwatch.api.SalesHistory;
import dev.binwatch.api.ScriptedTransport;
import dev.binwatch.api.SnapshotClock;
import dev.binwatch.api.SnapshotStatus;
import dev.binwatch.config.BinWatchConfig;
import dev.binwatch.service.SyntheticData;
import dev.binwatch.service.SyntheticData.Auction;
import dev.binwatch.store.ObservationStore;
import dev.binwatch.store.Records;
import dev.binwatch.value.PriceIndex;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotTimedPollingTest {
	static final long T0 = 1_793_310_000_000L;
	final MutableClock clock = new MutableClock(T0 + 10_000);
	final List<AuctionCollector.FirstSeen> firstSeen = new ArrayList<>();
	final List<AuctionCollector.SnapshotSummary> snapshots = new ArrayList<>();
	final List<List<UUID>> batches = new ArrayList<>();

	AuctionCollector collector(ScriptedTransport t, ObservationStore store) {
		BinWatchConfig cfg = new BinWatchConfig();
		cfg.api.fetchEndedAuctions = false;
		AuctionApiClient client = new AuctionApiClient(t, URI.create("https://api.example.invalid"), clock,
				ms -> clock.advance(ms), new Random(1), AuctionCollector.policyFrom(cfg.api));
		return new AuctionCollector(client, store, clock, cfg, new SalesHistory(50, 3_600_000L), new AuctionCollector.Listener() {
			@Override
			public void onFirstSeen(List<AuctionCollector.FirstSeen> fs, PriceIndex index) {
				firstSeen.addAll(fs);
				batches.add(fs.stream().map(f -> f.auction().uuid()).toList());
			}

			@Override
			public void onSnapshot(AuctionCollector.SnapshotSummary summary) {
				snapshots.add(summary);
			}
		});
	}

	/** Response stamped like the real CDN: Date (whole seconds, from the fake clock) and Last-Modified. */
	HttpTransport.Response page(int page, int total, long lastUpdated, List<Auction> auctions, String lastModified) {
		return ScriptedTransport.status(200, headers(lastModified), SyntheticData.pageJson(page, total, lastUpdated, auctions));
	}

	HttpTransport.Response notModified(String lastModified) {
		return ScriptedTransport.status(304, headers(lastModified), "");
	}

	Map<String, List<String>> headers(String lastModified) {
		String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(clock.millis()).atZone(ZoneOffset.UTC));
		return Map.of("Date", List.of(date), "Last-Modified", List.of(lastModified));
	}

	long countFor(UUID uuid) {
		return firstSeen.stream().filter(f -> f.auction().uuid().equals(uuid)).count();
	}

	@Test
	void clockLearnsIntervalOffsetAndPublishDelay() {
		SnapshotClock c = new SnapshotClock();
		assertTrue(c.behind(T0), "no snapshot yet: re-sync");
		// Date samples underestimate the offset by the sub-second part; the best sample wins.
		c.onDateSample(T0 + 9_000, T0 + 9_900);
		c.onDateSample(T0 + 10_000, T0 + 10_050);
		assertEquals(-50, c.offsetMs());

		c.onSnapshot(T0, T0 + 30_000, false);
		assertEquals(0, c.minPublishDelayMs(), "a plain poll says nothing about when the snapshot appeared");
		assertEquals(T0 + 60_000 + 50 - 2_000, c.wakeAtLocalMs(2_000));

		c.onSnapshot(T0 + 50_000, T0 + 59_050, true);
		assertEquals(58_000, c.avgIntervalMs(), "rolling average moves 20% toward the observed gap");
		assertEquals(9_000, c.minPublishDelayMs());
		assertEquals(T0 + 50_000 + 58_000 + 9_000, c.nextExpectedServerMs());

		// A gap of several intervals (missed snapshots) does not distort the average.
		c.onSnapshot(T0 + 290_000, T0 + 300_000, false);
		assertEquals(58_000, c.avgIntervalMs());

		assertFalse(c.behind(T0 + 300_000));
		assertTrue(c.behind(T0 + 290_000 + 58_000 + 9_000 + 58_000 + 1_000), "more than one interval overdue");

		// One skipped snapshot (a two-interval gap, seen live) is not a cadence step either.
		c.onSnapshot(T0 + 290_000 + 116_000, T0 + 415_000, false);
		assertEquals(58_000, c.avgIntervalMs());
		// A burst that gave up expects the snapshot after the missing one, until a new one arrives.
		long next = c.nextExpectedServerMs();
		c.onMissed();
		assertEquals(next + 58_000, c.nextExpectedServerMs());
		c.onSnapshot(T0 + 290_000 + 116_000 + 116_000, T0 + 531_000, false);
		assertEquals(T0 + 522_000 + 58_000 + 9_000, c.nextExpectedServerMs());
	}

	@Test
	void burstFindsSnapshotRetriesStalePageAndAlertsNewListingOnce() throws Exception {
		List<Auction> base = List.of(Auction.bin("ITEM_0", 1_000, T0 - 600_000), Auction.bin("ITEM_1", 2_000, T0 - 500_000));
		UUID fresh = UUID.randomUUID();
		List<Auction> next = new ArrayList<>(base);
		next.add(Auction.bin("ITEM_0", 900, T0 + 30_000).withUuid(fresh));
		long lu1 = T0 + 50_000;
		ScriptedTransport t = new ScriptedTransport()
				.enqueue("page=0",
						() -> page(0, 2, T0, base.subList(0, 1), "A"),
						() -> notModified("A"), () -> notModified("A"), () -> notModified("A"),
						() -> page(0, 2, lu1, List.of(next.get(0), next.get(2)), "B"),
						() -> notModified("B"),
						() -> page(0, 2, lu1 + 60_000, List.of(next.get(0), next.get(2)), "C"))
				.enqueue("page=1",
						() -> page(1, 2, T0, base.subList(1, 2), "A"),
						() -> page(1, 2, T0, base.subList(1, 2), "A"),
						() -> page(1, 2, lu1, base.subList(1, 2), "B"),
						() -> page(1, 2, lu1 + 60_000, base.subList(1, 2), "C"));
		try (ObservationStore store = ObservationStore.openInMemory("timed1")) {
			AuctionCollector c = collector(t, store);

			// Warm-up: the first snapshot only fills state; nothing in it is new.
			long delay = c.timedStep();
			assertEquals(1, snapshots.size());
			assertTrue(firstSeen.stream().allMatch(f -> Records.KIND_PREEXISTING.equals(f.row().observationKind())));
			assertEquals(48_000, delay, "sleep until 2 s before lastUpdated + 60 s");

			clock.advance(delay);
			int before = t.requests.size();
			c.timedStep();
			// 3 unchanged probes + 1 hit on page 0, then page 1 once stale and once fresh.
			assertEquals(6, t.requests.size() - before);
			assertEquals(Map.of("If-Modified-Since", "A"), t.requestHeaders.get(before));
			AuctionCollector.SnapshotSummary s = snapshots.get(1);
			assertEquals(lu1, s.lastUpdatedMs());
			assertEquals(SnapshotStatus.COMPLETE, s.status());
			assertEquals(4, s.burstProbes());
			assertEquals(1, s.staleRetries());
			assertEquals(1, s.newListings());
			assertEquals(8_300, s.detectLagMs(), "page 0 arrived after three 100 ms waits");
			assertEquals(1, countFor(fresh));
			assertEquals(Records.KIND_NEW, store.getApiObservation(fresh).observationKind());
			assertEquals(8_300, c.snapshotClock().minPublishDelayMs());
			// Published between the third unchanged probe (+200 ms) and the hit (+300 ms).
			AuctionCollector.PublishEstimate pub = c.publishEstimate(lu1);
			assertEquals(50, pub.uncertaintyMs());
			assertEquals(8_250, pub.hypixelLagMs());

			// Next cycle: the same listing is still there and must not be reported again.
			clock.set(c.snapshotClock().wakeAtLocalMs(2_000));
			c.timedStep();
			assertEquals(3, snapshots.size());
			assertEquals(1, countFor(fresh));
			assertEquals(Map.of("If-Modified-Since", "B"), t.requestHeaders.get(before + 6));
		}
	}

	@Test
	void newestListingsAreHandledBeforePage0FinishesAndOnlyOnce() throws Exception {
		List<Auction> old = new ArrayList<>();
		for (int i = 0; i < 25; i++) {
			old.add(Auction.bin("ITEM_" + i, 1_000 + i, T0 - 600_000));
		}
		UUID fresh = UUID.randomUUID();
		UUID late = UUID.randomUUID();
		List<Auction> next = new ArrayList<>();
		next.add(Auction.bin("ITEM_0", 900, T0 + 30_000).withUuid(fresh));
		next.addAll(old);
		// Not new (started long ago) and past the head: handled with the rest of the page.
		next.add(Auction.bin("ITEM_1", 950, T0 - 590_000).withUuid(late));
		long lu1 = T0 + 50_000;
		ScriptedTransport t = new ScriptedTransport()
				.enqueue("page=0",
						() -> page(0, 1, T0, old, "A"),
						() -> notModified("A"),
						() -> page(0, 1, lu1, next, "B"));
		try (ObservationStore store = ObservationStore.openInMemory("timedHead")) {
			AuctionCollector c = collector(t, store);
			clock.advance(c.timedStep());
			batches.clear();
			c.timedStep();
			// Head (fresh + 20 known old listings) first, then the rest of page 0.
			assertEquals(List.of(List.of(fresh), List.of(late)), batches);
			AuctionCollector.SnapshotSummary s = snapshots.get(1);
			assertEquals(1, s.newListings());
			assertEquals(2, s.firstSeen());
			assertEquals(store.getApiObservation(fresh).pollId(), store.getApiObservation(late).pollId());
		}
	}

	@Test
	void burstGivesUpThenProbesSlowly() throws Exception {
		List<Auction> base = List.of(Auction.bin("ITEM_0", 1_000, T0 - 600_000));
		ScriptedTransport t = new ScriptedTransport()
				.enqueue("page=0", () -> page(0, 1, T0, base, "A"))
				.whenEmpty("page=0", u -> notModified("A"));
		try (ObservationStore store = ObservationStore.openInMemory("timed2")) {
			AuctionCollector c = collector(t, store);
			clock.advance(c.timedStep());
			long burstStart = clock.millis();
			long delay = c.timedStep();
			assertEquals(10_000, delay, "falls back to the unchanged-recheck interval");
			assertEquals(1, c.burstGiveUps.get());
			assertTrue(clock.millis() - burstStart >= 20_000 && clock.millis() - burstStart < 21_000);

			int before = t.requests.size();
			clock.advance(delay);
			assertEquals(10_000, c.timedStep());
			assertEquals(1, t.requests.size() - before, "one probe per step after giving up, not another burst");
		}
	}

	@Test
	void resyncsWithFullPollAfterSleeping() throws Exception {
		List<Auction> base = List.of(Auction.bin("ITEM_0", 1_000, T0 - 600_000));
		ScriptedTransport t = new ScriptedTransport()
				.enqueue("page=0", () -> page(0, 1, T0, base, "A"), () -> page(0, 1, T0 + 600_000, base, "K"));
		try (ObservationStore store = ObservationStore.openInMemory("timed3")) {
			AuctionCollector c = collector(t, store);
			c.timedStep();
			clock.advance(600_000);
			int before = t.requests.size();
			c.timedStep();
			assertEquals(Map.of(), t.requestHeaders.get(before), "plain poll, not a conditional probe");
			assertEquals(T0 + 600_000, snapshots.get(1).lastUpdatedMs());
			assertEquals(0, snapshots.get(1).burstProbes());
			assertEquals(0, c.snapshotClock().minPublishDelayMs(), "a re-sync poll does not time the publish moment");
		}
	}

	@Test
	void networkErrorsBackOff() throws Exception {
		ScriptedTransport t = new ScriptedTransport().whenEmpty("page=0", u -> ScriptedTransport.status(500, Map.of(), ""));
		try (ObservationStore store = ObservationStore.openInMemory("timed4")) {
			AuctionCollector c = collector(t, store);
			List<Long> delays = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				delays.add(c.timedStep());
			}
			assertEquals(List.of(1_000L, 2_000L, 5_000L, 5_000L), delays);
		}
	}
}
