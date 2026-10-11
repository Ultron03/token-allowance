package io.github.ultron03.tokenallowance.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TokenUsageTest {

	@Test
	void negativeInputThrowsIllegalArgumentException() {
		assertThatThrownBy(() -> new TokenUsage(-1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void negativeOutputThrowsIllegalArgumentException() {
		assertThatThrownBy(() -> new TokenUsage(0, -1, 0)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void negativeCachedInputThrowsIllegalArgumentException() {
		assertThatThrownBy(() -> new TokenUsage(0, 0, -1)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void cachedInputGreaterThanInputThrowsIllegalArgumentException() {
		assertThatThrownBy(() -> new TokenUsage(1, 0, 2)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void cachedEqualToInputIsAllowed() {
		TokenUsage usage = new TokenUsage(2, 0, 2);
		assertThat(usage.cachedInput()).isEqualTo(2);
	}

	@Test
	void totalReturnsCorrectSum() {
		TokenUsage usage = new TokenUsage(5, 10, 3);
		assertThat(usage.total()).isEqualTo(15);
	}

	@Test
	void plusReturnsCorrectSum() {
		TokenUsage usage1 = new TokenUsage(5, 10, 3);
		TokenUsage usage2 = new TokenUsage(2, 4, 1);
		TokenUsage result = usage1.plus(usage2);
		assertThat(result).isEqualTo(new TokenUsage(7, 14, 4));
	}

	@Test
	void isZeroReturnsTrueForZeroUsage() {
		TokenUsage usage = TokenUsage.ZERO;
		assertThat(usage.isZero()).isTrue();
	}

	@Test
	void isZeroReturnsFalseForNonZeroUsage() {
		TokenUsage usage = new TokenUsage(1, 0, 0);
		assertThat(usage.isZero()).isFalse();
	}

	@Test
	void plusThrowsArithmeticExceptionOnOverflow() {
		TokenUsage usage1 = new TokenUsage(Long.MAX_VALUE, 0, 0);
		TokenUsage usage2 = new TokenUsage(1, 0, 0);
		assertThatThrownBy(() -> usage1.plus(usage2)).isInstanceOf(ArithmeticException.class);
	}
}
