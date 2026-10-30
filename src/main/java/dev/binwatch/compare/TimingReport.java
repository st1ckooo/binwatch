package dev.binwatch.compare;

import dev.binwatch.store.ObservationStore;
import dev.binwatch.util.Stats;
import dev.binwatch.util.TimeFmt;
import dev.binwatch.util.Timing;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * API observation-timing statistics over a time window.
 *
 * @param conditions free-form lines describing how the data was collected (poll mode)
 */
public record TimingReport(
		long fromMs,
		long toMs,
		long apiObservations,
		long apiNewDuringMonitoring,
		int apiInvalidDelays,
		Stats.Summary apiDelay,
		Long firstObservationMs,
		Long lastObservationMs,
		List<String> conditions) {

	public static TimingReport build(ObservationStore store, long sinceMs, long nowMs, long skewToleranceMs) throws SQLException {
		return build(store, sinceMs, nowMs, skewToleranceMs, List.of());
	}

	public static TimingReport build(ObservationStore store, long sinceMs, long nowMs, long skewToleranceMs,
			List<String> conditions) throws SQLException {
		long[] apiCount = {0};
		long[] range = {Long.MAX_VALUE, Long.MIN_VALUE};
		store.forEachApiObservation(sinceMs, Integer.MAX_VALUE, a -> {
			apiCount[0]++;
			range[0] = Math.min(range[0], a.firstApiObservedMs());
			range[1] = Math.max(range[1], a.firstApiObservedMs());
		});
		List<long[]> samples = store.apiTimingSamples(sinceMs);
		List<Long> apiDelays = new ArrayList<>();
		int invalid = 0;
		for (long[] s : samples) {
			Timing.Delay d = Timing.delay(s[0], s[1], nowMs, skewToleranceMs);
			if (d.valid()) {
				apiDelays.add(d.millis());
			} else {
				invalid++;
			}
		}
		boolean any = range[0] != Long.MAX_VALUE;
		return new TimingReport(sinceMs, nowMs, apiCount[0], samples.size(), invalid, Stats.summarize(apiDelays),
				any ? range[0] : null, any ? range[1] : null, List.copyOf(conditions));
	}

	public List<String> toLines() {
		List<String> out = new ArrayList<>();
		out.add("BIN Watch timing report");
		out.add("Window: " + TimeFmt.utc(fromMs) + " to " + TimeFmt.utc(toMs));
		out.add(firstObservationMs == null ? "Observations in window: none"
				: "Observations from " + TimeFmt.utc(firstObservationMs) + " to " + TimeFmt.utc(lastObservationMs)
						+ " (" + TimeFmt.duration(lastObservationMs - firstObservationMs) + ")");
		for (String c : conditions) {
			out.add("  " + c);
		}
		out.add("");
		out.add("API source (public /v2/skyblock/auctions):");
		out.add("  Auctions first observed: " + apiObservations);
		out.add("  Created during continuous monitoring (used for delay stats): " + apiNewDuringMonitoring
				+ " (" + apiInvalidDelays + " with invalid timestamps excluded)");
		out.add("  Time from recorded auction start to first API observation: " + summary(apiDelay));
		out.add("");
		out.add("Limitations:");
		out.add("  * Delays include Hypixel's 60-second snapshot cycle and its publishing delay, plus this monitor's polling;");
		out.add("    auction start is the auction's recorded start time, not the moment Hypixel published it.");
		out.add("  * Auctions that already existed when first seen are excluded from delay statistics.");
		return out;
	}

	private static String summary(Stats.Summary s) {
		if (s.count() == 0) {
			return "no valid samples";
		}
		return "median " + TimeFmt.duration(s.median()) + ", p90 " + TimeFmt.duration(s.p90()) + ", n=" + s.count();
	}
}
