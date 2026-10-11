package io.github.ultron03.tokenallowance.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Calendar period a budget resets on. Windows start at midnight in the configured zone.
 */
public enum Window {

	/** Resets every day. */
	DAILY,

	/** Resets on the first day of every month. */
	MONTHLY;

	/** {@return the first instant of the window that contains {@code now}} */
	public Instant startOf(Instant now, ZoneId zone) {
		LocalDate thatDate = LocalDate.ofInstant(now, zone);
		LocalDate day = firstDay(thatDate);
		return day.atStartOfDay(zone).toInstant();
	}

	/** {@return the first instant of the next window; the current window ends just before it} */
	public Instant endOf(Instant now, ZoneId zone) {
		LocalDate thatDate = LocalDate.ofInstant(now, zone);
		LocalDate firstDay = firstDay(thatDate);
		LocalDate nextFirstDay = nextFirstDay(firstDay);
		return nextFirstDay.atStartOfDay(zone).toInstant();
	}

	private LocalDate firstDay(LocalDate date) {
		return switch (this) {
			case DAILY -> date;
			case MONTHLY -> date.withDayOfMonth(1);
		};
	}

	private LocalDate nextFirstDay(LocalDate firstDay) {
		return switch (this) {
			case DAILY -> firstDay.plusDays(1);
			case MONTHLY -> firstDay.plusMonths(1);
		};
	}
}
