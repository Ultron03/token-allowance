package io.github.ultron03.tokenallowance.core;

/**
 * Thrown by a {@link UsageLedger} whose store can't be reached, so {@link BudgetService} can apply
 * its {@link LedgerFailureMode}.
 */
public class LedgerUnavailableException extends RuntimeException {

	/** Creates the exception; {@code cause} is the store client's own error. */
	public LedgerUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
