package io.github.ultron03.tokenallowance.core;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * A configured cap on spending, applied to every subject of a scope in each window.
 * A null cap means that kind of usage is not limited.
 *
 * @param scope what kind of subject the limit applies to
 * @param window the period the limit resets on
 * @param maxTokens the most tokens allowed per window, or null for no token cap
 * @param maxCost the most money allowed per window, or null for no cost cap
 */
public record Limit(Scope scope, Window window, @Nullable Long maxTokens, @Nullable Money maxCost) {

	/**
	 * Validates the fields.
	 * @throws IllegalArgumentException if neither cap is set, or a set cap is not greater than zero
	 */
	public Limit {
		Objects.requireNonNull(scope, "scope can't be null");
		Objects.requireNonNull(window, "window can't be null");

		if (maxTokens == null && maxCost == null) {
			throw new IllegalArgumentException("maxTokens and maxCost can't both be null");
		}

		if (maxTokens != null && maxTokens <= 0) {
			throw new IllegalArgumentException("maxTokens must be greater than zero");
		}

		if (maxCost != null && maxCost.equals(Money.ZERO)) {
			throw new IllegalArgumentException("maxCost must be greater than zero");
		}
	}
}
