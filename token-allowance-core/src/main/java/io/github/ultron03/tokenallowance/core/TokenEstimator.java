package io.github.ultron03.tokenallowance.core;

/** Guesses the input tokens of a prompt before the call. Replace this bean for exact counts. */
public interface TokenEstimator {

	/** {@return the estimated input tokens of {@code text}; should overestimate rather than underestimate} */
	long estimateInputTokens(String text);
}
