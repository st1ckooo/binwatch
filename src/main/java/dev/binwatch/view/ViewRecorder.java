package dev.binwatch.view;

import dev.binwatch.api.AuctionCollector;
import dev.binwatch.export.CsvExporter;
import dev.binwatch.log.BwLog;
import dev.binwatch.util.Stats;
import dev.binwatch.util.TimeFmt;
import dev.binwatch.view.ViewClassifier.Result;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Appends every speed check to a CSV file and keeps running totals for the status command. */
public final class ViewRecorder {
	static final String HEADER = "checked_utc,auction_uuid,item,price,est_net_profit,result,still_for_sale,"
			+ "command_after_publish_ms,result_after_publish_ms,result_after_command_ms,page_to_command_ms,"
			+ "publish_uncertainty_ms,title,button_item,button_name,button_lore,item_status_lore,chat";

	/**
	 * One check. Times after publish are null when the collector could not bracket the snapshot's publish moment.
	 */
	public record Check(long checkedAtMs, UUID auctionUuid, String item, long price, long estNetProfit, Result result,
			Long commandAfterPublishMs, Long resultAfterPublishMs, Long resultAfterCommandMs, long pageToCommandMs,
			Long publishUncertaintyMs, String title, String buttonItem, String buttonName, List<String> buttonLore,
			List<String> itemStatusLore, List<String> chat) {
	}

	private final Path file;
	private final Map<Result, Integer> counts = new EnumMap<>(Result.class);
	private final List<Long> forSaleAfterPublish = new ArrayList<>();
	private final List<Long> goneAfterPublish = new ArrayList<>();

	public ViewRecorder(Path file) {
		this.file = file;
	}

	public Path file() {
		return file;
	}

	public synchronized void record(Check c) {
		counts.merge(c.result(), 1, Integer::sum);
		if (c.resultAfterPublishMs() != null && c.result().stillForSale != null) {
			(c.result().stillForSale ? forSaleAfterPublish : goneAfterPublish).add(c.resultAfterPublishMs());
		}
		try {
			Files.createDirectories(file.getParent());
			boolean fresh = !Files.exists(file) || Files.size(file) == 0;
			StringBuilder sb = new StringBuilder();
			if (fresh) {
				sb.append(HEADER).append('\n');
			}
			sb.append(row(c)).append('\n');
			Files.writeString(file, sb, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			BwLog.throttled(BwLog.Level.WARN, "autoview-csv", "Could not write " + file + ": " + e, null);
		}
	}

	static String row(Check c) {
		return String.join(",", List.of(
				CsvExporter.escape(TimeFmt.iso(c.checkedAtMs())),
				CsvExporter.escape(c.auctionUuid()),
				CsvExporter.escape(c.item()),
				CsvExporter.escape(c.price()),
				CsvExporter.escape(c.estNetProfit()),
				CsvExporter.escape(c.result().name()),
				CsvExporter.escape(c.result().stillForSale == null ? "" : c.result().stillForSale ? "yes" : "no"),
				CsvExporter.escape(c.commandAfterPublishMs()),
				CsvExporter.escape(c.resultAfterPublishMs()),
				CsvExporter.escape(c.resultAfterCommandMs()),
				CsvExporter.escape(c.pageToCommandMs()),
				CsvExporter.escape(c.publishUncertaintyMs()),
				CsvExporter.escape(c.title()),
				CsvExporter.escape(c.buttonItem()),
				CsvExporter.escape(c.buttonName()),
				CsvExporter.escape(String.join(" | ", c.buttonLore())),
				CsvExporter.escape(String.join(" | ", c.itemStatusLore())),
				CsvExporter.escape(String.join(" | ", c.chat()))));
	}

	public synchronized List<String> summaryLines() {
		List<String> out = new ArrayList<>();
		int total = counts.values().stream().mapToInt(Integer::intValue).sum();
		int forSale = sum(Result.BUYABLE, Result.CANT_AFFORD, Result.PURCHASE_DELAY);
		int gone = sum(Result.SOLD, Result.ENDED, Result.NOT_FOUND);
		out.add("Speed checks this session: " + total + " (results in " + file.getFileName() + ")");
		if (forSale + gone > 0) {
			out.add(String.format(java.util.Locale.ROOT, "  Still for sale when opened: %d of %d (%.0f%%) - buyable %d, can't afford %d, purchase delay %d",
					forSale, forSale + gone, 100.0 * forSale / (forSale + gone), n(Result.BUYABLE), n(Result.CANT_AFFORD),
					n(Result.PURCHASE_DELAY)));
			out.add("  Already gone: " + gone + " - sold " + n(Result.SOLD) + ", ended " + n(Result.ENDED) + ", not found "
					+ n(Result.NOT_FOUND));
		}
		int other = total - forSale - gone;
		if (other > 0) {
			StringBuilder sb = new StringBuilder("  Inconclusive: " + other + " -");
			for (Result r : Result.values()) {
				if (r.stillForSale == null && n(r) > 0) {
					sb.append(' ').append(r.name().toLowerCase(java.util.Locale.ROOT)).append(' ').append(n(r));
				}
			}
			out.add(sb.toString());
		}
		if (!forSaleAfterPublish.isEmpty()) {
			out.add("  Opened (still for sale) after publish: " + stats(forSaleAfterPublish));
		}
		if (!goneAfterPublish.isEmpty()) {
			out.add("  Opened (already gone) after publish: " + stats(goneAfterPublish));
		}
		return out;
	}

	private static String stats(List<Long> values) {
		Stats.Summary s = Stats.summarize(values);
		return "median " + AuctionCollector.secs(s.median()) + ", p90 " + AuctionCollector.secs(s.p90()) + ", n=" + s.count();
	}

	private int sum(Result... rs) {
		int t = 0;
		for (Result r : rs) {
			t += n(r);
		}
		return t;
	}

	private int n(Result r) {
		return counts.getOrDefault(r, 0);
	}
}
