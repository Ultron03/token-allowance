package io.github.ultron03.tokenallowance.core;

import org.jspecify.annotations.Nullable;

/** Guesses the tokens of a call before it is made, so they can be reserved. Replace this bean for exact counts. */
public interface TokenEstimator {

	/** Output tokens reserved for a request that sets no max tokens. */
	long DEFAULT_OUTPUT_TOKENS = 1024;

	/** {@return the estimated input tokens of {@code text}; should overestimate rather than underestimate} */
	long estimateInputTokens(String text);

	/**
	 * {@return the output tokens to reserve before a call} By default the request's max tokens, or
	 * {@link #DEFAULT_OUTPUT_TOKENS} without one: the most an answer can use, so a budget is only
	 * exceeded by input estimate errors.
	 * @param maxTokens the request's max output tokens, or null if it sets none
	 */
	default long estimateOutputTokens(@Nullable Integer maxTokens) {
		return (maxTokens != null) ? maxTokens : DEFAULT_OUTPUT_TOKENS;
	}
}
