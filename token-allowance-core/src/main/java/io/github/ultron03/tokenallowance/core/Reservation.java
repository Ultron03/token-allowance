package io.github.ultron03.tokenallowance.core;

import java.util.Objects;
import java.util.Set;

/**
 * An estimate held on some budget counters until the call is settled with commit or release.
 *
 * @param id identifies the reservation so settling it twice has no effect
 * @param keys the counters the estimate is held on
 * @param amount the estimate held on each counter
 */
public record Reservation(String id, Set<BudgetKey> keys, Amount amount) {

	/**
	 * Validates the fields and copies {@code keys}.
	 * @throws IllegalArgumentException if {@code id} is blank
	 */
	public Reservation {
		Objects.requireNonNull(id, "id can't be null");
		Objects.requireNonNull(amount, "amount can't be null");
		if (id.isBlank()) {
			throw new IllegalArgumentException("id can't be blank");
		}
		keys = Set.copyOf(keys);
	}
}
