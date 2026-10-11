package io.github.ultron03.tokenallowance.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyTest {

	@Test
	void minusThrowsWhenResultWouldBeNegative() {
		Money money1 = Money.ofDollars("10.00");
		Money money2 = Money.ofDollars("5.00");
		assertThatThrownBy(() -> money2.minus(money1)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void additionAndSubtractionWorksCorrectly() {
		Money money1 = Money.ofDollars("10.00");
		Money money2 = Money.ofDollars("5.00");
		Money sum = money1.plus(money2);
		Money difference = money1.minus(money2);

		assertThat(sum).isEqualTo( Money.ofDollars("15.00"));
		assertThat(difference).isEqualTo( Money.ofDollars("5.00"));
	}

	@Test
	void isGreaterThanWorksCorrectly() {
		Money money1 = Money.ofDollars("10.00");
		Money money2 = Money.ofDollars("5.00");

		assertThat(money1.isGreaterThan(money2)).isTrue();
		assertThat(money2.isGreaterThan(money1)).isFalse();
	}

	@Test
	void addingHugeAmountsThrowsException() {
		Money money1 = new Money(Long.MAX_VALUE);
		Money money2 = new Money(1);
		assertThatThrownBy(() -> money1.plus(money2)).isInstanceOf(ArithmeticException.class);
	}

	@Test
	void stringToMicrosConversionWorksCorrectly() {
		Money money = Money.ofDollars("1.50");
		assertThat(money.micros()).isEqualTo(1_500_000);
	}

	@Test
	void ofDollarsAllowsSixDecimalPlacesButRejectsSeven() {
		assertThat(Money.ofDollars("0.000001")).isEqualTo(new Money(1));
		assertThatThrownBy(() -> Money.ofDollars("0.0000001")).isInstanceOf(ArithmeticException.class);
	}

	@Test
	void subtractingToZeroWorksCorrectly() {
		Money money1 = Money.ofDollars("10.00");
		Money money2 = Money.ofDollars("10.00");
		Money result = money1.minus(money2);
		assertThat(result).isEqualTo(Money.ZERO);
	}

	@Test
	void negativeInputThrowsIllegalArgumentException() {
		assertThatThrownBy(() -> Money.ofDollars("abc")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Money.ofDollars("-1.00")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void nonNumericInputThrowsNumberFormatException() {
		assertThatThrownBy(() -> Money.ofDollars("abc")).isInstanceOf(NumberFormatException.class);
	}
}
