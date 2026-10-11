package io.github.ultron03.tokenallowance.core;

/**
 * Tokens used by one or more model calls.
 *
 * @param input prompt tokens, including cached ones
 * @param output completion tokens
 * @param cachedInput the part of {@code input} served from the provider's cache
 */
public record TokenUsage(long input, long output, long cachedInput) {

	/** No tokens used. */
	public static final TokenUsage ZERO = new TokenUsage(0, 0, 0);

	/**
	 * Validates the counts.
	 * @throws IllegalArgumentException if a count is negative or {@code cachedInput} exceeds {@code input}
	 */
	public TokenUsage {
		requireNonNegative("input", input);
		requireNonNegative("output", output);
		requireNonNegative("cachedInput", cachedInput);

		if (input < cachedInput) {
			throw new IllegalArgumentException(
					"Cached input (" + cachedInput + ") cannot be greater than input (" + input + ")");
		}
	}

	private static void requireNonNegative(String name, long value) {
		if (value < 0) {
			throw new IllegalArgumentException(name + " cannot be negative: " + value);
		}
	}

	/** {@return input plus output tokens; cached tokens are already part of input} */
	public long total() {
		return Math.addExact(this.input, this.output);
	}

	/**
	 * Adds two usages field by field.
	 * @throws ArithmeticException on overflow
	 */
	public TokenUsage plus(TokenUsage other) {
		return new TokenUsage(
				Math.addExact(this.input, other.input),
				Math.addExact(this.output, other.output),
				Math.addExact(this.cachedInput, other.cachedInput));
	}

	/** {@return whether no tokens were reported; providers that omit usage report all zeros} */
	public boolean isZero() {
		return this.input == 0 && this.output == 0 && this.cachedInput == 0;
	}
}
