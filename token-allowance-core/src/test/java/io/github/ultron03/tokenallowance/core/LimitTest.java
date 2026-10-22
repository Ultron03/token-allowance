package io.github.ultron03.tokenallowance.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class LimitTest {

	@Test
	void acceptsTokenLimitOnly() {
		assertThatCode(() -> new Limit(Scope.USER, Window.DAILY, 10L, null))
				.doesNotThrowAnyException();
	}

	@Test
	void acceptsCostLimitOnly() {
		Money money = new Money(10);
		assertThatCode(() -> new Limit(Scope.USER, Window.DAILY, null, money))
				.doesNotThrowAnyException();
	}

	@Test
	void acceptsBothLimits() {
		Money money = new Money(10);
		assertThatCode(() -> new Limit(Scope.USER, Window.DAILY, 10L, money))
				.doesNotThrowAnyException();
	}

	@Test
	void rejectsLimitWithNeitherTokensNorCost() {
		assertThatThrownBy(() -> new Limit(Scope.USER, Window.DAILY, null, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsZeroMaxTokens() {
		assertThatThrownBy(() -> new Limit(Scope.USER, Window.DAILY, 0L, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsNegativeMaxTokens() {
		assertThatThrownBy(() -> new Limit(Scope.USER, Window.DAILY, -1L, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsZeroMaxCost() {
		Money money = new Money(0);
		assertThatThrownBy(() -> new Limit(Scope.USER, Window.DAILY, 10L, money))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsNullScope() {
		assertThatThrownBy(() -> new Limit(null, Window.DAILY, 10L, null))
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	void isNotExceededByAmountThatReachesCapExactly() {
		Limit limit = new Limit(Scope.USER, Window.DAILY, 100L, Money.ofDollars("1.00"));

		assertThat(limit.isExceededBy(new Amount(100, Money.ofDollars("1.00")))).isFalse();
	}

	@Test
	void isExceededByTooManyTokens() {
		Limit limit = new Limit(Scope.USER, Window.DAILY, 100L, Money.ofDollars("1.00"));

		assertThat(limit.isExceededBy(new Amount(101, Money.ZERO))).isTrue();
	}

	@Test
	void isExceededByTooMuchCost() {
		Limit limit = new Limit(Scope.USER, Window.DAILY, 100L, Money.ofDollars("1.00"));

		assertThat(limit.isExceededBy(new Amount(0, Money.ofDollars("1.000001")))).isTrue();
	}

	@Test
	void ignoresCapThatIsNotSet() {
		Limit tokenOnly = new Limit(Scope.USER, Window.DAILY, 100L, null);

		assertThat(tokenOnly.isExceededBy(new Amount(10, Money.ofDollars("999.00")))).isFalse();
	}

	@Test
	void isReachedByAmountEqualToCap() {
		Limit limit = new Limit(Scope.USER, Window.DAILY, 100L, Money.ofDollars("1.00"));

		assertThat(limit.isReachedBy(new Amount(100, Money.ZERO))).isTrue();
		assertThat(limit.isReachedBy(new Amount(0, Money.ofDollars("1.00")))).isTrue();
	}

	@Test
	void isNotReachedByAmountBelowEveryCap() {
		Limit limit = new Limit(Scope.USER, Window.DAILY, 100L, Money.ofDollars("1.00"));

		assertThat(limit.isReachedBy(new Amount(99, Money.ofDollars("0.999999")))).isFalse();
	}

	@Test
	void isReachedByIgnoresCapThatIsNotSet() {
		Limit costOnly = new Limit(Scope.USER, Window.DAILY, null, Money.ofDollars("1.00"));

		assertThat(costOnly.isReachedBy(new Amount(1_000_000, Money.ZERO))).isFalse();
	}

	@Test
	void appliesOnlyToKeyWithSameScopeAndWindow() {
		Limit limit = new Limit(Scope.USER, Window.DAILY, 100L, null);
		Instant now = Instant.parse("2026-10-22T10:00:00Z");

		assertThat(limit.appliesTo(BudgetKey.of(Scope.USER, "alice", Window.DAILY, now, ZoneOffset.UTC))).isTrue();
		assertThat(limit.appliesTo(BudgetKey.of(Scope.USER, "alice", Window.MONTHLY, now, ZoneOffset.UTC))).isFalse();
		assertThat(limit.appliesTo(BudgetKey.of(Scope.TENANT, "acme", Window.DAILY, now, ZoneOffset.UTC))).isFalse();
	}
}
