package io.github.ultron03.tokenallowance.springai.advisor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

class PromptTextTest {

	@Test
	void includesMessageText() {
		Prompt prompt = new Prompt(List.of(new SystemMessage("Be brief."), new UserMessage("What time is it?")));

		assertThat(PromptText.of(prompt)).isEqualTo("Be brief.What time is it?");
	}

	@Test
	void includesToolCallNameAndArguments() {
		AssistantMessage toolCall = AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("1", "function", "currentTime", "{\"zone\":\"UTC\"}")))
			.build();

		assertThat(PromptText.of(new Prompt(List.of(toolCall)))).isEqualTo("currentTime{\"zone\":\"UTC\"}");
	}

	@Test
	void includesToolResponses() {
		ToolResponseMessage toolResult = ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("1", "currentTime", "12:00 PM")))
			.build();

		assertThat(PromptText.of(new Prompt(List.of(toolResult)))).isEqualTo("12:00 PM");
	}
}
