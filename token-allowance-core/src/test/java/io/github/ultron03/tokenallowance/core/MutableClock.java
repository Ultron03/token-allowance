package io.github.ultron03.tokenallowance.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock that tests move forward by hand, so window rollover needs no waiting. */
final class MutableClock extends Clock {

	private Instant now;

	private final ZoneId zone;

	MutableClock(Instant start) {
		this(start, ZoneOffset.UTC);
	}

	private MutableClock(Instant start, ZoneId zone) {
		this.now = start;
		this.zone = zone;
	}

	void advance(Duration duration) {
		this.now = this.now.plus(duration);
	}

	@Override
	public Instant instant() {
		return this.now;
	}

	@Override
	public ZoneId getZone() {
		return this.zone;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return new MutableClock(this.now, zone);
	}
}
