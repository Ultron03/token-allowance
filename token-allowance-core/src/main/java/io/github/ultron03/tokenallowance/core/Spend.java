package io.github.ultron03.tokenallowance.core;

import java.util.Objects;

/**
 * What one budget counter holds: spend from finished calls, and estimates held for calls in flight.
 *
 * @param committed the actual amount of settled calls
 * @param reserved the estimates of calls that are not settled yet
 */
public record Spend(Amount committed, Amount reserved) {

	/** Nothing spent or reserved. */
	public static final Spend ZERO = new Spend(Amount.ZERO, Amount.ZERO);

	/** Validates the fields. */
	public Spend {
		Objects.requireNonNull(committed, "committed can't be null");
		Objects.requireNonNull(reserved, "reserved can't be null");
	}

	/** {@return committed plus reserved; a new reservation is checked against this} */
	public Amount total() {
		return this.committed.plus(this.reserved);
	}

	/** {@return this spend with {@code amount} added to the reserved part} */
	public Spend plusReserved(Amount amount) {
		return new Spend(this.committed, this.reserved.plus(amount));
	}

	/** {@return this spend with {@code amount} removed from the reserved part} */
	public Spend minusReserved(Amount amount) {
		return new Spend(this.committed, this.reserved.minus(amount));
	}

	/** {@return this spend with {@code amount} added to the committed part} */
	public Spend plusCommitted(Amount amount) {
		return new Spend(this.committed.plus(amount), this.reserved);
	}
}
