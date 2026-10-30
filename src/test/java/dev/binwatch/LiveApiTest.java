package dev.binwatch;

import dev.binwatch.api.AuctionApiClient;
import dev.binwatch.api.AuctionPage;
import dev.binwatch.api.JdkHttpTransport;
import dev.binwatch.config.BinWatchConfig;
import dev.binwatch.item.ItemNormalizer;
import dev.binwatch.item.NormalizedItem;
import dev.binwatch.nbt.Nbt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Clock;
import java.time.Duration;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in check against the real public API: {@code ./gradlew test -Dbinwatch.liveTest=true}. Read-only GET requests
 * to two documented endpoints; never runs in a normal build.
 */
@EnabledIfSystemProperty(named = "binwatch.liveTest", matches = "true")
class LiveApiTest {
	@Test
	void fetchesAndDecodesRealPages() throws Exception {
		BinWatchConfig cfg = new BinWatchConfig();
		AuctionApiClient client = new AuctionApiClient(new JdkHttpTransport(Duration.ofSeconds(10)),
				AuctionApiClient.DEFAULT_BASE, Clock.systemUTC(), Thread::sleep, new Random(),
				new AuctionApiClient.RetryPolicy(2, 1000, 10_000, Duration.ofSeconds(30)));
		AuctionPage p0 = client.fetchPage(0);
		System.out.println("Live: totalPages=" + p0.totalPages() + " totalAuctions=" + p0.totalAuctions()
				+ " lastUpdated=" + p0.lastUpdatedMs() + " cdnAge=" + p0.cdnAgeSeconds() + " skipped=" + p0.skipped());
		assertTrue(p0.totalPages() > 0);
		assertTrue(p0.auctions().size() > 100);
		ItemNormalizer norm = new ItemNormalizer(false, cfg.valuation.valueAttributes);
		int decoded = 0;
		int keyed = 0;
		int itemUuidAgrees = 0;
		int bothUuids = 0;
		for (var a : p0.auctions()) {
			ItemNormalizer.ItemBytes ib = ItemNormalizer.firstItem(Nbt.decodeBase64Gzip(a.itemBytes()));
			NormalizedItem item = norm.normalize(ItemNormalizer.extraAttributes(ib.tag()), a.itemName(), a.tier(), ib.count());
			decoded++;
			if (item.comparisonKey() != null) {
				keyed++;
			}
			if (a.itemUuid() != null && item.itemUuid() != null) {
				bothUuids++;
				if (AuctionApiClient.parseHexUuid(a.itemUuid()).equals(AuctionApiClient.parseHexUuid(item.itemUuid()))) {
					itemUuidAgrees++;
				}
			}
		}
		System.out.println("Live: decoded=" + decoded + " keyed=" + keyed + " itemUuid agree " + itemUuidAgrees + "/" + bothUuids);
		assertTrue(keyed > decoded * 0.95);
		assertTrue(itemUuidAgrees == bothUuids);
		AuctionApiClient.EndedResult ended = client.fetchEnded();
		System.out.println("Live: ended auctions=" + ended.auctions().size() + " skipped=" + ended.skipped());
	}
}
