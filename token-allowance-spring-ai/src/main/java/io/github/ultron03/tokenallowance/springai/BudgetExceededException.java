package io.github.ultron03.tokenallowance.springai;

import java.util.Objects;

import io.github.ultron03.tokenallowance.core.Decision;

/** Thrown when a budget is used up, so the model is not called; carries the reason and when to retry. */
public class BudgetExceededException extends RuntimeException {

	private final Decision.Denied decision;

	/** Creates the exception; its message is the decision's reason, which holds no subject id. */
	public BudgetExceededException(Decision.Denied decision) {
		super(Objects.requireNonNull(decision, "decision can't be null").reason());
		this.decision = decision;
	}

	/** Returns the denial, including how long until the budget's window resets. */
	public Decision.Denied decision() {
		return this.decision;
	}
}
