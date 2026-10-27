package io.github.ultron03.tokenallowance.springai.advisor;

import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import io.github.ultron03.tokenallowance.core.TokenUsage;

/**
 * Turns Spring AI's {@link Usage} into a {@link TokenUsage} whose input counts all input tokens.
 *
 * <p>OpenAI-style providers include cached tokens in the prompt tokens. Anthropic does not: its
 * prompt tokens are only those after the last cache breakpoint, so the cache counts are added. Its
 * streams report usage with a different native type, so both types are recognised.
 */
final class UsageMapping {

	/** Anthropic's native usage types, matched by name so the SDK is not needed at runtime. */
	static final Set<String> ANTHROPIC_USAGE_CLASSES = Set.of("com.anthropic.models.messages.Usage",
			"com.anthropic.models.messages.MessageDeltaUsage");

	private UsageMapping() {
	}

	/** Returns the usage of a response, or {@link TokenUsage#ZERO} if it has none. */
	static TokenUsage toTokenUsage(@Nullable ChatResponse response) {
		if (response == null) {
			return TokenUsage.ZERO;
		}
		Usage usage = response.getMetadata().getUsage();
		long prompt = orZero(usage.getPromptTokens());
		long output = orZero(usage.getCompletionTokens());
		long cacheRead = orZero(usage.getCacheReadInputTokens());
		long cacheWrite = orZero(usage.getCacheWriteInputTokens());
		long cached = Math.addExact(cacheRead, cacheWrite);

		boolean cacheExcludedFromPrompt = isAnthropic(usage.getNativeUsage()) || cached > prompt;
		long input = cacheExcludedFromPrompt ? Math.addExact(prompt, cached) : prompt;
		return new TokenUsage(input, output, cacheRead, cacheWrite);
	}

	private static boolean isAnthropic(@Nullable Object nativeUsage) {
		return nativeUsage != null && ANTHROPIC_USAGE_CLASSES.contains(nativeUsage.getClass().getName());
	}

	private static long orZero(@Nullable Number count) {
		return (count != null) ? count.longValue() : 0;
	}
}
