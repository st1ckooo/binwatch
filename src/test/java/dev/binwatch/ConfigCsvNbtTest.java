package dev.binwatch;

import dev.binwatch.config.BinWatchConfig;
import dev.binwatch.config.ConfigManager;
import dev.binwatch.config.Secrets;
import dev.binwatch.export.CsvExporter;
import dev.binwatch.item.ItemNormalizer;
import dev.binwatch.log.BwLog;
import dev.binwatch.nbt.Nbt;
import dev.binwatch.nbt.NbtWriter;
import dev.binwatch.store.ObservationStore;
import dev.binwatch.store.Records;
import dev.binwatch.util.Redactor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigCsvNbtTest {
	static final String FAKE_URL = "https://discord.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyz0123456789ABCD";

	@Test
	void defaultConfigIsValid() {
		assertEquals(List.of(), new BinWatchConfig().validate());
	}

	@Test
	void invalidConfigIsRejected() {
		BinWatchConfig c = new BinWatchConfig();
		c.api.pollIntervalSeconds = 1;
		c.filters.minConfidence = 1.5;
		c.valuation.sources = List.of("MAGIC");
		c.fees.listingFeeTiers.get(2).upToPrice = 5L;
		List<String> errors = c.validate();
		assertTrue(errors.stream().anyMatch(e -> e.startsWith("api.pollIntervalSeconds")));
		assertTrue(errors.stream().anyMatch(e -> e.startsWith("filters.minConfidence")));
		assertTrue(errors.stream().anyMatch(e -> e.contains("MAGIC")));
		assertTrue(errors.stream().anyMatch(e -> e.contains("last fee tier")));
	}

	@Test
	void configFilesAreCreatedAndInvalidJsonIsReportedNotOverwritten(@TempDir Path dir) throws Exception {
		ConfigManager m = new ConfigManager(dir);
		assertTrue(m.loadConfig().createdDefault());
		assertTrue(Files.exists(dir.resolve("config.json")));
		Files.writeString(dir.resolve("config.json"), "{ broken");
		ConfigManager.LoadResult<BinWatchConfig> r = m.loadConfig();
		assertFalse(r.ok());
		assertEquals("{ broken", Files.readString(dir.resolve("config.json")), "invalid file must not be overwritten");
		Files.writeString(dir.resolve("config.json"), "{\"api\":{\"pollIntervalSeconds\":5}}");
		assertFalse(m.loadConfig().ok());
	}

	@Test
	void secretsValidationAndRedaction(@TempDir Path dir) throws Exception {
		assertNull(Secrets.validateWebhookUrl(FAKE_URL));
		assertNotNull(Secrets.validateWebhookUrl("http://discord.com/api/webhooks/1/x"));
		assertNotNull(Secrets.validateWebhookUrl("https://evil.example/api/webhooks/123456/abcdefghijklmnopqrstuvwxyz"));
		String problem = Secrets.validateWebhookUrl("https://discord.com/not-a-webhook/" + "secret-token-value-123456");
		assertFalse(problem.contains("secret-token"), "validation messages never echo the URL");

		ConfigManager m = new ConfigManager(dir);
		m.loadSecrets();
		assertTrue(Files.exists(dir.resolve("secrets.json")));
		Files.writeString(dir.resolve("secrets.json"), "{\"discordWebhookUrl\":\"" + FAKE_URL + "\"}");
		Secrets s = m.loadSecrets().value();
		assertTrue(s.hasWebhook());
		assertFalse(s.toString().contains("abcdefghij"));
		Files.writeString(dir.resolve("secrets.json"), "{\"discordWebhookUrl\":\"https://example.com/hook\"}");
		ConfigManager.LoadResult<Secrets> bad = m.loadSecrets();
		assertFalse(bad.ok());
		assertFalse(bad.value().hasWebhook(), "rejected URL is not used");

		String text = "posting to " + FAKE_URL + " failed; also discordapp.com/api/v10/webhooks/1/tok";
		String red = Redactor.redact(text);
		assertFalse(red.contains("abcdefghij"));
		assertFalse(red.contains("/tok"));
		assertTrue(red.contains("[REDACTED-WEBHOOK]"));
	}

	@Test
	void logFileNeverContainsWebhook(@TempDir Path dir) throws Exception {
		BwLog.configure(dir, BwLog.Level.INFO, 1, 2);
		BwLog.warn("delivery to " + FAKE_URL + " failed");
		BwLog.configure(null, BwLog.Level.INFO, 1, 2);
		String content = Files.readString(dir.resolve("binwatch.log"));
		assertTrue(content.contains("[REDACTED-WEBHOOK]"));
		assertFalse(content.contains("abcdefghij"));
	}

	@Test
	void csvExportIsCorrectlyQuoted(@TempDir Path dir) throws Exception {
		assertEquals("\"a,b\"", CsvExporter.escape("a,b"));
		assertEquals("\"say \"\"hi\"\"\"", CsvExporter.escape("say \"hi\""));
		assertEquals("'=HYPERLINK(1)", CsvExporter.escape("=HYPERLINK(1)"));
		assertEquals("-5", CsvExporter.escape(-5L));
		assertEquals("", CsvExporter.escape(null));
		long now = 1_793_310_000_000L;
		try (ObservationStore store = ObservationStore.openInMemory("csv")) {
			UUID u = UUID.fromString("5e6ae994-da89-44b0-ae26-c63e4f023589");
			store.insertApiObservations(List.of(new Records.ApiObservation(u, "item-1", "HYPERION", "Hyperion, \"Heroic\"",
					"K", "LEGENDARY", true, 15_000_000, 1, now - 72_000, null, now, now - 3_000, "lu-1", 7, 0, "COMPLETE",
					Records.KIND_NEW)));
			List<Path> files = CsvExporter.exportAll(store, dir, now - 86_400_000L, now, 1000);
			assertEquals(2, files.size());
			List<String> lines = Files.readAllLines(files.get(0), StandardCharsets.UTF_8);
			assertEquals(2, lines.size());
			assertTrue(lines.get(0).startsWith("source,auction_uuid,item_uuid"));
			assertEquals("API,5e6ae994-da89-44b0-ae26-c63e4f023589,item-1,HYPERION,\"Hyperion, \"\"Heroic\"\"\",LEGENDARY,true,"
					+ "15000000,1,2026-10-29T21:38:48Z,,2026-10-29T21:40:00Z,72000,2026-10-29T21:39:57Z,lu-1,7,0,COMPLETE,"
					+ "NEW_DURING_MONITORING", lines.get(1));
		}
	}

	@Test
	void nbtRoundTripAndItemBytesDecoding() throws Exception {
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put("id", "POWER_WITHER_BOOTS");
		extra.put("uuid", "888f72b1-804b-41ef-94ce-c50314992fd1");
		extra.put("upgrade_level", 5);
		extra.put("enchantments", Map.of("protection", 6));
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("id", (short) 1);
		item.put("Count", (byte) 1);
		item.put("tag", Map.of("ExtraAttributes", extra));
		item.put("components", Map.of("minecraft:tooltip_style", "hypixel_skyblock:mythic"));
		String b64 = NbtWriter.base64Gzip(Map.of("i", List.of(item)));
		ItemNormalizer.ItemBytes ib = ItemNormalizer.firstItem(Nbt.decodeBase64Gzip(b64));
		assertEquals("MYTHIC", ib.tooltipTier());
		Map<String, Object> ea = ItemNormalizer.extraAttributes(ib.tag());
		assertEquals("POWER_WITHER_BOOTS", Nbt.string(ea, "id"));
		assertEquals(5L, Nbt.number(ea, "upgrade_level"));
		assertEquals(Map.of("protection", 6), Nbt.compound(ea, "enchantments"));
		assertEquals(Nbt.canonical(Map.of("a", (byte) 1, "b", List.of(1, 2))), Nbt.canonical(Map.of("b", List.of(1L, 2L), "a", 1)));
	}

	@Test
	void corruptItemBytesAreRejected() {
		assertThrowsIo(() -> Nbt.decodeBase64Gzip("not base64 !!"));
		assertThrowsIo(() -> Nbt.decodeBase64Gzip("H4sIAAAAAAAA"));
	}

	private static void assertThrowsIo(org.junit.jupiter.api.function.Executable e) {
		try {
			e.execute();
		} catch (java.io.IOException ok) {
			return;
		} catch (Throwable t) {
			throw new AssertionError("expected IOException, got " + t);
		}
		throw new AssertionError("expected IOException");
	}

	@Test
	void corruptedDatabaseIsMovedAsideAndRecreated(@TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve("binwatch.mv.db"), "this is not an H2 database");
		try (ObservationStore store = ObservationStore.openFile(dir)) {
			assertEquals(0, store.count("api_obs"));
		}
		try (var files = Files.list(dir)) {
			assertTrue(files.anyMatch(p -> p.getFileName().toString().contains(".corrupt-")));
		}
	}
}
