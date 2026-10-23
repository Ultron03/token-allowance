package io.github.ultron03.tokenallowance.springai.advisor;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Collects the text of a prompt that the model reads, for the token estimate.
 *
 * <p>{@link Prompt#getContents()} is not enough: tool results and tool-call arguments are not part
 * of a message's text, and in a tool loop they are often the largest input.
 */
final class PromptText {

	private PromptText() {
	}

	/** Returns message texts, tool-call names and arguments, and tool results, joined. */
	static String of(Prompt prompt) {
		StringBuilder text = new StringBuilder();
		for (Message message : prompt.getInstructions()) {
			appendIfPresent(text, message.getText());
			if (message instanceof AssistantMessage assistant) {
				for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
					text.append(call.name()).append(call.arguments());
				}
			}
			if (message instanceof ToolResponseMessage tool) {
				for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
					text.append(response.responseData());
				}
			}
		}
		return text.toString();
	}

	private static void appendIfPresent(StringBuilder text, @Nullable String part) {
		if (part != null) {
			text.append(part);
		}
	}
}
