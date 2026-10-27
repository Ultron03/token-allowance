package io.github.ultron03.tokenallowance.springai.fixture;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

/**
 * A {@link ChatModel} that plays back a fixed script of replies, so tests can check budget behavior
 * without a network, an API key, or model randomness.
 *
 * <p>Each model call consumes the next {@link ScriptedReply}. A call after the script runs out fails,
 * because an unexpected extra call means the code under test looped more than the test expected.
 *
 * <p>{@link #getOptions()} returns {@link ToolCallingChatOptions}. Spring AI's
 * {@code ToolCallingAdvisor} only runs its tool loop when the prompt options are of that type, and
 * {@code ChatClient} derives prompt options from the model's options.
 */
public final class FakeChatModel implements ChatModel {

	/** The model name reported in every response and in {@link #getOptions()}. */
	public static final String MODEL_NAME = "fake-model";

	/** The tool requested by {@link #toolCallThenAnswer()}. */
	public static final String TIME_TOOL_NAME = "currentTime";

	private static final String NO_ARGUMENTS_JSON = "{}";

	private static final int TOOL_CALL_PROMPT_TOKENS = 120;

	private static final int TOOL_CALL_COMPLETION_TOKENS = 15;

	private static final int FINAL_ANSWER_PROMPT_TOKENS = 160;

	private static final int FINAL_ANSWER_COMPLETION_TOKENS = 40;

	private final Deque<ScriptedReply> script;

	private final int scriptLength;

	private final List<Prompt> receivedPrompts = new CopyOnWriteArrayList<>();

	/**
	 * Creates a model that returns {@code replies} in order, one per model call.
	 * @param replies the script; must not be empty
	 */
	public FakeChatModel(List<ScriptedReply> replies) {
		if (replies.isEmpty()) {
			throw new IllegalArgumentException("A script needs at least one reply");
		}
		this.script = new ArrayDeque<>(replies);
		this.scriptLength = replies.size();
	}

	/**
	 * Convenience for {@code new FakeChatModel(List.of(replies))}.
	 * @param replies the script, in call order
	 * @return the model
	 */
	public static FakeChatModel replying(ScriptedReply... replies) {
		return new FakeChatModel(List.of(replies));
	}

	/**
	 * Two model calls: the first asks for {@link #TIME_TOOL_NAME}, the second answers. Both report
	 * usage, so a correct meter charges two separate calls.
	 * @return the model
	 */
	public static FakeChatModel toolCallThenAnswer() {
		return replying(
				ScriptedReply.toolCall(TIME_TOOL_NAME, NO_ARGUMENTS_JSON, TOOL_CALL_PROMPT_TOKENS,
						TOOL_CALL_COMPLETION_TOKENS),
				ScriptedReply.answer("It is noon.", FINAL_ANSWER_PROMPT_TOKENS, FINAL_ANSWER_COMPLETION_TOKENS));
	}

	/**
	 * One model call whose provider reports no usage, to test the "charge the estimate" fallback.
	 * @return the model
	 */
	public static FakeChatModel usageMissing() {
		return replying(ScriptedReply.answerWithoutUsage("An answer nobody counted."));
	}

	/**
	 * One model call whose provider reports usage with zero tokens, the other "no usage" shape.
	 * @return the model
	 */
	public static FakeChatModel usageZero() {
		return replying(ScriptedReply.answerWithZeroUsage("An answer counted as nothing."));
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		this.receivedPrompts.add(prompt);
		return nextReply().toChatResponse(MODEL_NAME);
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		return Flux.defer(() -> {
			this.receivedPrompts.add(prompt);
			return Flux.fromIterable(nextReply().toStreamChunks(MODEL_NAME));
		});
	}

	@Override
	public ChatOptions getOptions() {
		return ToolCallingChatOptions.builder().model(MODEL_NAME).build();
	}

	/** {@return the prompts received so far, in call order} */
	public List<Prompt> receivedPrompts() {
		return List.copyOf(this.receivedPrompts);
	}

	/** {@return how many model calls were made} */
	public int callCount() {
		return this.receivedPrompts.size();
	}

	private synchronized ScriptedReply nextReply() {
		ScriptedReply reply = this.script.poll();
		if (reply == null) {
			throw new IllegalStateException(
					"Script exhausted: it has " + this.scriptLength + " replies, but call " + callCount() + " was made");
		}
		return reply;
	}

}
