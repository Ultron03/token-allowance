package io.github.ultron03.tokenallowance.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import io.github.ultron03.tokenallowance.core.BudgetKey;
import io.github.ultron03.tokenallowance.core.Scope;
import io.github.ultron03.tokenallowance.core.Window;

class RedisKeysTest {

	private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");

	@Test
	void buildsBudgetKeyWithSubjectLast() {
		BudgetKey key = BudgetKey.of(Scope.USER, "alice", Window.DAILY, NOW, ZoneOffset.UTC);

		assertThat(RedisKeys.budget(key)).isEqualTo("{ta}:budget:user:daily:1793404800:alice");
	}

	@Test
	void buildsGlobalKeyWithGlobalSubject() {
		BudgetKey key = BudgetKey.of(Scope.GLOBAL, "anything", Window.MONTHLY, NOW, ZoneOffset.UTC);

		assertThat(RedisKeys.budget(key)).startsWith("{ta}:budget:global:monthly:").endsWith(":global");
	}

	@Test
	void keepsSubjectWithColonsAndBracesAfterHashTag() {
		BudgetKey key = BudgetKey.of(Scope.USER, "a:{b}", Window.DAILY, NOW, ZoneOffset.UTC);

		assertThat(RedisKeys.budget(key)).startsWith("{ta}:").endsWith(":1793404800:a:{b}");
	}

	@Test
	void expiresDailyKeyOneDayAfterWindowEnd() {
		BudgetKey key = BudgetKey.of(Scope.USER, "alice", Window.DAILY, NOW, ZoneOffset.UTC);

		assertThat(RedisKeys.expiresAt(key, ZoneOffset.UTC)).isEqualTo(Instant.parse("2026-11-02T00:00:00Z"));
	}

	@Test
	void expiresDailyKeyByWindowEndInConfiguredZone() {
		ZoneId kolkata = ZoneId.of("Asia/Kolkata");
		BudgetKey key = BudgetKey.of(Scope.USER, "alice", Window.DAILY, NOW, kolkata);

		assertThat(RedisKeys.expiresAt(key, kolkata)).isEqualTo(Instant.parse("2026-11-01T18:30:00Z"));
	}

	@Test
	void expiresMonthlyKeyOneDayAfterMonthEnd() {
		Instant midMonth = Instant.parse("2026-10-15T12:00:00Z");
		BudgetKey key = BudgetKey.of(Scope.USER, "alice", Window.MONTHLY, midMonth, ZoneOffset.UTC);

		assertThat(RedisKeys.expiresAt(key, ZoneOffset.UTC)).isEqualTo(Instant.parse("2026-11-02T00:00:00Z"));
	}

	@Test
	void expiresMinuteKeyOneHourAfterWindowEnd() {
		BudgetKey key = BudgetKey.of(Scope.USER, "alice", Window.MINUTE, NOW, ZoneOffset.UTC);

		assertThat(RedisKeys.expiresAt(key, ZoneOffset.UTC)).isEqualTo(Instant.parse("2026-10-31T13:01:00Z"));
	}

	@Test
	void buildsReservationKey() {
		assertThat(RedisKeys.reservation("3f2a")).isEqualTo("{ta}:reservation:3f2a");
	}
}
