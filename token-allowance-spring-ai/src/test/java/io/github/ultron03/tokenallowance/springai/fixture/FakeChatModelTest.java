package io.github.ultron03.tokenallowance.springai.fixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

class FakeChatModelTest {

	private static final Prompt ANY_PROMPT = new Prompt("What time is it?");

	@Test
	void returnsToolCallFirstAndFinalAnswerSecond() {
		FakeChatModel model = FakeChatModel.toolCallThenAnswer();

		ChatResponse first = model.call(ANY_PROMPT);
		ChatResponse second = model.call(ANY_PROMPT);

		assertThat(first.hasToolCalls()).isTrue();
		assertThat(first.getResult().getOutput().getToolCalls())
			.singleElement()
			.satisfies(call -> assertThat(call.name()).isEqualTo(FakeChatModel.TIME_TOOL_NAME));
		assertThat(second.hasToolCalls()).isFalse();
		assertThat(second.getResult().getOutput().getText()).isEqualTo("It is noon.");
	}

	@Test
	void reportsScriptedUsageForEachCall() {
		FakeChatModel model = FakeChatModel.replying(ScriptedReply.answer("hi", 7, 3));

		ChatResponse response = model.call(ANY_PROMPT);

		assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(7);
		assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(3);
		assertThat(response.getMetadata().getModel()).isEqualTo(FakeChatModel.MODEL_NAME);
	}

	@Test
	void reportsEmptyUsageWhenProviderOmitsIt() {
		FakeChatModel model = FakeChatModel.usageMissing();

		ChatResponse response = model.call(ANY_PROMPT);

		assertThat(response.getMetadata().getUsage()).isInstanceOf(EmptyUsage.class);
		assertThat(response.getMetadata().getUsage().getTotalTokens()).isZero();
	}

	@Test
	void reportsZeroTokenDefaultUsageWhenProviderSendsZeros() {
		FakeChatModel model = FakeChatModel.usageZero();

		ChatResponse response = model.call(ANY_PROMPT);

		assertThat(response.getMetadata().getUsage()).isInstanceOf(DefaultUsage.class);
		assertThat(response.getMetadata().getUsage().getTotalTokens()).isZero();
	}

	@Test
	void failsWhenCalledMoreTimesThanScripted() {
		FakeChatModel model = FakeChatModel.usageMissing();
		model.call(ANY_PROMPT);

		assertThatIllegalStateException().isThrownBy(() -> model.call(ANY_PROMPT))
			.withMessageContaining("Script exhausted");
	}

	@Test
	void recordsEveryPromptItReceives() {
		FakeChatModel model = FakeChatModel.toolCallThenAnswer();

		model.call(ANY_PROMPT);
		model.call(ANY_PROMPT);

		assertThat(model.callCount()).isEqualTo(2);
		assertThat(model.receivedPrompts()).containsExactly(ANY_PROMPT, ANY_PROMPT);
	}

	@Test
	void exposesToolCallingOptionsSoTheToolLoopActivates() {
		assertThat(FakeChatModel.usageMissing().getOptions()).isInstanceOf(ToolCallingChatOptions.class);
	}

	@Test
	void streamedChunksJoinToTheFullAnswer() {
		FakeChatModel model = FakeChatModel.replying(ScriptedReply.answer("It is noon.", 7, 3));

		StringBuilder joined = new StringBuilder();
		model.stream(ANY_PROMPT).toIterable().forEach(chunk -> joined.append(chunk.getResult().getOutput().getText()));

		assertThat(joined.toString()).isEqualTo("It is noon.");
	}

	@Test
	void onlyTheLastStreamedChunkCarriesTheUsage() {
		FakeChatModel model = FakeChatModel.replying(ScriptedReply.answer("It is noon.", 7, 3));

		List<ChatResponse> chunks = model.stream(ANY_PROMPT).collectList().block();
		
		int length = chunks.size();
		for (int i=0; i < length; i++) {
			ChatResponse chunk = chunks.get(i);
			if (i == length - 1) {
				assertThat(chunk.getMetadata().getUsage()).isNotNull();
				assertThat(chunk.getMetadata().getUsage().getPromptTokens()).isEqualTo(7);
				assertThat(chunk.getMetadata().getUsage().getCompletionTokens()).isEqualTo(3);
			} else {
				assertThat(chunk.getMetadata().getUsage()).isInstanceOf(EmptyUsage.class);
			}
		}

	}

	@Test
	void streamWithoutUsageReportsEmptyUsageOnEveryChunk() {
		FakeChatModel model = FakeChatModel.usageMissing();
		List<ChatResponse> chunks = model.stream(ANY_PROMPT).collectList().block();

		assertThat(chunks).isNotEmpty();
		for (ChatResponse chunk : chunks) {
			assertThat(chunk.getMetadata().getUsage()).isInstanceOf(EmptyUsage.class);
			assertThat(chunk.getMetadata().getUsage().getTotalTokens()).isZero();
		}
	}

	@Test
	void doesNotConsumeScriptUntilSubscribed() {
		FakeChatModel model = FakeChatModel.usageMissing();

		model.stream(ANY_PROMPT);
		assertThat(model.callCount()).isZero();
	}
}
