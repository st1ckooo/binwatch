package dev.binwatch;

import dev.binwatch.util.Stats;
import dev.binwatch.util.TimeFmt;
import dev.binwatch.util.Timing;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimingTest {
	static final long NOW = 1_793_310_186_275L;

	@Test
	void formatsSecondsMinutesHours() {
		assertEquals("0.4s", TimeFmt.duration(400));
		assertEquals("8s", TimeFmt.duration(8_000));
		assertEquals("59s", TimeFmt.duration(59_999));
		assertEquals("1m 12s", TimeFmt.duration(72_000));
		assertEquals("1h 0m 5s", TimeFmt.duration(3_605_000));
		assertEquals("2h 5m 3s", TimeFmt.duration(2 * 3_600_000 + 5 * 60_000 + 3_000));
		assertEquals("1d 2h 3m", TimeFmt.duration(86_400_000L + 2 * 3_600_000 + 3 * 60_000));
		assertThrows(IllegalArgumentException.class, () -> TimeFmt.duration(-1));
		assertEquals("+3s", TimeFmt.signedDuration(3_000));
		assertEquals("-1m 2s", TimeFmt.signedDuration(-62_000));
		assertEquals("0s", TimeFmt.signedDuration(0));
	}

	@Test
	void validDelay() {
		Timing.Delay d = Timing.delay(NOW - 72_000, NOW, NOW, 5000);
		assertTrue(d.valid());
		assertEquals(72_000, d.millis());
		assertEquals("API observed 1m 12s after recorded auction start", Timing.apiDelayLabel(d));
	}

	@Test
	void missingInvalidAndFutureTimestamps() {
		assertEquals(Timing.Status.MISSING_START, Timing.delay(null, NOW, NOW, 5000).status());
		assertEquals(Timing.Status.MISSING_START, Timing.delay(0L, NOW, NOW, 5000).status());
		assertEquals(Timing.Status.MISSING_OBSERVATION, Timing.delay(NOW, null, NOW, 5000).status());
		assertEquals(Timing.Status.INVALID_TIMESTAMP, Timing.delay(1_000L, NOW, NOW, 5000).status());
		assertEquals(Timing.Status.START_IN_FUTURE, Timing.delay(NOW + 60_000, NOW + 61_000, NOW, 5000).status());
		assertEquals(Timing.Status.INVALID_TIMESTAMP, Timing.delay(NOW - 1000, NOW + 60_000, NOW, 5000).status());
		// Clock skew: observation before start must never be shown as a negative duration.
		Timing.Delay skew = Timing.delay(NOW, NOW - 2_000, NOW, 5000);
		assertFalse(skew.valid());
		assertEquals(Timing.Status.OBSERVED_BEFORE_START, skew.status());
		assertNull(skew.millisOrNull());
		assertTrue(Timing.apiDelayLabel(skew).startsWith("Unavailable"));
		assertFalse(Timing.apiDelayLabel(skew).contains("-"));
	}

	@Test
	void medianAndP90() {
		Stats.Summary s = Stats.summarize(List.of(5L, 1L, 3L, 2L, 4L, 6L, 7L, 8L, 9L, 10L));
		assertEquals(10, s.count());
		assertEquals(5L, s.median());
		assertEquals(9L, s.p90());
		assertEquals(1L, s.min());
		assertEquals(10L, s.max());
		assertEquals(0, Stats.summarize(List.of()).count());
		assertEquals(-2L, Stats.summarize(List.of(-3L, -1L)).median());
	}
}
