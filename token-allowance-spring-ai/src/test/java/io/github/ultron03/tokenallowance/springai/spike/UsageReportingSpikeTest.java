package io.github.ultron03.tokenallowance.springai.spike;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import reactor.core.publisher.Flux;

@EnabledIfEnvironmentVariable(named = "OLLAMA_SPIKE", matches = "true")
public class UsageReportingSpikeTest {
	

	private static final Prompt ANY_PROMPT = new Prompt("Say hello in five words.");
	private static final Logger logger = LoggerFactory.getLogger(UsageReportingSpikeTest.class);
	private final OllamaApi api = OllamaApi.builder().build();
	private final OllamaChatModel ollamaChatModel = OllamaChatModel.builder().ollamaApi(api)
			.options(OllamaChatOptions.builder().model("llama3.2").build()).build();
	private final OpenAiChatModel openAiChatModel = OpenAiChatModel.builder()
			.options(OpenAiChatOptions.builder().baseUrl("http://localhost:11434/v1").apiKey("ollama").model("llama3.2").build()).build();

	private final OpenAiChatModel openAiChatModelWithNoUsage = OpenAiChatModel.builder()
			.options(OpenAiChatOptions.builder().streamUsage(false).baseUrl("http://localhost:11434/v1").apiKey("ollama").model("llama3.2").build()).build();

	
	private void logUsage(String label, Usage usage, @Nullable Integer chunkIndex, @Nullable Integer totalChunks) {
		logger.info("{} usage: promptTokens={}, completionTokens={}, totalTokens={}, CacheReadTokens={}, CacheWriteTokens={}, SimpleName={} chunkIndex={}, totalChunks={}", label, usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens(), usage.getCacheReadInputTokens(), usage.getCacheWriteInputTokens(), usage.getClass().getSimpleName(), chunkIndex, totalChunks);
	}

	@Test
	public void ollamaNativeCall() {
		ChatResponse response = ollamaChatModel.call(ANY_PROMPT);
		logUsage("Ollama call", response.getMetadata().getUsage(), null, null);
	}

	@Test
	public void ollamaNativeStream() {
		Flux<ChatResponse> response = ollamaChatModel.stream(ANY_PROMPT);
		List<ChatResponse> responseList = response.collectList().block();

		for (int i=0; i<responseList.size(); i++) {
			ChatResponse chunk = responseList.get(i);
			logUsage("Ollama native with stream", chunk.getMetadata().getUsage(), i, responseList.size());
		}
	}

	@Test
	public void openAiCompatibleCall() {
		ChatResponse response = openAiChatModel.call(ANY_PROMPT);
		logUsage("OpenAI-compatible (Ollama /v1) call", response.getMetadata().getUsage(), null, null);
	}

	@Test
	public void openAiCompatibleStream() {
		Flux<ChatResponse> response = openAiChatModel.stream(ANY_PROMPT);
		List<ChatResponse> responseList = response.collectList().block();

		for (int i = 0; i < responseList.size(); i++) {
			ChatResponse chunk = responseList.get(i);
			logUsage("OpenAI-compatible (Ollama /v1) with stream", chunk.getMetadata().getUsage(), i, responseList.size());
		}
	}

	@Test
	public void openAiCompatibleStreamWithUsageDisabled() {
		Flux<ChatResponse> response = openAiChatModelWithNoUsage.stream(ANY_PROMPT);
		
		List<ChatResponse> responseList = response.collectList().block();

		for (int i = 0; i < responseList.size(); i++) {
			ChatResponse chunk = responseList.get(i);
			logUsage("OpenAI-compatible (Ollama /v1) with no usage stream", chunk.getMetadata().getUsage(), i, responseList.size());
		}

	}
}     
