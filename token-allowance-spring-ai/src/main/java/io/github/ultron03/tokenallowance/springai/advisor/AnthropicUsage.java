package io.github.ultron03.tokenallowance.springai.advisor;

import org.jspecify.annotations.Nullable;

import com.anthropic.models.messages.MessageDeltaUsage;
import com.anthropic.models.messages.Usage;

/**
 * Reads Anthropic's own usage objects. Kept apart from {@link UsageMapping} so the Anthropic SDK is
 * only loaded once a response is known to come from it.
 */
final class AnthropicUsage {

	private AnthropicUsage() {
	}

	/**
	 * Returns Anthropic's {@code input_tokens}, which excludes cache reads and writes, or
	 * {@code null} when a stream delta leaves it out.
	 */
	static @Nullable Long uncachedInputTokens(Object nativeUsage) {
		if (nativeUsage instanceof Usage usage) {
			return usage.inputTokens();
		}
		if (nativeUsage instanceof MessageDeltaUsage delta) {
			return delta.inputTokens().orElse(null);
		}
		return null;
	}
}
