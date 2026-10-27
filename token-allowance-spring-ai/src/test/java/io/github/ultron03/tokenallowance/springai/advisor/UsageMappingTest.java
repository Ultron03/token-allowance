package io.github.ultron03.tokenallowance.springai.advisor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import com.anthropic.models.messages.MessageDeltaUsage;

import io.github.ultron03.tokenallowance.core.TokenUsage;

class UsageMappingTest {

	@Test
	void keepsPromptTokensWhenCacheIsIncluded() {
		Usage openAiStyle = new DefaultUsage(100, 20, 120, null, 40L, null);

		assertThat(UsageMapping.toTokenUsage(responseWith(openAiStyle))).isEqualTo(new TokenUsage(100, 20, 40, 0));
	}

	@Test
	void addsCacheTokensForAnthropic() {
		com.anthropic.models.messages.Usage nativeUsage = anthropicUsage(100, 20, 300, 50);
		Usage usage = new DefaultUsage(100, 20, 120, nativeUsage, 300L, 50L);

		assertThat(UsageMapping.toTokenUsage(responseWith(usage))).isEqualTo(new TokenUsage(450, 20, 300, 50));
	}

	@Test
	void addsCacheTokensForAnthropicStream() {
		MessageDeltaUsage nativeUsage = MessageDeltaUsage.builder()
			.inputTokens(100)
			.outputTokens(20)
			.cacheReadInputTokens(60)
			.cacheCreationInputTokens(10)
			.outputTokensDetails(Optional.empty())
			.serverToolUse(Optional.empty())
			.build();
		Usage usage = new DefaultUsage(100, 20, 120, nativeUsage, 60L, 10L);

		assertThat(UsageMapping.toTokenUsage(responseWith(usage))).isEqualTo(new TokenUsage(170, 20, 60, 10));
	}

	@Test
	void addsCacheTokensWhenTheyExceedPrompt() {
		Usage usage = new DefaultUsage(10, 5, 15, null, 30L, 20L);

		assertThat(UsageMapping.toTokenUsage(responseWith(usage))).isEqualTo(new TokenUsage(60, 5, 30, 20));
	}

	@Test
	void treatsNullCountsAsZero() {
		Usage usage = new DefaultUsage(null, null, null, null, null, null);

		assertThat(UsageMapping.toTokenUsage(responseWith(usage))).isEqualTo(TokenUsage.ZERO);
	}

	@Test
	void returnsZeroWhenResponseHasNoUsage() {
		ChatResponse response = new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("hi").build())));

		assertThat(UsageMapping.toTokenUsage(response)).isEqualTo(TokenUsage.ZERO);
	}

	@Test
	void returnsZeroWithoutResponse() {
		assertThat(UsageMapping.toTokenUsage(null)).isEqualTo(TokenUsage.ZERO);
	}

	private static ChatResponse responseWith(Usage usage) {
		Generation generation = new Generation(AssistantMessage.builder().content("hi").build());
		return new ChatResponse(List.of(generation), ChatResponseMetadata.builder().usage(usage).build());
	}

	private static com.anthropic.models.messages.Usage anthropicUsage(long input, long output, long cacheRead,
			long cacheWrite) {
		return com.anthropic.models.messages.Usage.builder()
			.inputTokens(input)
			.outputTokens(output)
			.cacheReadInputTokens(cacheRead)
			.cacheCreationInputTokens(cacheWrite)
			.cacheCreation(Optional.empty())
			.inferenceGeo(Optional.empty())
			.outputTokensDetails(Optional.empty())
			.serverToolUse(Optional.empty())
			.serviceTier(Optional.empty())
			.build();
	}
}
