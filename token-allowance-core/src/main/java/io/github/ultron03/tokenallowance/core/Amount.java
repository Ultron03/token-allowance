package io.github.ultron03.tokenallowance.core;

import java.util.Objects;

/**
 * Tokens and money counted against a budget: an estimate before a call, or the actual use after it.
 *
 * @param tokens input plus output tokens
 * @param cost what those tokens cost
 */
public record Amount(long tokens, Money cost) {

	/** Nothing used. */
	public static final Amount ZERO = new Amount(0, Money.ZERO);

	/**
	 * Validates the fields.
	 * @throws IllegalArgumentException if {@code tokens} is negative
	 */
	public Amount {
		Objects.requireNonNull(cost, "cost can't be null");
		if (tokens < 0) {
			throw new IllegalArgumentException("tokens can't be negative: " + tokens);
		}
	}

	/**
	 * Adds two amounts.
	 * @throws ArithmeticException on overflow
	 */
	public Amount plus(Amount other) {
		return new Amount(Math.addExact(this.tokens, other.tokens), this.cost.plus(other.cost));
	}

	/**
	 * Subtracts an amount.
	 * @throws IllegalArgumentException if the result would be negative
	 */
	public Amount minus(Amount other) {
		return new Amount(Math.subtractExact(this.tokens, other.tokens), this.cost.minus(other.cost));
	}
}
