package io.github.ultron03.tokenallowance.core;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RequestLimitTest {

	@Test
	void acceptsNoCaps() {
		assertThatCode(() -> new RequestLimit(null, null)).doesNotThrowAnyException();
	}

	@Test
	void acceptsBothCaps() {
		assertThatCode(() -> new RequestLimit(5, Money.ofDollars("0.20"))).doesNotThrowAnyException();
	}

	@Test
	void rejectsZeroMaxModelCalls() {
		assertThatThrownBy(() -> new RequestLimit(0, null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsNegativeMaxModelCalls() {
		assertThatThrownBy(() -> new RequestLimit(-1, null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsZeroMaxCost() {
		assertThatThrownBy(() -> new RequestLimit(null, Money.ZERO)).isInstanceOf(IllegalArgumentException.class);
	}
}
