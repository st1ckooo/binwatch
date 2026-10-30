package dev.binwatch.export;

import dev.binwatch.store.ObservationStore;
import dev.binwatch.util.TimeFmt;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Writes CSV exports of API observations and alerts. All timestamps are ISO-8601 UTC. */
public final class CsvExporter {
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);

	private CsvExporter() {
	}

	public static List<Path> exportAll(ObservationStore store, Path dir, long sinceMs, long nowMs, int maxRows) throws IOException, SQLException {
		Files.createDirectories(dir);
		String stamp = STAMP.format(Instant.ofEpochMilli(nowMs));
		List<Path> out = new ArrayList<>();
		out.add(write(dir.resolve("api_observations_" + stamp + ".csv"), w -> {
			w.row("source", "auction_uuid", "item_uuid", "item_id", "item_name", "tier", "bin", "price", "count",
					"auction_start_utc", "auction_end_utc", "first_api_observed_utc", "api_observed_delay_ms",
					"snapshot_last_updated_utc", "snapshot_id", "poll_id", "page", "api_source_status", "observation_kind");
			store.forEachApiObservation(sinceMs, maxRows, a -> w.row("API", a.auctionUuid(), a.itemUuid(), a.itemId(),
					a.itemName(), a.tier(), a.bin(), a.startingBid(), a.count(), TimeFmt.iso(a.auctionStartMs()),
					TimeFmt.iso(a.auctionEndMs()), TimeFmt.iso(a.firstApiObservedMs()),
					a.auctionStartMs() != null && a.firstApiObservedMs() >= a.auctionStartMs()
							? a.firstApiObservedMs() - a.auctionStartMs() : "",
					TimeFmt.iso(a.snapshotLastUpdatedMs()), a.snapshotId(), a.pollId(), a.page(), a.sourceStatus(),
					a.observationKind()));
		}));
		out.add(write(dir.resolve("flip_alerts_" + stamp + ".csv"), w -> {
			w.row("auction_uuid", "item_name", "price", "estimated_net_profit", "status", "created_utc", "last_attempt_utc",
					"attempts", "delivered_utc", "detail", "norm_key");
			store.forEachAlert(sinceMs, maxRows, a -> w.row(a.auctionUuid(), a.itemName(), a.price(), a.estimatedNetProfit(),
					a.status(), TimeFmt.iso(a.createdMs()), TimeFmt.iso(a.lastAttemptMs()), a.attempts(),
					TimeFmt.iso(a.deliveredMs()), a.detail(), a.normKey()));
		}));
		return out;
	}

	public static Path writeText(Path file, List<String> lines) throws IOException {
		Files.createDirectories(file.toAbsolutePath().getParent());
		Files.write(file, lines, StandardCharsets.UTF_8);
		return file;
	}

	public static String stamp(long nowMs) {
		return STAMP.format(Instant.ofEpochMilli(nowMs));
	}

	interface Body {
		void write(Writer w) throws SQLException;
	}

	static final class Writer {
		private final BufferedWriter out;

		Writer(BufferedWriter out) {
			this.out = out;
		}

		void row(Object... values) {
			try {
				for (int i = 0; i < values.length; i++) {
					if (i > 0) {
						out.write(',');
					}
					out.write(escape(values[i]));
				}
				out.write("\r\n");
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
	}

	/** RFC 4180 quoting; also neutralises spreadsheet formula injection from item names. */
	public static String escape(Object value) {
		if (value == null) {
			return "";
		}
		String s = value.toString();
		if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0 && !(value instanceof Number)) {
			s = "'" + s;
		}
		if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
			return "\"" + s.replace("\"", "\"\"") + "\"";
		}
		return s;
	}

	private static Path write(Path file, Body body) throws IOException, SQLException {
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try (BufferedWriter bw = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			body.write(new Writer(bw));
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		return file;
	}
}
