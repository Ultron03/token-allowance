package io.github.ultron03.tokenallowance.springai.advisor;

import org.jspecify.annotations.Nullable;

import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;

/**
 * Reads Bedrock Converse's own usage object. Kept apart from {@link UsageMapping} so the AWS SDK is
 * only loaded once a response is known to come from it.
 */
final class BedrockUsage {

	private BedrockUsage() {
	}

	/** Returns Bedrock's {@code inputTokens}, which excludes cache reads and writes. */
	static @Nullable Long uncachedInputTokens(Object nativeUsage) {
		Integer input = ((TokenUsage) nativeUsage).inputTokens();
		return (input != null) ? input.longValue() : null;
	}
}
