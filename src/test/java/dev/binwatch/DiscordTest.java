package dev.binwatch;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.binwatch.api.AuctionCollector;
import dev.binwatch.api.ScriptedTransport;
import dev.binwatch.config.BinWatchConfig;
import dev.binwatch.discord.AlertDispatcher;
import dev.binwatch.discord.FlipEmbed;
import dev.binwatch.discord.WebhookClient;
import dev.binwatch.item.ItemNormalizer;
import dev.binwatch.item.NormalizedItem;
import dev.binwatch.store.ObservationStore;
import dev.binwatch.store.Records;
import dev.binwatch.store.Records.AlertStatus;
import dev.binwatch.value.FlipCandidate;
import dev.binwatch.value.Valuation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordTest {
	static final long NOW = 1_793_310_000_000L;
	/** Fake webhook URL: never a real one, and the transport is scripted, so nothing leaves the machine. */
	static final String FAKE_URL = "https://discord.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyz0123456789ABCD";
	final MutableClock clock = new MutableClock(NOW);
	final List<Long> sleeps = new ArrayList<>();

	static FlipCandidate candidate(UUID uuid, String key, long net, boolean synthetic) {
		Valuation v = new Valuation(true, null, 19_800_000, 0.82, "Lowest other BIN of 5 listing(s)", NOW - 20_000,
				20_000_000L, 20_500_000L, 5, 20_100_000L, 12, List.of());
		return new FlipCandidate(uuid, "Hyperion", "HYPERION", "LEGENDARY, Enchants: sharpness 6", key, 15_000_000, 1,
				NOW - 72_000, NOW + 3_600_000, NOW, NOW - 3_000, Records.KIND_NEW, v, 19_800_000, 4_800_000, 594_000, net,
				24.2, true, "Net 4.21M >= 1.00M", List.of(), "Estimate only; resale and profit are not guaranteed", synthetic, null);
	}

	static Map<String, String> fields(JsonObject payload) {
		JsonObject embed = payload.getAsJsonArray("embeds").get(0).getAsJsonObject();
		Map<String, String> m = new HashMap<>();
		JsonArray arr = embed.getAsJsonArray("fields");
		for (int i = 0; i < arr.size(); i++) {
			JsonObject f = arr.get(i).getAsJsonObject();
			m.put(f.get("name").getAsString(), f.get("value").getAsString());
		}
		return m;
	}

	@Test
	void foundOnApiMeasuresFromHypixelPublishWhenKnown() {
		UUID u = UUID.fromString("5e6ae994-da89-44b0-ae26-c63e4f023589");
		FlipCandidate c = candidate(u, "K", 4_206_000, false);
		long now = NOW + 1000;
		AuctionCollector.PublishEstimate pub = new AuctionCollector.PublishEstimate(c.snapshotLastUpdatedMs(),
				now - 640, 50, 7_800L);
		JsonObject payload = FlipEmbed.build(c, FlipEmbed.Style.of(new BinWatchConfig().discord), now, 5000, 0, pub);
		assertEquals("API observed 1m 12s after recorded auction start\n"
				+ "Sent 0.6s after Hypixel published the snapshot (Hypixel published it 7.8s after its lastUpdated)",
				fields(payload).get("Found on API"));
	}

	@Test
	void embedShowsOnlyTheRequestedFields() {
		UUID u = UUID.fromString("5e6ae994-da89-44b0-ae26-c63e4f023589");
		JsonObject payload = FlipEmbed.build(candidate(u, "K", 4_206_000, false), 0x2ECC71, NOW + 1000, 5000);
		JsonObject embed = payload.getAsJsonArray("embeds").get(0).getAsJsonObject();
		assertEquals("Potential BIN Flip", embed.get("title").getAsString());
		assertEquals(0x2ECC71, embed.get("color").getAsInt());
		assertTrue(embed.get("description").getAsString().contains("**Hyperion**"));
		Map<String, String> f = fields(payload);
		assertEquals(List.of("Profit", "AH price", "Suggested price", "Attributes", "Found on API", "Open in game"),
				fieldNames(payload));
		assertEquals("15,000,000", f.get("AH price"));
		assertEquals("19,800,000", f.get("Suggested price"));
		assertEquals("```ansi\n\u001b[1;32m+4,206,000 coins  (24.2%)\u001b[0m\n```", f.get("Profit"));
		assertEquals("API observed 1m 12s after recorded auction start\nFetched 3.0s after its API snapshot (lastUpdated)",
				f.get("Found on API"));
		assertFalse(payload.toString().contains("publication delay"));
		assertEquals("```/viewauction 5e6ae994da8944b0ae26c63e4f023589```", f.get("Open in game"));
		assertFalse(payload.toString().contains("http"), "no constructed auction URL and no image when disabled");
		assertTrue(embed.getAsJsonObject("footer").get("text").getAsString().contains("estimate"));
		assertTrue(payload.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").isEmpty());
		// Valid JSON round trip and Discord limits.
		JsonObject reparsed = JsonParser.parseString(payload.toString()).getAsJsonObject();
		JsonArray fs = reparsed.getAsJsonArray("embeds").get(0).getAsJsonObject().getAsJsonArray("fields");
		assertTrue(fs.size() <= 25);
		for (int i = 0; i < fs.size(); i++) {
			assertTrue(fs.get(i).getAsJsonObject().get("value").getAsString().length() <= 1024);
		}
	}

	static List<String> fieldNames(JsonObject payload) {
		JsonArray arr = payload.getAsJsonArray("embeds").get(0).getAsJsonObject().getAsJsonArray("fields");
		List<String> names = new ArrayList<>();
		for (int i = 0; i < arr.size(); i++) {
			names.add(arr.get(i).getAsJsonObject().get("name").getAsString());
		}
		return names;
	}

	@Test
	void syntheticDataIsMarkedAndNeverQueued() throws Exception {
		JsonObject p = FlipEmbed.build(candidate(UUID.randomUUID(), "K", 1, true), 0, NOW, 5000);
		assertTrue(p.getAsJsonArray("embeds").get(0).getAsJsonObject().get("title").getAsString().startsWith("[TEST DATA]"));
		try (ObservationStore store = ObservationStore.openInMemory("d0")) {
			AlertDispatcher d = dispatcher(store, new ScriptedTransport(), new BinWatchConfig());
			assertEquals(AlertDispatcher.OfferResult.SYNTHETIC_NOT_SENT, d.offer(candidate(UUID.randomUUID(), "K", 1, true)));
		}
	}

	@Test
	void styledEmbedShowsItemDetailsIconAndRarityColor() {
		ItemNormalizer norm = new ItemNormalizer(false, new BinWatchConfig().valuation.valueAttributes);
		Map<String, Object> extra = new HashMap<>();
		extra.put("id", "HYPERION");
		extra.put("modifier", "heroic");
		extra.put("hot_potato_count", 15);
		extra.put("rarity_upgrades", 1);
		extra.put("enchantments", Map.of("sharpness", 6, "ultimate_wise", 5, "critical", 7));
		extra.put("ability_scroll", List.of("IMPLOSION_SCROLL", "SHADOW_WARP_SCROLL"));
		extra.put("gems", Map.of("SAPPHIRE_0", "PERFECT", "COMBAT_0", Map.of("quality", "FLAWLESS"), "COMBAT_0_gem",
				"JASPER", "unlocked_slots", List.of("SAPPHIRE_0")));
		NormalizedItem item = norm.normalize(extra, "Heroic Hyperion ✪✪✪✪✪", "MYTHIC", 1);
		FlipCandidate base = candidate(UUID.randomUUID(), "K", 4_206_000, false);
		FlipCandidate c = new FlipCandidate(base.auctionUuid(), item.displayName(), "HYPERION", item.attributeSummary(),
				item.comparisonKey(), base.price(), 1, base.auctionStartMs(), base.auctionEndMs(), base.firstApiObservedMs(),
				base.snapshotLastUpdatedMs(), base.observationKind(), base.valuation(), base.estimatedResale(),
				base.estimatedGrossProfit(), base.estimatedFees(), base.estimatedNetProfit(), base.discountPercent(), true,
				base.passReason(), List.of(), "only 2 recorded sale(s); need 3; Estimate only; resale and profit are not guaranteed",
				false, item);
		FlipEmbed.Style style = FlipEmbed.Style.of(new BinWatchConfig().discord);
		JsonObject payload = FlipEmbed.build(c, style, NOW + 1000, 5000);
		JsonObject embed = payload.getAsJsonArray("embeds").get(0).getAsJsonObject();
		Map<String, String> f = fields(payload);
		assertEquals(0xFF55FF, embed.get("color").getAsInt(), "Mythic rarity color");
		assertEquals("https://sky.coflnet.com/static/icon/HYPERION",
				embed.getAsJsonObject("thumbnail").get("url").getAsString());
		assertTrue(embed.get("description").getAsString().contains("Mythic · Recombobulated"));
		assertEquals("**Ultimate Wise V** · Critical VII · Sharpness VI", f.get("Enchantments"));
		String upgrades = f.get("Upgrades");
		assertTrue(upgrades.contains("Reforge: Heroic"));
		assertTrue(upgrades.contains("10 Hot Potato + 5 Fuming"));
		assertTrue(upgrades.contains("Scrolls: Implosion, Shadow Warp"));
		assertTrue(upgrades.contains("Perfect Sapphire") && upgrades.contains("Flawless Jasper"));
		assertEquals(List.of("Profit", "AH price", "Suggested price", "Enchantments", "Upgrades", "Found on API", "Open in game"),
				fieldNames(payload));
		// The icon is the only URL: no auction URL is ever built.
		assertEquals(1, payload.toString().split("https://", -1).length - 1);
	}

	@Test
	void petIconAndDetails() {
		ItemNormalizer norm = new ItemNormalizer(false, new BinWatchConfig().valuation.valueAttributes);
		NormalizedItem pet = norm.normalize(Map.of("id", "PET", "petInfo",
				"{\"type\":\"GOLDEN_DRAGON\",\"tier\":\"LEGENDARY\",\"heldItem\":\"PET_ITEM_TIER_BOOST\",\"candyUsed\":0}"),
				"[Lvl 100] Golden Dragon", "LEGENDARY", 1);
		assertEquals("PET_GOLDEN_DRAGON", pet.details().imageId());
		assertEquals("Held: Tier Boost", pet.details().pet());
		assertEquals("https://sky.coflnet.com/static/icon/PET_GOLDEN_DRAGON",
				FlipEmbed.imageUrl(FlipEmbed.Style.of(new BinWatchConfig().discord), pet.details().imageId()));
		assertEquals(null, FlipEmbed.imageUrl(FlipEmbed.Style.of(new BinWatchConfig().discord), "../evil?x=1"));
		assertEquals(null, FlipEmbed.imageUrl(new FlipEmbed.Style(0, false, "http://insecure/{id}"), "HYPERION"));
	}

	@Test
	void previewIsMarkedTestDataAndHasNoAuctionCommand() {
		JsonObject p = FlipEmbed.samplePayload(FlipEmbed.Style.of(new BinWatchConfig().discord), NOW);
		JsonObject embed = p.getAsJsonArray("embeds").get(0).getAsJsonObject();
		assertTrue(embed.get("title").getAsString().startsWith("[TEST DATA]"));
		assertTrue(embed.get("description").getAsString().startsWith("Synthetic test data"));
		assertFalse(p.toString().contains("/viewauction"));
	}

	AlertDispatcher dispatcher(ObservationStore store, ScriptedTransport t, BinWatchConfig cfg) {
		return new AlertDispatcher(store, new WebhookClient(t), clock, ms -> {
			sleeps.add(ms);
			clock.advance(ms);
		}, cfg, () -> FAKE_URL);
	}

	@Test
	void deliversOnlyOnSuccessAndHandles429() throws Exception {
		ScriptedTransport t = new ScriptedTransport().enqueue("/api/webhooks/",
				() -> ScriptedTransport.status(429, Map.of(), "{\"retry_after\": 2.5, \"global\": false}"),
				() -> ScriptedTransport.status(200, Map.of(), "{\"id\":\"1\"}"));
		try (ObservationStore store = ObservationStore.openInMemory("d1")) {
			AlertDispatcher d = dispatcher(store, t, new BinWatchConfig());
			UUID u = UUID.randomUUID();
			assertEquals(AlertDispatcher.OfferResult.QUEUED, d.offer(candidate(u, "K", 5_000_000, false)));
			assertEquals(AlertStatus.QUEUED, store.getAlert(u).status());
			assertTrue(d.processNext(0));
			assertEquals(AlertStatus.SENT, store.getAlert(u).status());
			assertEquals(1, d.rateLimited.get());
			assertTrue(sleeps.contains(2500L), "waits Discord's retry_after: " + sleeps);
			assertTrue(t.requests.get(0).getQuery().contains("wait=true"));
		}
	}

	@Test
	void permanentAndRetryableErrors() throws Exception {
		ScriptedTransport t = new ScriptedTransport()
				.enqueue("/api/webhooks/", () -> ScriptedTransport.status(404, Map.of(), "{\"message\":\"Unknown Webhook\"}"))
				.enqueue("/api/webhooks/", () -> ScriptedTransport.status(502, Map.of(), ""),
						() -> ScriptedTransport.status(502, Map.of(), ""), () -> ScriptedTransport.status(502, Map.of(), ""));
		try (ObservationStore store = ObservationStore.openInMemory("d2")) {
			BinWatchConfig cfg = new BinWatchConfig();
			cfg.discord.itemCooldownSeconds = 0;
			AlertDispatcher d = dispatcher(store, t, cfg);
			UUID a = UUID.randomUUID();
			d.offer(candidate(a, "K1", 5_000_000, false));
			d.processNext(0);
			Records.Alert alertA = store.getAlert(a);
			assertEquals(AlertStatus.FAILED, alertA.status());
			assertTrue(alertA.detail().contains("Unknown Webhook"));
			assertFalse(alertA.detail().contains(FAKE_URL), "webhook URL must never be stored");
			UUID b = UUID.randomUUID();
			d.offer(candidate(b, "K2", 5_000_000, false));
			d.processNext(0);
			assertEquals(AlertStatus.FAILED, store.getAlert(b).status());
			assertEquals(3, store.getAlert(b).attempts());
		}
	}

	@Test
	void dedupAndCooldownSurviveRestart(@TempDir Path dir) throws Exception {
		ScriptedTransport t = new ScriptedTransport().whenEmpty("/api/webhooks/", u -> ScriptedTransport.status(204, Map.of(), ""));
		UUID u = UUID.randomUUID();
		try (ObservationStore store = ObservationStore.openFile(dir)) {
			AlertDispatcher d = dispatcher(store, t, new BinWatchConfig());
			assertEquals(AlertDispatcher.OfferResult.QUEUED, d.offer(candidate(u, "SAMEKEY", 5_000_000, false)));
			d.processNext(0);
			assertEquals(AlertStatus.SENT, store.getAlert(u).status());
		}
		clock.advance(60_000);
		try (ObservationStore store = ObservationStore.openFile(dir)) {
			AlertDispatcher d = dispatcher(store, t, new BinWatchConfig());
			assertEquals(AlertDispatcher.OfferResult.COOLDOWN, d.offer(candidate(UUID.randomUUID(), "SAMEKEY", 5_000_000, false)));
			BinWatchConfig noCooldown = new BinWatchConfig();
			noCooldown.discord.itemCooldownSeconds = 0;
			d.applyConfig(noCooldown);
			assertEquals(AlertDispatcher.OfferResult.DUPLICATE, d.offer(candidate(u, "SAMEKEY", 5_000_000, false)));
		}
		assertEquals(1, t.requests.size(), "exactly one delivery across both sessions");
	}

	@Test
	void queuedAlertsFromPreviousSessionExpireInsteadOfResending(@TempDir Path dir) throws Exception {
		UUID u = UUID.randomUUID();
		try (ObservationStore store = ObservationStore.openFile(dir)) {
			dispatcher(store, new ScriptedTransport(), new BinWatchConfig()).offer(candidate(u, "K", 5_000_000, false));
		}
		try (ObservationStore store = ObservationStore.openFile(dir)) {
			assertEquals(1, store.expireQueuedAlerts(clock.millis()));
			assertEquals(AlertStatus.EXPIRED, store.getAlert(u).status());
		}
	}

	@Test
	void minuteRateLimitAndBoundedQueue() throws Exception {
		ScriptedTransport t = new ScriptedTransport().whenEmpty("/api/webhooks/", u -> ScriptedTransport.status(204, Map.of(), ""));
		try (ObservationStore store = ObservationStore.openInMemory("d4")) {
			BinWatchConfig cfg = new BinWatchConfig();
			cfg.discord.itemCooldownSeconds = 0;
			cfg.discord.maxAlertsPerMinute = 2;
			cfg.discord.queueCapacity = 3;
			cfg.discord.maxQueuedAgeSeconds = 3600;
			AlertDispatcher d = dispatcher(store, t, cfg);
			for (int i = 0; i < 4; i++) {
				d.offer(candidate(UUID.randomUUID(), "K" + i, 1_000_000L * (i + 1), false));
			}
			assertEquals(3, d.queued());
			assertEquals(1, d.dropped.get(), "lowest-profit alert dropped when queue is full");
			long start = clock.millis();
			while (d.processNext(0)) {
				// drain
			}
			assertEquals(3, d.delivered.get());
			assertTrue(clock.millis() - start >= 59_000, "third alert waits for the per-minute budget");
		}
	}

	@Test
	void testWebhookIsOnlySentOnRequest() throws Exception {
		ScriptedTransport t = new ScriptedTransport().whenEmpty("/api/webhooks/", u -> ScriptedTransport.status(204, Map.of(), ""));
		try (ObservationStore store = ObservationStore.openInMemory("d5")) {
			AlertDispatcher d = dispatcher(store, t, new BinWatchConfig());
			assertEquals(0, t.requests.size());
			WebhookClient.Result r = d.sendTest().get();
			assertTrue(r.delivered());
			assertFalse(r.detail().contains("discord.com"));
			assertTrue(t.postedBodies.get(0).contains("webhook test"));
		}
	}
}
