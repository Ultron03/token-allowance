package io.github.ultron03.tokenallowance.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ModelPriceTest {

	private static final Money TEN_CENTS = Money.ofDollars("0.10");

	private static final ModelPrice GPT_4O = new ModelPrice(Money.ofDollars("2.50"), Money.ofDollars("10.00"),
			Money.ofDollars("1.25"), null);

	@Test
	void chargesOneMillionInputTokensAtTheInputPrice() {
		ModelPrice price = new ModelPrice(TEN_CENTS, TEN_CENTS, null, null);

		Money cost = price.costOf(new TokenUsage(1_000_000, 0, 0, 0));

		assertThat(cost).isEqualTo(Money.ofDollars("0.10"));
	}

	@Test
	void roundsAFractionOfAMicroUp() {
		ModelPrice price = new ModelPrice(TEN_CENTS, TEN_CENTS, null, null);

		Money cost = price.costOf(new TokenUsage(1, 0, 0, 0));

		assertThat(cost).isEqualTo(new Money(1));
	}

	@Test
	void roundsOnceOnTheSumNotOncePerPart() {
		ModelPrice price = new ModelPrice(TEN_CENTS, TEN_CENTS, null, null);

		// 0.1 micro of input + 0.1 micro of output: 2 micros if each part were rounded, 1 when rounded once
		Money cost = price.costOf(new TokenUsage(1, 1, 0, 0));

		assertThat(cost).isEqualTo(new Money(1));
	}

	@Test
	void doesNotRoundUpAnExactMicro() {
		ModelPrice price = new ModelPrice(TEN_CENTS, TEN_CENTS, null, null);

		Money cost = price.costOf(new TokenUsage(10, 0, 0, 0));

		assertThat(cost).isEqualTo(new Money(1));
	}

	@Test
	void chargesCachedTokensOnlyAtTheCachedPrice() {
		// 200 x 2.50 + 1000 x 1.25 + 300 x 10.00 = 4_750_000_000 micros per MTok = 4750 micros
		Money cost = GPT_4O.costOf(new TokenUsage(1200, 300, 1000, 0));

		assertThat(cost).isEqualTo(new Money(4750));
	}

	@Test
	void chargesCachedTokensAtTheInputPriceWhenNoCachedPriceIsSet() {
		ModelPrice price = new ModelPrice(Money.ofDollars("2.00"), Money.ofDollars("8.00"), null, null);

		Money cost = price.costOf(new TokenUsage(1_000_000, 0, 1_000_000, 0));

		assertThat(cost).isEqualTo(Money.ofDollars("2.00"));
	}

	@Test
	void chargesCacheWriteTokensAtTheCacheWritePrice() {
		ModelPrice price = new ModelPrice(Money.ofDollars("3.00"), Money.ofDollars("15.00"),
				Money.ofDollars("0.30"), Money.ofDollars("3.75"));

		Money cost = price.costOf(new TokenUsage(1_000_000, 0, 0, 1_000_000));

		assertThat(cost).isEqualTo(Money.ofDollars("3.75"));
	}

	@Test
	void costsNothingForZeroUsage() {
		assertThat(GPT_4O.costOf(TokenUsage.ZERO)).isEqualTo(Money.ZERO);
	}

	@Test
	void throwsInsteadOfOverflowingOnHugeUsage() {
		TokenUsage huge = new TokenUsage(Long.MAX_VALUE / 2, 0, 0, 0);

		assertThatThrownBy(() -> GPT_4O.costOf(huge)).isInstanceOf(ArithmeticException.class);
	}

	@Test
	void rejectsMissingInputPrice() {
		assertThatThrownBy(() -> new ModelPrice(null, TEN_CENTS, null, null))
			.isInstanceOf(NullPointerException.class);
	}
}
