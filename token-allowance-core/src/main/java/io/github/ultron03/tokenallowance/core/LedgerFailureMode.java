package io.github.ultron03.tokenallowance.core;

/** What {@link BudgetService} does with new calls while the {@link UsageLedger} can't be reached. */
public enum LedgerFailureMode {

	/** Calls go ahead without budget checks, and their spend is not recorded. */
	FAIL_OPEN,

	/** Calls are rejected with {@link LedgerUnavailableException}. */
	FAIL_CLOSED;

	/** {@return fail-open for observe mode, which never blocks, and fail-closed for enforce mode} */
	public static LedgerFailureMode defaultFor(EnforcementMode mode) {
		return switch (mode) {
			case OBSERVE -> FAIL_OPEN;
			case ENFORCE -> FAIL_CLOSED;
		};
	}
}
