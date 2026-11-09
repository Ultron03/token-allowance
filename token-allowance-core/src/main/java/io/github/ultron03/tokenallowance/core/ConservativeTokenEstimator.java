package io.github.ultron03.tokenallowance.core;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Estimates one input token per three characters, rounded up, to overestimate on purpose. Output is
 * reserved at the request's max tokens, or at an expected answer length when one is configured.
 */
public class ConservativeTokenEstimator implements TokenEstimator {

	private static final long CHARS_PER_TOKEN = 3;

	private final @Nullable Long expectedOutputTokens;

	/** Creates an estimator that reserves the most an answer can use. */
	public ConservativeTokenEstimator() {
		this.expectedOutputTokens = null;
	}

	/**
	 * Creates an estimator that reserves a typical answer instead of the longest one, so a nearly used-up
	 * budget still admits short calls. A longer answer is still charged in full afterwards.
	 * @param expectedOutputTokens output tokens to reserve, never more than the request's max tokens
	 * @throws IllegalArgumentException if {@code expectedOutputTokens} is not greater than zero
	 */
	public ConservativeTokenEstimator(long expectedOutputTokens) {
		if (expectedOutputTokens <= 0) {
			throw new IllegalArgumentException("expectedOutputTokens must be greater than zero");
		}
		this.expectedOutputTokens = expectedOutputTokens;
	}

	@Override
	public long estimateInputTokens(String text) {
		Objects.requireNonNull(text, "text must not be null");
		long chars = text.length();
		return (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
	}

	@Override
	public long estimateOutputTokens(@Nullable Integer maxTokens) {
		if (this.expectedOutputTokens == null) {
			return TokenEstimator.super.estimateOutputTokens(maxTokens);
		}
		return (maxTokens != null) ? Math.min(maxTokens, this.expectedOutputTokens) : this.expectedOutputTokens;
	}
}
