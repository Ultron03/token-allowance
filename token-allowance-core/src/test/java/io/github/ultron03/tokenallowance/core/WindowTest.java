package io.github.ultron03.tokenallowance.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class WindowTest {

	@Test
	void dailyWindowStartsAtMidnightUtc() {
		Instant now = Instant.parse("2026-10-11T15:30:00Z");

		Instant start = Window.DAILY.startOf(now, ZoneOffset.UTC);

		assertThat(start).isEqualTo(Instant.parse("2026-10-11T00:00:00Z"));
	}

	@Test
	void dailyWindowEndsAtNextMidnightUtc() {
		Instant now = Instant.parse("2026-10-11T15:30:00Z");

		Instant end = Window.DAILY.endOf(now, ZoneOffset.UTC);

		assertThat(end).isEqualTo(Instant.parse("2026-10-12T00:00:00Z"));
	}

	@Test
	void dailyWindowStartsAtExactlyMidnightUtc() {
		Instant now = Instant.parse("2026-10-11T00:00:00Z");

		Instant start = Window.DAILY.startOf(now, ZoneOffset.UTC);

		assertThat(start).isEqualTo(Instant.parse("2026-10-11T00:00:00Z"));
	}

	@Test
	void lastNanosecondBeforeMidnightBelongsToSameDay() {
		Instant now = Instant.parse("2026-10-11T23:59:59.999999999Z");

		Instant start = Window.DAILY.startOf(now, ZoneOffset.UTC);

		assertThat(start).isEqualTo(Instant.parse("2026-10-11T00:00:00Z"));
	}

	@Test
	void dailyWindowFollowsLocalMidnightInIndia() {
		Instant now = Instant.parse("2026-10-11T20:00:00Z");

		Instant start = Window.DAILY.startOf(now, ZoneId.of("Asia/Kolkata"));

		assertThat(start).isEqualTo(Instant.parse("2026-10-11T18:30:00Z"));
	}

	@Test
	void monthlyWindowRunsFromFirstToFirstOfNextMonth() {
		Instant now = Instant.parse("2026-01-31T12:00:00Z");

		assertThat(Window.MONTHLY.startOf(now, ZoneOffset.UTC)).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
		assertThat(Window.MONTHLY.endOf(now, ZoneOffset.UTC)).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
	}

	@Test
	void monthlyWindowInDecemberEndsInNextYear() {
		Instant now = Instant.parse("2026-12-15T12:00:00Z");

		Instant end = Window.MONTHLY.endOf(now, ZoneOffset.UTC);

		assertThat(end).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
	}

	@Test
	void dailyWindowOnLeapDayEndsOnFirstOfMarch() {
		Instant now = Instant.parse("2028-02-29T12:00:00Z");

		Instant end = Window.DAILY.endOf(now, ZoneOffset.UTC);

		assertThat(end).isEqualTo(Instant.parse("2028-03-01T00:00:00Z"));
	}

	@Test
	void dailyWindowIsTwentyThreeHoursOnDaylightSavingStart() {
		Instant now = Instant.parse("2026-03-08T12:00:00Z");
		ZoneId newYork = ZoneId.of("America/New_York");

		Instant start = Window.DAILY.startOf(now, newYork);
		Instant end = Window.DAILY.endOf(now, newYork);

		assertThat(start).isEqualTo(Instant.parse("2026-03-08T05:00:00Z"));
		assertThat(end).isEqualTo(Instant.parse("2026-03-09T04:00:00Z"));
		assertThat(Duration.between(start, end)).isEqualTo(Duration.ofHours(23));
	}

	@Test
	void minuteWindowRunsFromStartOfMinuteToNextMinute() {
		Instant now = Instant.parse("2026-10-11T15:30:42.5Z");

		assertThat(Window.MINUTE.startOf(now, ZoneOffset.UTC)).isEqualTo(Instant.parse("2026-10-11T15:30:00Z"));
		assertThat(Window.MINUTE.endOf(now, ZoneOffset.UTC)).isEqualTo(Instant.parse("2026-10-11T15:31:00Z"));
	}

	@Test
	void hourWindowRunsFromFullHourToNextHour() {
		Instant now = Instant.parse("2026-10-11T23:59:59Z");

		assertThat(Window.HOUR.startOf(now, ZoneOffset.UTC)).isEqualTo(Instant.parse("2026-10-11T23:00:00Z"));
		assertThat(Window.HOUR.endOf(now, ZoneOffset.UTC)).isEqualTo(Instant.parse("2026-10-12T00:00:00Z"));
	}

	@Test
	void hourWindowFollowsLocalHourInIndia() {
		Instant now = Instant.parse("2026-10-11T10:00:00Z");

		Instant start = Window.HOUR.startOf(now, ZoneId.of("Asia/Kolkata"));

		assertThat(start).isEqualTo(Instant.parse("2026-10-11T09:30:00Z"));
	}

	@Test
	void hourWindowIsOneHourWhenClocksGoBack() {
		Instant secondOneOClock = Instant.parse("2026-11-01T06:30:00Z");
		ZoneId newYork = ZoneId.of("America/New_York");

		Instant start = Window.HOUR.startOf(secondOneOClock, newYork);
		Instant end = Window.HOUR.endOf(secondOneOClock, newYork);

		assertThat(start).isEqualTo(Instant.parse("2026-11-01T06:00:00Z"));
		assertThat(end).isEqualTo(Instant.parse("2026-11-01T07:00:00Z"));
	}
}
