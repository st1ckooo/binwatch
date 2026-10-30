package dev.binwatch;

import dev.binwatch.api.AuctionApiClient;
import dev.binwatch.api.AuctionCollector;
import dev.binwatch.api.SalesHistory;
import dev.binwatch.api.ScriptedTransport;
import dev.binwatch.api.SnapshotStatus;
import dev.binwatch.config.BinWatchConfig;
import dev.binwatch.service.SyntheticData;
import dev.binwatch.service.SyntheticData.Auction;
import dev.binwatch.store.ObservationStore;
import dev.binwatch.store.Records;
import dev.binwatch.value.PriceIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectorTest {
	static final long T0 = 1_793_310_000_000L;
	final MutableClock clock = new MutableClock(T0);
	final List<AuctionCollector.FirstSeen> firstSeen = new ArrayList<>();
	final List<AuctionCollector.SnapshotSummary> snapshots = new ArrayList<>();

	AuctionCollector collector(ScriptedTransport t, ObservationStore store) {
		BinWatchConfig cfg = new BinWatchConfig();
		cfg.api.fetchEndedAuctions = false;
		AuctionApiClient client = new AuctionApiClient(t, URI.create("https://api.example.invalid"), clock,
				ms -> clock.advance(ms), new Random(1), AuctionCollector.policyFrom(cfg.api));
		return new AuctionCollector(client, store, clock, cfg, new SalesHistory(50, 3_600_000L), new AuctionCollector.Listener() {
			@Override
			public void onFirstSeen(List<AuctionCollector.FirstSeen> fs, PriceIndex index) {
				firstSeen.addAll(fs);
			}

			@Override
			public void onSnapshot(AuctionCollector.SnapshotSummary summary) {
				snapshots.add(summary);
			}
		});
	}

	static List<Auction> auctions(int n, long start) {
		List<Auction> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			out.add(Auction.bin("ITEM_" + (i % 3), 1_000 + i, start));
		}
		return out;
	}

	@Test
	void followsPaginationAndRecordsFirstSeenOncePerUuid() throws Exception {
		List<Auction> all = auctions(6, T0 - 600_000);
		ScriptedTransport t = new ScriptedTransport()
				.whenEmpty("page=0", u -> ScriptedTransport.ok(SyntheticData.pageJson(0, 3, T0 - 1000, all.subList(0, 2))))
				.whenEmpty("page=1", u -> ScriptedTransport.ok(SyntheticData.pageJson(1, 3, T0 - 1000, all.subList(2, 4))))
				.whenEmpty("page=2", u -> ScriptedTransport.ok(SyntheticData.pageJson(2, 3, T0 - 1000, all.subList(4, 6))));
		try (ObservationStore store = ObservationStore.openInMemory("c1")) {
			AuctionCollector c = collector(t, store);
			AuctionCollector.PollResult r = c.pollOnce(null);
			assertEquals(AuctionCollector.PollOutcome.NEW_SNAPSHOT, r.outcome());
			assertEquals(SnapshotStatus.COMPLETE, r.summary().status());
			assertEquals(3, r.summary().pagesFetched());
			assertEquals(6, r.summary().firstSeen());
			assertEquals(6, store.count("api_obs"));
			assertEquals(6, firstSeen.size());
			// The first snapshot of a session cannot prove auctions are new.
			assertTrue(firstSeen.stream().allMatch(f -> Records.KIND_PREEXISTING.equals(f.row().observationKind())));
			Records.ApiObservation row = store.getApiObservation(all.get(0).uuid());
			assertEquals(SnapshotStatus.COMPLETE.name(), row.sourceStatus());
			assertEquals(T0 - 1000, row.snapshotLastUpdatedMs());

			// Same lastUpdated again: unchanged, nothing reprocessed.
			assertEquals(AuctionCollector.PollOutcome.UNCHANGED, c.pollOnce(null).outcome());
			assertEquals(6, firstSeen.size());
		}
	}

	@Test
	void parallelPageFetching() throws Exception {
		List<Auction> all = auctions(40, T0 - 600_000);
		ScriptedTransport t = new ScriptedTransport();
		for (int p = 0; p < 10; p++) {
			final int pg = p;
			t.whenEmpty("page=" + p + "", u -> ScriptedTransport.ok(SyntheticData.pageJson(pg, 10, T0, all.subList(pg * 4, pg * 4 + 4))));
		}
		ExecutorService pool = Executors.newFixedThreadPool(4);
		try (ObservationStore store = ObservationStore.openInMemory("c2")) {
			AuctionCollector.PollResult r = collector(t, store).pollOnce(pool);
			assertEquals(SnapshotStatus.COMPLETE, r.summary().status());
			assertEquals(40, store.count("api_obs"));
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void detectsInconsistentAndIncompleteSnapshots() throws Exception {
		List<Auction> all = auctions(4, T0 - 600_000);
		ScriptedTransport t = new ScriptedTransport()
				.enqueue("page=0", () -> ScriptedTransport.ok(SyntheticData.pageJson(0, 2, T0 - 1000, all.subList(0, 2))))
				.enqueue("page=1", () -> ScriptedTransport.ok(SyntheticData.pageJson(1, 2, T0 + 59_000, all.subList(2, 4))));
		try (ObservationStore store = ObservationStore.openInMemory("c3")) {
			AuctionCollector c = collector(t, store);
			AuctionCollector.PollResult r = c.pollOnce(null);
			assertEquals(SnapshotStatus.INCONSISTENT, r.summary().status());
			assertEquals("INCONSISTENT", store.getApiObservation(all.get(3).uuid()).sourceStatus());

			ScriptedTransport t2 = new ScriptedTransport()
					.enqueue("page=0", () -> ScriptedTransport.ok(SyntheticData.pageJson(0, 2, T0 + 120_000, all.subList(0, 2))))
					.whenEmpty("page=1", u -> ScriptedTransport.status(500, Map.of(), ""));
			AuctionCollector c2 = collector(t2, store);
			AuctionCollector.PollResult r2 = c2.pollOnce(null);
			assertEquals(SnapshotStatus.INCOMPLETE, r2.summary().status());
			assertEquals(List.of(1), r2.summary().failedPages());
		}
	}

	@Test
	void newAuctionsDuringContinuousMonitoringAreMarkedNew() throws Exception {
		List<Auction> base = auctions(3, T0 - 600_000);
		UUID freshUuid = UUID.randomUUID();
		ScriptedTransport t = new ScriptedTransport().enqueue("page=0",
				() -> ScriptedTransport.ok(SyntheticData.pageJson(0, 1, T0 - 1000, base)),
				() -> {
					List<Auction> next = new ArrayList<>(base);
					next.add(Auction.bin("ITEM_0", 900, T0 + 30_000).withUuid(freshUuid));
					return ScriptedTransport.ok(SyntheticData.pageJson(0, 1, T0 + 59_000, next));
				});
		try (ObservationStore store = ObservationStore.openInMemory("c4")) {
			AuctionCollector c = collector(t, store);
			c.pollOnce(null);
			clock.set(T0 + 75_000);
			c.pollOnce(null);
			Records.ApiObservation fresh = store.getApiObservation(freshUuid);
			assertNotNull(fresh);
			assertEquals(Records.KIND_NEW, fresh.observationKind());
			assertEquals(T0 + 75_000, fresh.firstApiObservedMs(), "timestamp = page receive time");
			assertEquals(45_000, fresh.firstApiObservedMs() - fresh.auctionStartMs());
		}
	}

	@Test
	void cadenceAlignmentSchedulesNextPollWhenNextSnapshotIsDue() {
		// Snapshot generated at T0; fetched 50 s later (CDN age). Next one is due at T0 + 60 s.
		assertEquals(10_000, AuctionCollector.nextDelayAfterSnapshot(60_000, 5_000, T0, 60_000, T0 + 50_000, true));
		// Without alignment: plain remaining interval.
		assertEquals(55_000, AuctionCollector.nextDelayAfterSnapshot(60_000, 5_000, T0, 60_000, T0 + 50_000, false));
		// Already overdue: never busy-loop, at least 1 s.
		assertEquals(1_000, AuctionCollector.nextDelayAfterSnapshot(60_000, 5_000, T0, 60_000, T0 + 90_000, true));
		// Never later than the configured interval.
		assertEquals(55_000, AuctionCollector.nextDelayAfterSnapshot(60_000, 5_000, T0, 300_000, T0 + 1_000, true));
	}

	@Test
	void newAuctionAfterInconsistentSnapshotIsStillNew() throws Exception {
		List<Auction> base = auctions(2, T0 - 600_000);
		UUID freshUuid = UUID.randomUUID();
		ScriptedTransport t = new ScriptedTransport()
				.enqueue("page=0", () -> ScriptedTransport.ok(SyntheticData.pageJson(0, 2, T0, base.subList(0, 1))),
						() -> ScriptedTransport.ok(SyntheticData.pageJson(0, 2, T0 + 60_000,
								List.of(base.get(0), Auction.bin("ITEM_0", 900, T0 + 50_000).withUuid(freshUuid)))))
				// Page 1 served from an older CDN copy on the first poll, through all stale-page retries: INCONSISTENT.
				.enqueue("page=1", () -> ScriptedTransport.ok(SyntheticData.pageJson(1, 2, T0 - 60_000, base.subList(1, 2))),
						() -> ScriptedTransport.ok(SyntheticData.pageJson(1, 2, T0 - 60_000, base.subList(1, 2))),
						() -> ScriptedTransport.ok(SyntheticData.pageJson(1, 2, T0 - 60_000, base.subList(1, 2))),
						() -> ScriptedTransport.ok(SyntheticData.pageJson(1, 2, T0 - 60_000, base.subList(1, 2))),
						() -> ScriptedTransport.ok(SyntheticData.pageJson(1, 2, T0 + 60_000, base.subList(1, 2))));
		try (ObservationStore store = ObservationStore.openInMemory("c6")) {
			AuctionCollector c = collector(t, store);
			assertEquals(SnapshotStatus.INCONSISTENT, c.pollOnce(null).summary().status());
			clock.set(T0 + 65_000);
			assertEquals(SnapshotStatus.COMPLETE, c.pollOnce(null).summary().status());
			assertEquals(Records.KIND_NEW, store.getApiObservation(freshUuid).observationKind());
			assertEquals(Records.KIND_PREEXISTING, store.getApiObservation(base.get(1).uuid()).observationKind());
		}
	}

	@Test
	void firstSeenSurvivesRestartAndIsNeverOverwritten(@TempDir Path dir) throws Exception {
		List<Auction> all = auctions(3, T0 - 600_000);
		ScriptedTransport t = new ScriptedTransport().whenEmpty("page=0",
				u -> ScriptedTransport.ok(SyntheticData.pageJson(0, 1, clock.millis() - 1000, all)));
		long firstObserved;
		try (ObservationStore store = ObservationStore.openFile(dir)) {
			collector(t, store).pollOnce(null);
			firstObserved = store.getApiObservation(all.get(0).uuid()).firstApiObservedMs();
		}
		clock.advance(3_600_000);
		firstSeen.clear();
		try (ObservationStore store = ObservationStore.openFile(dir)) {
			AuctionCollector c = collector(t, store);
			AuctionCollector.PollResult r = c.pollOnce(null);
			assertEquals(0, r.summary().firstSeen(), "restart must not re-record known auctions");
			assertTrue(firstSeen.isEmpty());
			assertEquals(firstObserved, store.getApiObservation(all.get(0).uuid()).firstApiObservedMs());
			assertEquals(3, store.count("api_obs"));
		}
	}

	@Test
	void decodesRealItemBytesIntoComparisonKey() throws Exception {
		Auction a = new Auction(UUID.randomUUID(), "HYPERION", "Hyperion", "LEGENDARY", 900_000_000, true, T0 - 1000,
				T0 + 3_600_000, UUID.randomUUID().toString(), 1, Map.of("rarity_upgrades", 1, "enchantments", Map.of("sharpness", 6)));
		ScriptedTransport t = new ScriptedTransport().enqueue("page=0",
				() -> ScriptedTransport.ok(SyntheticData.pageJson(0, 1, T0, List.of(a))));
		try (ObservationStore store = ObservationStore.openInMemory("c5")) {
			collector(t, store).pollOnce(null);
			Records.ApiObservation row = store.getApiObservation(a.uuid());
			assertEquals("HYPERION", row.itemId());
			assertEquals(a.itemUuid(), row.itemUuid());
			assertTrue(row.normKey().startsWith("HYPERION|tier=LEGENDARY|ench=sharpness:6,"), row.normKey());
			assertTrue(row.normKey().contains("rarity_upgrades=1"));
		}
	}
}
