package io.github.ultron03.tokenallowance.core;

import java.time.Instant;
import java.util.Objects;

/**
 * A model call took a subject's committed spend to a configured percent of a limit. Holds the
 * subject id: fine for alerts, never for metric tags.
 *
 * @param key the budget, including the subject id
 * @param limit the caps of that budget
 * @param percent the threshold that was reached, 1 to 100
 * @param committed the committed spend right after the call
 * @param resetsAt when the window ends and spend starts again from zero
 */
public record BudgetThresholdReached(BudgetKey key, Limit limit, int percent, Amount committed, Instant resetsAt) {

	/** Validates the fields. */
	public BudgetThresholdReached {
		Objects.requireNonNull(key, "key can't be null");
		Objects.requireNonNull(limit, "limit can't be null");
		Objects.requireNonNull(committed, "committed can't be null");
		Objects.requireNonNull(resetsAt, "resetsAt can't be null");
	}
}
