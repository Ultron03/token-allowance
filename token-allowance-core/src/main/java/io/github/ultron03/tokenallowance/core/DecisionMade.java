package io.github.ultron03.tokenallowance.core;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * One decision of {@link BudgetService}. Holds no subject id, so it is safe to turn into metrics.
 *
 * @param stage where in the request the decision was made
 * @param outcome what was decided
 * @param scope the scope of the budget that was used up; null when none was
 */
public record DecisionMade(Stage stage, Outcome outcome, @Nullable Scope scope) {

	/** Where in the request a decision is made. */
	public enum Stage {

		/** Once per request, before any model call. */
		ADMISSION,

		/** Before each model call, against the per-request caps. */
		REQUEST,

		/** Before each model call, against the budgets. */
		RESERVATION
	}

	/** What was decided. */
	public enum Outcome {

		/** Within budget. */
		ALLOWED,

		/** Over budget in enforce mode. */
		DENIED,

		/** Over budget in observe mode, so let through. */
		OBSERVED,

		/** The ledger could not be reached; the failure mode decided. */
		LEDGER_UNAVAILABLE
	}

	/** Validates the fields. */
	public DecisionMade {
		Objects.requireNonNull(stage, "stage can't be null");
		Objects.requireNonNull(outcome, "outcome can't be null");
	}
}
