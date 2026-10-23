/**
 * Advisors that enforce budgets on {@code ChatClient} calls.
 *
 * <p>{@link io.github.ultron03.tokenallowance.springai.advisor.AdmissionAdvisor} runs once per
 * request, before the tool loop, and leaves a
 * {@link io.github.ultron03.tokenallowance.springai.advisor.RequestBudgetContext} for the advisors
 * that run on every model call.
 *
 * <p>Everything in this package is non-null unless it is annotated {@code @Nullable}.
 */
@NullMarked
package io.github.ultron03.tokenallowance.springai.advisor;

import org.jspecify.annotations.NullMarked;
