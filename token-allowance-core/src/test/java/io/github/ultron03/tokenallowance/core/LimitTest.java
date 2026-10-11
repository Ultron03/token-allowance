package io.github.ultron03.tokenallowance.core;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
