package dev.binwatch;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Controllable clock for tests. */
public final class MutableClock extends Clock {
	private volatile long millis;

	public MutableClock(long millis) {
		this.millis = millis;
	}

	public void advance(long ms) {
		millis += ms;
	}

	public void set(long ms) {
		millis = ms;
	}

	@Override
	public long millis() {
		return millis;
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return this;
	}

	@Override
	public Instant instant() {
		return Instant.ofEpochMilli(millis);
	}
}
