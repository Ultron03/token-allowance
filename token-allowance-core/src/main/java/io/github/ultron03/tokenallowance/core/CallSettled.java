package io.github.ultron03.tokenallowance.core;

import java.util.Objects;
import java.util.Set;

/**
 * One model call that was charged or released. The keys hold subject ids: fine for an audit log,
 * never for metric tags.
 *
 * @param model the model the call went to
 * @param keys the budgets the call was reserved on
 * @param estimate what was reserved before the call
 * @param reported the usage the provider reported; zero if it reported none or the call was released
 * @param charged what was charged; zero when released
 * @param outcome how the call was settled
 */
public record CallSettled(String model, Set<BudgetKey> keys, Amount estimate, TokenUsage reported, Amount charged,
		Outcome outcome) {

	/** How a call was settled. */
	public enum Outcome {

		/** Charged the usage the provider reported. */
		CHARGED,

		/** The provider reported no usage, so the estimate was charged. */
		ESTIMATE_CHARGED,

		/** The call failed, so nothing was charged. */
		RELEASED
	}

	/** Validates the fields. */
	public CallSettled {
		Objects.requireNonNull(model, "model can't be null");
		Objects.requireNonNull(estimate, "estimate can't be null");
		Objects.requireNonNull(reported, "reported can't be null");
		Objects.requireNonNull(charged, "charged can't be null");
		Objects.requireNonNull(outcome, "outcome can't be null");
		keys = Set.copyOf(keys);
	}
}
