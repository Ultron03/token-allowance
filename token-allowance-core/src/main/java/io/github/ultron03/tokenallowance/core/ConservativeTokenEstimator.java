package io.github.ultron03.tokenallowance.core;

import java.util.Objects;

/** Estimates one token per three characters, rounded up, to overestimate on purpose. */
public class ConservativeTokenEstimator implements TokenEstimator {

	private static final long CHARS_PER_TOKEN = 3;

	@Override
	public long estimateInputTokens(String text) {
		Objects.requireNonNull(text, "text must not be null");
		long chars = text.length();
		return (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
	}
}
