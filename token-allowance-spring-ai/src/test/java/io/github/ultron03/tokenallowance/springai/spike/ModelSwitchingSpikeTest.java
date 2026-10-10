package io.github.ultron03.tokenallowance.springai.spike;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.annotation.Tool;

import io.github.ultron03.tokenallowance.springai.fixture.FakeChatModel;

class ModelSwitchingSpikeTest {

	static class TimeTool {
		@Tool(name = FakeChatModel.TIME_TOOL_NAME, description = "Returns the current time")
		public String getCurrentTime() {
			return "12:00 PM";
		}
	}

	@Test
	void switchesModelOnEveryModelCallOfTheToolLoop() {
		FakeChatModel fakeChatModel = FakeChatModel.toolCallThenAnswer();
		ChatClient client = ChatClient.builder(fakeChatModel).build();
		assertThat(client.prompt("What time is it?")
			.advisors(new ModelSwitchingAdvisor("cheaper-model"))
			.tools(new TimeTool())
			.call().content()).isNotBlank();
		assertThat(fakeChatModel.receivedPrompts())
			.extracting(prompt -> prompt.getOptions().getModel())
			.containsExactly("cheaper-model", "cheaper-model");
	}

	@EnabledIfEnvironmentVariable(named = "OLLAMA_SPIKE", matches = "true")
	@Test
	void ollamaReceivesTheSwitchedModelName() {
		OllamaApi ollamaApi = OllamaApi.builder().build();
		OllamaChatModel ollamaChatModel = OllamaChatModel.builder().ollamaApi(ollamaApi).options(OllamaChatOptions.builder().model("llama3.2").build()).build();
		ChatClient client = ChatClient.builder(ollamaChatModel).build();

		assertThat(client.prompt("What time is it?").call().content()).isNotBlank();

		assertThatThrownBy(() -> client.prompt("What time is it?")
			.advisors(new ModelSwitchingAdvisor("llama3.1"))
			.call().content())
		.hasMessageContaining("not found")
		.hasMessageContaining("llama3.1");

	}
}