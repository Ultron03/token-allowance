/**
 * The framework-free domain of Token Allowance: money, token usage, budget scopes, windows and
 * limits.
 *
 * <p>This package must not depend on Spring, Reactor or any storage client, so the budget rules
 * can be unit-tested without a framework and reused by any adapter.
 *
 * <p>Everything in this package is non-null unless it is annotated {@code @Nullable}.
 */
@NullMarked
package io.github.ultron03.tokenallowance.core;

import org.jspecify.annotations.NullMarked;
