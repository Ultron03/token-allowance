package io.github.ultron03.tokenallowance.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class BudgetKeyTest {

	@Test
	void timesInSameWindowGiveSameKey() {

		Instant now = Instant.parse("2026-10-11T15:30:00Z");
		Instant now2 = Instant.parse("2026-10-11T16:30:00Z");
		BudgetKey key1 = BudgetKey.of(Scope.USER, "1", Window.DAILY, now, ZoneOffset.UTC);
		BudgetKey key2 = BudgetKey.of(Scope.USER, "1", Window.DAILY, now2, ZoneOffset.UTC);

		assertThat(key1).isEqualTo(key2);
	}

	@Test
	void keysInDifferentWindowsAreNotEqual() {
		Instant now = Instant.parse("2026-10-11T15:30:00Z");

		Instant yesterday = Instant.parse("2026-10-10T15:30:00Z");
		BudgetKey key1 = BudgetKey.of(Scope.USER, "1", Window.DAILY, now, ZoneOffset.UTC);
		BudgetKey key2 = BudgetKey.of(Scope.USER, "1", Window.DAILY, yesterday, ZoneOffset.UTC);

		assertThat(key1).isNotEqualTo(key2);
	}

	@Test
	void rejectsBlankSubjectId() {
		assertThatThrownBy(() -> new BudgetKey(Scope.USER, "", Window.DAILY, Instant.now()))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsNullSubjectId() {
		assertThatThrownBy(() -> new BudgetKey(Scope.USER, null, Window.DAILY, Instant.now()))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void ofComputesWindowStart() {
		Instant now = Instant.parse("2026-10-11T15:30:00Z");
		assertThat(BudgetKey.of(Scope.USER, "1", Window.DAILY, now, ZoneOffset.UTC).windowStart())
				.isEqualTo("2026-10-11T00:00:00Z");
	}

	@Test
	void globalKeysIgnoreSubjectId() {
		Instant now = Instant.parse("2026-10-11T15:30:00Z");

		BudgetKey key1 = BudgetKey.of(Scope.GLOBAL, "1", Window.DAILY, now, ZoneOffset.UTC);
		BudgetKey key2 = BudgetKey.of(Scope.GLOBAL, "2", Window.DAILY, now, ZoneOffset.UTC);

		assertThat(key1.subjectId()).isEqualTo(key2.subjectId());
	}
}
