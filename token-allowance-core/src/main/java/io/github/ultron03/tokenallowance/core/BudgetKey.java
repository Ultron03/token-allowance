package io.github.ultron03.tokenallowance.core;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Identifies one budget counter: who it belongs to and which window it counts.
 * Used as a ledger key, so two keys for the same subject and window must be equal.
 *
 * @param scope what kind of subject the budget belongs to
 * @param subjectId the user, tenant or session id; {@link #GLOBAL_SUBJECT} for {@link Scope#GLOBAL}
 * @param window the period the budget resets on
 * @param windowStart the first instant of the window being counted
 */
public record BudgetKey(Scope scope, String subjectId, Window window, Instant windowStart) {

	/** Subject id used for {@link Scope#GLOBAL}; there is only one global budget. */
	public static final String GLOBAL_SUBJECT = "global";

	/**
	 * Validates the fields; a {@link Scope#GLOBAL} key always uses {@link #GLOBAL_SUBJECT}.
	 * @throws IllegalArgumentException if {@code subjectId} is null or blank
	 */
	public BudgetKey {
		Objects.requireNonNull(scope, "scope can't be null");
		Objects.requireNonNull(window, "window can't be null");
		Objects.requireNonNull(windowStart, "windowStart can't be null");

		if (subjectId == null || subjectId.isBlank()) {
			throw new IllegalArgumentException("subjectId can't be null or Blank");
		}

		if (scope == Scope.GLOBAL) subjectId = GLOBAL_SUBJECT;
	}

	/** {@return the key for the window that contains {@code now} in the given zone} */
	public static BudgetKey of(Scope scope, String subjectId, Window window, Instant now, ZoneId zone) {
		Instant windowStart = window.startOf(now, zone);
		return new BudgetKey(scope, subjectId, window, windowStart);
	}

}
