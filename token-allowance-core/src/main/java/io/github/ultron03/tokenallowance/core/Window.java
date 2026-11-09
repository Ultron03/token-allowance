package io.github.ultron03.tokenallowance.core;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Calendar period a budget resets on, in the configured zone. Windows are fixed, not sliding: a
 * subject can use a full cap just before a window ends and another just after.
 */
public enum Window {

	/** Resets at the start of every minute; caps bursts. */
	MINUTE,

	/** Resets at the start of every hour in the zone. */
	HOUR,

	/** Resets every day at midnight. */
	DAILY,

	/** Resets on the first day of every month at midnight. */
	MONTHLY;

	/** {@return the first instant of the window that contains {@code now}} */
	public Instant startOf(Instant now, ZoneId zone) {
		return start(ZonedDateTime.ofInstant(now, zone)).toInstant();
	}

	/** {@return the first instant of the next window; the current window ends just before it} */
	public Instant endOf(Instant now, ZoneId zone) {
		ZonedDateTime start = start(ZonedDateTime.ofInstant(now, zone));
		ZonedDateTime next = switch (this) {
			case MINUTE -> start.plusMinutes(1);
			case HOUR -> start.plusHours(1);
			case DAILY -> start.toLocalDate().plusDays(1).atStartOfDay(zone);
			case MONTHLY -> start.toLocalDate().plusMonths(1).atStartOfDay(zone);
		};
		return next.toInstant();
	}

	private ZonedDateTime start(ZonedDateTime time) {
		return switch (this) {
			case MINUTE -> time.truncatedTo(ChronoUnit.MINUTES);
			case HOUR -> time.truncatedTo(ChronoUnit.HOURS);
			case DAILY -> time.toLocalDate().atStartOfDay(time.getZone());
			case MONTHLY -> time.toLocalDate().withDayOfMonth(1).atStartOfDay(time.getZone());
		};
	}
}
