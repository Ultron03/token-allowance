package io.github.ultron03.tokenallowance.core;

import java.math.BigDecimal;

/**
 * A non-negative amount of money in micro-dollars (millionths of a dollar).
 * Stored as a {@code long} so sums of tiny per-token costs stay exact.
 *
 * @param micros the amount in micro-dollars
 */
public record Money(long micros) {

	private static final int MICROS_SCALE = 6;

	/** No money. */
	public static final Money ZERO = new Money(0);

	/**
	 * Validates the amount.
	 * @throws IllegalArgumentException if {@code micros} is negative
	 */
	public Money {
		if (micros < 0) {
			throw new IllegalArgumentException("Money can't be negative");
		}
	}

	/**
	 * Parses a dollar amount such as {@code "1.50"}. Takes a {@code String}, not a {@code double},
	 * to stay exact.
	 * @throws NumberFormatException if {@code amount} is not a number
	 * @throws ArithmeticException if it has more than six decimal places or is too large
	 * @throws IllegalArgumentException if it is negative
	 */
	public static Money ofDollars(String amount) {
		BigDecimal decimalAmount = new BigDecimal(amount);
		long exactMicros = decimalAmount.movePointRight(MICROS_SCALE).longValueExact();
		return new Money(exactMicros);
	}

	/**
	 * Adds two amounts.
	 * @throws ArithmeticException on overflow
	 */
	public Money plus(Money other) {
		return new Money(Math.addExact(this.micros, other.micros));
	}

	/**
	 * Subtracts an amount.
	 * @throws IllegalArgumentException if the result would be negative
	 */
	public Money minus(Money other) {
		return new Money(Math.subtractExact(this.micros, other.micros));
	}

	/** {@return whether this amount is strictly greater than {@code other}} */
	public boolean isGreaterThan(Money other) {
		return this.micros > other.micros;
	}

	/** {@return the amount in dollars, for example {@code $1.500000}} */
	@Override
	public String toString() {
		return "$" + BigDecimal.valueOf(micros, MICROS_SCALE).toPlainString();
	}
}
