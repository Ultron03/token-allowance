package io.github.ultron03.tokenallowance.core;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Caps on one request, so a runaway tool loop is cut off. A null cap means no limit.
 *
 * @param maxModelCalls the most model calls one request may make, or null for no cap
 * @param maxCost the most one request may cost, or null for no cap
 */
public record RequestLimit(@Nullable Integer maxModelCalls, @Nullable Money maxCost) {

	/** No per-request caps. */
	public static final RequestLimit NONE = new RequestLimit(null, null);

	/**
	 * Validates the fields.
	 * @throws IllegalArgumentException if a set cap is not greater than zero
	 */
	public RequestLimit {
		if (maxModelCalls != null && maxModelCalls <= 0) {
			throw new IllegalArgumentException("maxModelCalls must be greater than zero");
		}
		if (maxCost != null && maxCost.equals(Money.ZERO)) {
			throw new IllegalArgumentException("maxCost must be greater than zero");
		}
	}

	/** {@return this limit, with each cap it doesn't set taken from {@code fallback}} */
	public RequestLimit orElse(RequestLimit fallback) {
		Objects.requireNonNull(fallback, "fallback can't be null");
		return new RequestLimit((this.maxModelCalls != null) ? this.maxModelCalls : fallback.maxModelCalls(),
				(this.maxCost != null) ? this.maxCost : fallback.maxCost());
	}
}
