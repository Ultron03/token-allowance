package io.github.ultron03.tokenallowance.core;

import java.time.Instant;
import java.util.Objects;

/**
 * Where one subject stands against one limit in the current window.
 *
 * @param limit the configured limit
 * @param windowStart when the current window began
 * @param resetsAt when the current window ends and spend starts again from zero
 * @param spend what finished calls used and what calls in flight hold
 */
public record BudgetStatus(Limit limit, Instant windowStart, Instant resetsAt, Spend spend) {

	/** Validates the fields. */
	public BudgetStatus {
		Objects.requireNonNull(limit, "limit can't be null");
		Objects.requireNonNull(windowStart, "windowStart can't be null");
		Objects.requireNonNull(resetsAt, "resetsAt can't be null");
		Objects.requireNonNull(spend, "spend can't be null");
	}
}
