package io.github.ultron03.tokenallowance.core;

import java.time.Duration;
import java.util.Objects;

/** What {@link BudgetService} decided about a request or a model call. */
public sealed interface Decision {

	/** The request may go ahead; nothing is reserved yet. */
	record Allowed() implements Decision {
	}

	/**
	 * The model call may go ahead; its estimate is held until the call is settled.
	 *
	 * @param reservation the reservation to settle or release once the call ends
	 */
	record Reserved(Reservation reservation) implements Decision {

		/** Validates the field. */
		public Reserved {
			Objects.requireNonNull(reservation, "reservation can't be null");
		}
	}

	/**
	 * A budget is used up, so the call must not happen.
	 *
	 * @param reason which budget is used up; safe to show to the caller, it holds no subject id
	 * @param retryAfter how long until the budget's window resets
	 */
	record Denied(String reason, Duration retryAfter) implements Decision {

		/**
		 * Validates the fields.
		 * @throws IllegalArgumentException if {@code retryAfter} is negative
		 */
		public Denied {
			Objects.requireNonNull(reason, "reason can't be null");
			Objects.requireNonNull(retryAfter, "retryAfter can't be null");
			if (retryAfter.isNegative()) {
				throw new IllegalArgumentException("retryAfter can't be negative");
			}
		}
	}
}
