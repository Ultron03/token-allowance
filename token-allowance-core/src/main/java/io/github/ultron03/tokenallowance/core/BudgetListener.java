package io.github.ultron03.tokenallowance.core;

/**
 * Hears what {@link BudgetService} decided and charged, for metrics or an audit log. Called on the
 * caller's thread after the ledger work is done; an exception thrown here is logged at WARN and
 * doesn't fail the chat request.
 */
public interface BudgetListener {

	/** Called for each admission, reached request cap and reservation. */
	default void onDecision(DecisionMade event) {
	}

	/** Called once per model call, when it is charged or released. */
	default void onSettled(CallSettled event) {
	}

	/** Called once per subject, window and threshold, by the call that took committed spend to it. */
	default void onThresholdReached(BudgetThresholdReached event) {
	}
}
