package io.github.ultron03.tokenallowance.springai.advisor;

import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import io.github.ultron03.tokenallowance.core.TokenUsage;

/**
 * Turns Spring AI's {@link Usage} into a {@link TokenUsage} whose input counts all input tokens.
 *
 * <p>OpenAI-style providers include cached tokens in the prompt tokens. Anthropic and Bedrock
 * report input without them, and Spring AI passes that on as the prompt tokens before 2.1.0-RC2 and
 * adds the cache counts to it from then on. So for those two the input is rebuilt from the
 * provider's own count when the response carries it. An Anthropic stream delta without it carries no
 * cache counts on the older versions, so there the prompt tokens are already the whole input.
 *
 * <p>Tokens counted in the total but in neither part are charged as output. That is how Gemini
 * reports thinking, which it bills at the output price.
 */
final class UsageMapping {

	/** Native usage types, matched by name so their SDKs are not needed at runtime. */
	private static final Set<String> ANTHROPIC_USAGE_CLASSES = Set.of("com.anthropic.models.messages.Usage",
			"com.anthropic.models.messages.MessageDeltaUsage");

	private static final String BEDROCK_USAGE_CLASS = "software.amazon.awssdk.services.bedrockruntime.model.TokenUsage";

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

		long input = inputTokens(usage.getNativeUsage(), prompt, cached);
		long uncounted = orZero(usage.getTotalTokens()) - Math.addExact(input, output);
		if (uncounted > 0) {
			output = Math.addExact(output, uncounted);
		}
		return new TokenUsage(input, output, cacheRead, cacheWrite);
	}

	private static long inputTokens(@Nullable Object nativeUsage, long prompt, long cached) {
		Long uncached = (nativeUsage != null) ? uncachedInputTokens(nativeUsage) : null;
		if (uncached != null) {
			return Math.addExact(uncached, cached);
		}
		return (cached > prompt) ? Math.addExact(prompt, cached) : prompt;
	}

	private static @Nullable Long uncachedInputTokens(Object nativeUsage) {
		String type = nativeUsage.getClass().getName();
		if (ANTHROPIC_USAGE_CLASSES.contains(type)) {
			return AnthropicUsage.uncachedInputTokens(nativeUsage);
		}
		if (BEDROCK_USAGE_CLASS.equals(type)) {
			return BedrockUsage.uncachedInputTokens(nativeUsage);
		}
		return null;
	}

	private static long orZero(@Nullable Number count) {
		return (count != null) ? count.longValue() : 0;
	}
}
