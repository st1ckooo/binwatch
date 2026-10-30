package dev.binwatch.util;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** UTC timestamp and duration formatting shared by chat output, Discord embeds and reports. */
public final class TimeFmt {
	private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT)
			.withZone(ZoneOffset.UTC);

	private TimeFmt() {
	}

	/** Human readable UTC timestamp, or {@code fallback} when the value is missing. */
	public static String utc(Long epochMs, String fallback) {
		return epochMs == null ? fallback : UTC.format(Instant.ofEpochMilli(epochMs));
	}

	public static String utc(Long epochMs) {
		return utc(epochMs, "Not observed");
	}

	/** ISO-8601 UTC for CSV exports; empty when missing. */
	public static String iso(Long epochMs) {
		return epochMs == null ? "" : Instant.ofEpochMilli(epochMs).toString();
	}

	/**
	 * Formats a non-negative duration, e.g. {@code 0.4s}, {@code 8s}, {@code 1m 12s}, {@code 2h 5m 3s}, {@code 3d 4h 0m}.
	 * Negative inputs are rejected so a misleading negative delay can never be rendered by accident.
	 */
	public static String duration(long ms) {
		if (ms < 0) {
			throw new IllegalArgumentException("negative duration: " + ms);
		}
		if (ms < 1000) {
			return String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
		}
		long totalSeconds = ms / 1000;
		long days = totalSeconds / 86_400;
		long hours = (totalSeconds % 86_400) / 3600;
		long minutes = (totalSeconds % 3600) / 60;
		long seconds = totalSeconds % 60;
		if (days > 0) {
			return days + "d " + hours + "h " + minutes + "m";
		}
		if (hours > 0) {
			return hours + "h " + minutes + "m " + seconds + "s";
		}
		if (minutes > 0) {
			return minutes + "m " + seconds + "s";
		}
		return seconds + "s";
	}

	/** Signed variant used for observation-time differences, e.g. {@code +3s}, {@code -1m 2s}, {@code 0s}. */
	public static String signedDuration(long ms) {
		if (ms == 0) {
			return "0s";
		}
		return (ms > 0 ? "+" : "-") + duration(Math.abs(ms));
	}

	/** Grouped coin formatting: 1,234,567. */
	public static String coins(long coins) {
		return String.format(Locale.ROOT, "%,d", coins);
	}

	/** Short coin formatting for chat: 1.23M, 450.5k. */
	public static String shortCoins(long coins) {
		long abs = Math.abs(coins);
		String sign = coins < 0 ? "-" : "";
		if (abs >= 1_000_000_000L) {
			return sign + String.format(Locale.ROOT, "%.2fB", abs / 1e9);
		}
		if (abs >= 1_000_000L) {
			return sign + String.format(Locale.ROOT, "%.2fM", abs / 1e6);
		}
		if (abs >= 1_000L) {
			return sign + String.format(Locale.ROOT, "%.1fk", abs / 1e3);
		}
		return sign + abs;
	}
}
