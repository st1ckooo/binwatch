package dev.binwatch;

import dev.binwatch.config.BinWatchConfig;
import dev.binwatch.view.ViewClassifier;
import dev.binwatch.view.ViewClassifier.Result;
import dev.binwatch.view.ViewRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoViewTest {
	static final String BIN_VIEW = "BIN Auction View";
	static final List<String> LISTED = List.of("Seller: [MVP+] Someone", "Buy it now: 1,000,000 coins", "Ends in: 13h");

	@Test
	void purchaseButtonStates() {
		assertEquals(Result.BUYABLE, ViewClassifier.classifyView(BIN_VIEW, "minecraft:gold_nugget", "Buy Item Right Now",
				List.of("Price: 1,000,000 coins", "", "Click to purchase!"), LISTED));
		assertEquals(Result.CANT_AFFORD, ViewClassifier.classifyView(BIN_VIEW, "minecraft:poisonous_potato",
				"Buy Item Right Now", List.of("Price: 1,000,000 coins", "", "Cannot afford this item!"), LISTED));
		assertEquals(Result.PURCHASE_DELAY, ViewClassifier.classifyView(BIN_VIEW, "minecraft:red_bed", "Buy Item Right Now",
				List.of("Can be bought soon"), LISTED));
		assertEquals(Result.NOT_BIN, ViewClassifier.classifyView("Auction View", "minecraft:gold_nugget", "Submit Bid",
				List.of(), LISTED));
		assertEquals(Result.UNKNOWN, ViewClassifier.classifyView(BIN_VIEW, "minecraft:stone", "Something new", List.of(), LISTED));
	}

	@Test
	void goneListings() {
		assertEquals(Result.SOLD, ViewClassifier.classifyView(BIN_VIEW, "minecraft:gold_nugget", "Buy Item Right Now",
				List.of(), List.of("Seller: Someone", "Buy it now: 1,000,000 coins", "Status: Sold!")));
		assertEquals(Result.SOLD, ViewClassifier.classifyView(BIN_VIEW, "minecraft:barrier", "Buy Item Right Now",
				List.of("This item has already been bought!"), LISTED));
		assertEquals(Result.ENDED, ViewClassifier.classifyView(BIN_VIEW, "minecraft:barrier", "Buy Item Right Now",
				List.of(), List.of("Status: Expired!")));
		assertEquals(Result.OWN_AUCTION, ViewClassifier.classifyView(BIN_VIEW, "minecraft:gold_block", "Cancel Auction",
				List.of(), LISTED));
		assertTrue(Result.BUYABLE.stillForSale);
		assertFalse(Result.SOLD.stillForSale);
		assertNull(Result.NO_RESPONSE.stillForSale);
	}

	@Test
	void chatReplies() {
		assertEquals(Result.NOT_FOUND, ViewClassifier.classifyChat("This auction wasn't found!"));
		assertEquals(Result.COMMAND_REFUSED, ViewClassifier.classifyChat("You need the Cookie Buff to use this command!"));
		assertNull(ViewClassifier.classifyChat("[MVP+] Someone: hello"));
	}

	@Test
	void recorderWritesCsvAndSummarises(@TempDir Path dir) throws Exception {
		ViewRecorder r = new ViewRecorder(dir.resolve("exports").resolve("autoview_checks.csv"));
		r.record(new ViewRecorder.Check(1_793_357_000_000L, UUID.randomUUID(), "Hyperion, Heroic", 900_000_000, 50_000_000,
				Result.BUYABLE, 400L, 900L, 500L, 150, 50L, BIN_VIEW, "minecraft:gold_nugget", "Buy Item Right Now",
				List.of("Price: 900,000,000 coins"), LISTED, List.of()));
		r.record(new ViewRecorder.Check(1_793_357_060_000L, UUID.randomUUID(), "=cmd", 1_000_000, 300_000,
				Result.NOT_FOUND, 500L, 1_300L, 800L, 150, 50L, "", null, "", List.of(), List.of(),
				List.of("This auction wasn't found!")));
		List<String> lines = Files.readAllLines(r.file());
		assertEquals(3, lines.size(), "header and two rows");
		assertTrue(lines.get(0).startsWith("checked_utc,auction_uuid,"));
		assertTrue(lines.get(1).contains("\"Hyperion, Heroic\""));
		assertTrue(lines.get(2).contains("'=cmd"), "formula injection neutralised");
		String summary = String.join("\n", r.summaryLines());
		assertTrue(summary.contains("Still for sale when opened: 1 of 2 (50%)"), summary);
		assertTrue(summary.contains("not found 1"), summary);
	}

	@Test
	void autoViewIsOffByDefaultAndValidated() {
		BinWatchConfig cfg = new BinWatchConfig();
		assertFalse(cfg.autoView.enabled);
		assertTrue(cfg.validate().isEmpty());
		cfg.autoView.minGapMs = 100;
		assertTrue(cfg.validate().stream().anyMatch(e -> e.contains("autoView.minGapMs")));
	}
}
