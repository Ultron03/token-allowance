package io.github.ultron03.tokenallowance.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AmountTest {

	@Test
	void rejectsNegativeTokens() {
		assertThatThrownBy(() -> new Amount(-1, Money.ZERO)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void plusAddsTokensAndCost() {
		Amount sum = new Amount(10, Money.ofDollars("0.10")).plus(new Amount(5, Money.ofDollars("0.05")));

		assertThat(sum).isEqualTo(new Amount(15, Money.ofDollars("0.15")));
	}

	@Test
	void minusRejectsResultBelowZero() {
		Amount small = new Amount(5, Money.ofDollars("0.05"));
		Amount large = new Amount(10, Money.ofDollars("0.10"));

		assertThatThrownBy(() -> small.minus(large)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void spendTotalAddsCommittedAndReserved() {
		Spend spend = new Spend(new Amount(30, Money.ofDollars("0.30")), new Amount(20, Money.ofDollars("0.20")));

		assertThat(spend.total()).isEqualTo(new Amount(50, Money.ofDollars("0.50")));
	}
}
