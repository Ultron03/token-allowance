package io.github.ultron03.tokenallowance.core;

import java.util.Objects;

/** The outcome of {@link UsageLedger#reserve}: either reserved on every key, or on none. */
public sealed interface ReservationResult {

	/**
	 * Every budget had room; settle the reservation once the call ends.
	 *
	 * @param reservation the reservation to commit or release
	 */
	record Reserved(Reservation reservation) implements ReservationResult {

		/** Validates the field. */
		public Reserved {
			Objects.requireNonNull(reservation, "reservation can't be null");
		}
	}

	/**
	 * The estimate would go over a limit, so nothing was reserved.
	 *
	 * @param key the first counter found without room
	 * @param limit the limit that counter would exceed
	 */
	record Exceeded(BudgetKey key, Limit limit) implements ReservationResult {

		/** Validates the fields. */
		public Exceeded {
			Objects.requireNonNull(key, "key can't be null");
			Objects.requireNonNull(limit, "limit can't be null");
		}
	}
}
