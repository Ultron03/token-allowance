package io.github.ultron03.tokenallowance.springai.advisor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import com.anthropic.core.ObjectMappers;
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
	void countsAnthropicCacheOnceWhenPromptAlreadyIncludesIt() {
		com.anthropic.models.messages.Usage nativeUsage = anthropicUsage(100, 20, 300, 50);
		Usage summed = new DefaultUsage(450, 20, 470, nativeUsage, 300L, 50L);

		assertThat(UsageMapping.toTokenUsage(responseWith(summed))).isEqualTo(new TokenUsage(450, 20, 300, 50));
	}

	@Test
	void addsCacheTokensForAnthropicStream() {
		Usage usage = new DefaultUsage(100, 20, 120, anthropicDelta(100L, 60L, 10L), 60L, 10L);

		assertThat(UsageMapping.toTokenUsage(responseWith(usage))).isEqualTo(new TokenUsage(170, 20, 60, 10));
	}

	@Test
	void countsAnthropicStreamCacheOnceWhenPromptAlreadyIncludesIt() {
		Usage summed = new DefaultUsage(170, 20, 190, anthropicDelta(100L, 60L, 10L), 60L, 10L);

		assertThat(UsageMapping.toTokenUsage(responseWith(summed))).isEqualTo(new TokenUsage(170, 20, 60, 10));
	}

	@Test
	void keepsPromptTokensWhenAnthropicDeltaHasNoInputCount() {
		Usage summed = new DefaultUsage(170, 20, 190, anthropicDelta(null, null, null), 60L, 10L);

		assertThat(UsageMapping.toTokenUsage(responseWith(summed))).isEqualTo(new TokenUsage(170, 20, 60, 10));
	}

	@Test
	void addsCacheTokensForBedrock() {
		Usage usage = new DefaultUsage(400, 20, 420, bedrockUsage(400, 60, 10), 60L, 10L);

		assertThat(UsageMapping.toTokenUsage(responseWith(usage))).isEqualTo(new TokenUsage(470, 20, 60, 10));
	}

	@Test
	void countsBedrockCacheOnceWhenPromptAlreadyIncludesIt() {
		Usage summed = new DefaultUsage(470, 20, 490, bedrockUsage(400, 60, 10), 60L, 10L);

		assertThat(UsageMapping.toTokenUsage(responseWith(summed))).isEqualTo(new TokenUsage(470, 20, 60, 10));
	}

	@Test
	void addsCacheTokensWhenTheyExceedPrompt() {
		Usage usage = new DefaultUsage(10, 5, 15, null, 30L, 20L);

		assertThat(UsageMapping.toTokenUsage(responseWith(usage))).isEqualTo(new TokenUsage(60, 5, 30, 20));
	}

	@Test
	void chargesTokensOnlyInTheTotalAsOutput() {
		Usage geminiWithThinking = new DefaultUsage(100, 20, 150, null, null, null);

		assertThat(UsageMapping.toTokenUsage(responseWith(geminiWithThinking))).isEqualTo(new TokenUsage(100, 50, 0, 0));
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
		Map<String, Long> json = Map.of("input_tokens", input, "output_tokens", output, "cache_read_input_tokens",
				cacheRead, "cache_creation_input_tokens", cacheWrite);
		return ObjectMappers.jsonMapper().convertValue(json, com.anthropic.models.messages.Usage.class);
	}

	private static MessageDeltaUsage anthropicDelta(@Nullable Long input, @Nullable Long cacheRead,
			@Nullable Long cacheWrite) {
		Map<String, Long> json = new HashMap<>();
		json.put("output_tokens", 20L);
		putIfPresent(json, "input_tokens", input);
		putIfPresent(json, "cache_read_input_tokens", cacheRead);
		putIfPresent(json, "cache_creation_input_tokens", cacheWrite);
		return ObjectMappers.jsonMapper().convertValue(json, MessageDeltaUsage.class);
	}

	private static software.amazon.awssdk.services.bedrockruntime.model.TokenUsage bedrockUsage(int input,
			int cacheRead, int cacheWrite) {
		return software.amazon.awssdk.services.bedrockruntime.model.TokenUsage.builder()
			.inputTokens(input)
			.outputTokens(20)
			.totalTokens(input + 20)
			.cacheReadInputTokens(cacheRead)
			.cacheWriteInputTokens(cacheWrite)
			.build();
	}

	private static void putIfPresent(Map<String, Long> json, String field, @Nullable Long count) {
		if (count != null) {
			json.put(field, count);
		}
	}
}
