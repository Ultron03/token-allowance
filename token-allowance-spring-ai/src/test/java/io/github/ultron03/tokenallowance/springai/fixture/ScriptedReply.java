package io.github.ultron03.tokenallowance.springai.fixture;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * One canned model answer that {@link FakeChatModel} returns for a single model call.
 *
 * <p>A reply is either a final text answer or a request to call tools. Its {@code usage} is what a
 * provider would report; {@code null} simulates a provider that reports nothing, which Spring AI
 * surfaces as an {@code EmptyUsage} with zero tokens.
 *
 * @param text the assistant text; empty for a pure tool-call reply
 * @param toolCalls the tools the model asks to run; empty for a final answer
 * @param usage the provider-reported usage, or {@code null} when the provider omits it
 */
public record ScriptedReply(String text, List<AssistantMessage.ToolCall> toolCalls, @Nullable Usage usage) {

	private static final String FUNCTION_TOOL_TYPE = "function";

	private static final String FINISH_REASON_STOP = "stop";

	private static final String FINISH_REASON_TOOL_CALLS = "tool_calls";

	/** Copies {@code toolCalls} so a script cannot change after it is built. */
	public ScriptedReply {
		Objects.requireNonNull(text, "text");
		toolCalls = List.copyOf(toolCalls);
	}

	/**
	 * A final text answer with the given usage.
	 * @param text the answer text
	 * @param promptTokens input tokens the provider reports
	 * @param completionTokens output tokens the provider reports
	 * @return the reply
	 */
	public static ScriptedReply answer(String text, int promptTokens, int completionTokens) {
		return new ScriptedReply(text, List.of(), new DefaultUsage(promptTokens, completionTokens));
	}

	/**
	 * A final text answer from a provider that reports no usage at all.
	 * @param text the answer text
	 * @return the reply
	 */
	public static ScriptedReply answerWithoutUsage(String text) {
		return new ScriptedReply(text, List.of(), null);
	}

	/**
	 * A reply asking the caller to run one tool before the model answers.
	 * @param toolName the tool to run; must match a registered tool for tool-calling tests
	 * @param argumentsJson the tool arguments as JSON
	 * @param promptTokens input tokens the provider reports
	 * @param completionTokens output tokens the provider reports
	 * @return the reply
	 */
	public static ScriptedReply toolCall(String toolName, String argumentsJson, int promptTokens,
			int completionTokens) {
		var call = new AssistantMessage.ToolCall(UUID.randomUUID().toString(), FUNCTION_TOOL_TYPE, toolName,
				argumentsJson);
		return new ScriptedReply("", List.of(call), new DefaultUsage(promptTokens, completionTokens));
	}

	/** {@return whether this reply asks for tools instead of answering} */
	public boolean isToolCall() {
		return !this.toolCalls.isEmpty();
	}

	/**
	 * Builds the {@link ChatResponse} a real provider integration would return for this reply.
	 * @param model the model name to report in the response metadata
	 * @return the response
	 */
	public ChatResponse toChatResponse(String model) {
		var message = AssistantMessage.builder().content(this.text).toolCalls(this.toolCalls).build();
		var generationMetadata = ChatGenerationMetadata.builder()
			.finishReason(isToolCall() ? FINISH_REASON_TOOL_CALLS : FINISH_REASON_STOP)
			.build();
		var responseMetadata = ChatResponseMetadata.builder().model(model);
		if (this.usage != null) {
			responseMetadata.usage(this.usage);
		}
		return new ChatResponse(List.of(new Generation(message, generationMetadata)), responseMetadata.build());
	}

	/**
	 * Splits this reply into the chunks a streaming provider would emit, so stream handling can be
	 * tested. Only the last chunk carries usage, as with OpenAI's {@code include_usage}.
	 * @param model the model name to report in each chunk's metadata
	 * @return the chunks, in emission order
	 */
	public List<ChatResponse> toStreamChunks(String model) {
		if (this.isToolCall()) {
			return List.of(this.toChatResponse(model));
		}
		String[] words = this.text.split("(?<= )");
		List<ChatResponse> chunks = new ArrayList<>();
		for (int i = 0; i < words.length; i++) {
			String word = words[i];
			Usage usageForThisChunk = (i == words.length - 1) ? this.usage : null;
			ChatResponse chunk = new ScriptedReply(word, List.of(), usageForThisChunk).toChatResponse(model);
			chunks.add(chunk);
		}
		return chunks;
	}

}
