package io.github.ultron03.tokenallowance.springai.advisor;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.MediaContent;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Collects the text of a prompt that the model reads, for the token estimate.
 *
 * <p>{@link Prompt#getContents()} is not enough: tool results and tool-call arguments are not part
 * of a message's text, and in a tool loop they are often the largest input. Tool definitions are
 * sent with every call, so they count too. Images and other media have no text; they are counted
 * by {@link #mediaCount}.
 */
final class PromptText {

	private PromptText() {
	}

	/** Returns message texts, tool-call names and arguments, tool results and tool definitions, joined. */
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
		if (prompt.getOptions() instanceof ToolCallingChatOptions options && options.getToolCallbacks() != null) {
			for (ToolCallback tool : options.getToolCallbacks()) {
				ToolDefinition definition = tool.getToolDefinition();
				text.append(definition.name()).append(definition.description()).append(definition.inputSchema());
			}
		}
		return text.toString();
	}

	/** {@return how many images, audio clips and other media the prompt's messages carry} */
	static int mediaCount(Prompt prompt) {
		int count = 0;
		for (Message message : prompt.getInstructions()) {
			if (message instanceof MediaContent media) {
				count += media.getMedia().size();
			}
		}
		return count;
	}

	private static void appendIfPresent(StringBuilder text, @Nullable String part) {
		if (part != null) {
			text.append(part);
		}
	}
}
