package io.github.ultron03.tokenallowance.springai.advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import reactor.core.publisher.Flux;

import io.github.ultron03.tokenallowance.core.Amount;
import io.github.ultron03.tokenallowance.core.BudgetKey;
import io.github.ultron03.tokenallowance.core.BudgetService;
import io.github.ultron03.tokenallowance.core.ConfiguredPriceCatalog;
import io.github.ultron03.tokenallowance.core.ConservativeTokenEstimator;
import io.github.ultron03.tokenallowance.core.EnforcementMode;
import io.github.ultron03.tokenallowance.core.InMemoryUsageLedger;
import io.github.ultron03.tokenallowance.core.Limit;
import io.github.ultron03.tokenallowance.core.ModelPrice;
import io.github.ultron03.tokenallowance.core.Money;
import io.github.ultron03.tokenallowance.core.RequestLimit;
import io.github.ultron03.tokenallowance.core.Scope;
import io.github.ultron03.tokenallowance.core.Spend;
import io.github.ultron03.tokenallowance.core.TokenEstimator;
import io.github.ultron03.tokenallowance.core.UnknownModelPolicy;
import io.github.ultron03.tokenallowance.core.Window;
import io.github.ultron03.tokenallowance.springai.BudgetExceededException;
import io.github.ultron03.tokenallowance.springai.fixture.FakeChatModel;
import io.github.ultron03.tokenallowance.springai.fixture.ScriptedReply;
import io.github.ultron03.tokenallowance.springai.subject.SubjectResolver;

class MeteringAdvisorTest {

	private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

	private static final ZoneId ZONE = ZoneOffset.UTC;

	private static final Limit USER_DAILY = new Limit(Scope.USER, Window.DAILY, 10_000L, null);

	/** Below the default output estimate, so a call without max tokens can't reserve. */
	private static final Limit SMALL_USER_DAILY = new Limit(Scope.USER, Window.DAILY, 1_000L, null);

	/** $1 per MTok input, $2 per MTok output: 5 input + 3 output tokens cost 11 micros. */
	private static final ModelPrice PRICE = new ModelPrice(Money.ofDollars("1.00"), Money.ofDollars("2.00"), null,
			null);

	private static final SubjectResolver ALICE = request -> Map.of(Scope.USER, "alice");

	private static final BudgetKey ALICE_TODAY = BudgetKey.of(Scope.USER, "alice", Window.DAILY, NOW, ZONE);

	private final Clock clock = Clock.fixed(NOW, ZONE);

	private final InMemoryUsageLedger ledger = new InMemoryUsageLedger(this.clock, ZONE);

	private final TokenEstimator estimator = new ConservativeTokenEstimator();

	@Test
	void chargesActualUsageOfCall() {
		ChatClient client = client(answering(), service(RequestLimit.NONE, USER_DAILY));

		client.prompt("hi").call().content();

		assertThat(this.ledger.current(ALICE_TODAY)).isEqualTo(new Spend(new Amount(8, new Money(11)), Amount.ZERO));
	}

	@Test
	void chargesEachModelCallOfToolLoop() {
		ChatClient client = client(FakeChatModel.toolCallThenAnswer(), service(RequestLimit.NONE, USER_DAILY));

		client.prompt("What time is it?").tools(new TimeTool()).call().content();

		long bothCalls = (120 + 15) + (160 + 40);
		assertThat(this.ledger.current(ALICE_TODAY).committed().tokens()).isEqualTo(bothCalls);
		assertThat(this.ledger.current(ALICE_TODAY).reserved()).isEqualTo(Amount.ZERO);
	}

	@Test
	void chargesEstimateWhenUsageMissing() {
		ChatClient client = client(FakeChatModel.usageMissing(), service(RequestLimit.NONE, USER_DAILY));

		client.prompt("hi").call().content();

		assertThat(this.ledger.current(ALICE_TODAY).committed().tokens()).isEqualTo(estimateForHi());
	}

	@Test
	void chargesEstimateWhenUsageHasZeroTokens() {
		ChatClient client = client(FakeChatModel.usageZero(), service(RequestLimit.NONE, USER_DAILY));

		client.prompt("hi").call().content();

		assertThat(this.ledger.current(ALICE_TODAY).committed().tokens()).isEqualTo(estimateForHi());
	}

	@Test
	void releasesReservationWhenModelFails() {
		ChatClient client = client(new FailingChatModel(false), service(RequestLimit.NONE, USER_DAILY));

		assertThatThrownBy(() -> client.prompt("hi").call().content()).hasMessage(FailingChatModel.MESSAGE);

		assertThat(this.ledger.current(ALICE_TODAY)).isEqualTo(new Spend(Amount.ZERO, Amount.ZERO));
	}

	@Test
	void deniesCallWhenEstimateExceedsLimit() {
		FakeChatModel model = answering();
		ChatClient client = client(model, service(RequestLimit.NONE, SMALL_USER_DAILY));

		assertThatThrownBy(() -> client.prompt("hi").call().content()).isInstanceOf(BudgetExceededException.class);

		assertThat(model.receivedPrompts()).isEmpty();
	}

	@Test
	void usesMaxTokensAsOutputEstimate() {
		FakeChatModel model = answering();
		ChatClient client = client(model, service(RequestLimit.NONE, SMALL_USER_DAILY));

		client.prompt("hi").options(ToolCallingChatOptions.builder().maxTokens(100)).call().content();

		assertThat(model.receivedPrompts()).hasSize(1);
	}

	@Test
	void cutsOffToolLoopAtMaxModelCalls() {
		FakeChatModel model = FakeChatModel.toolCallThenAnswer();
		ChatClient client = client(model, service(new RequestLimit(1, null), USER_DAILY));

		assertThatThrownBy(() -> client.prompt("What time is it?").tools(new TimeTool()).call().content())
			.isInstanceOf(BudgetExceededException.class)
			.hasMessage("request model call limit reached");

		assertThat(model.receivedPrompts()).hasSize(1);
		assertThat(this.ledger.current(ALICE_TODAY).committed().tokens()).isEqualTo(120 + 15);
	}

	@Test
	void stopsRequestWhenRequestCostReached() {
		FakeChatModel model = FakeChatModel.toolCallThenAnswer();
		ChatClient client = client(model, service(new RequestLimit(null, new Money(1)), USER_DAILY));

		assertThatThrownBy(() -> client.prompt("What time is it?").tools(new TimeTool()).call().content())
			.isInstanceOf(BudgetExceededException.class)
			.hasMessage("request cost limit reached");

		assertThat(model.receivedPrompts()).hasSize(1);
	}

	@Test
	void chargesLastReportedUsageOfStream() {
		ChatClient client = client(answering(), service(RequestLimit.NONE, USER_DAILY));

		client.prompt("hi").stream().chatResponse().blockLast();

		assertThat(this.ledger.current(ALICE_TODAY)).isEqualTo(new Spend(new Amount(8, new Money(11)), Amount.ZERO));
	}

	@Test
	void chargesEstimateWhenStreamReportsNoUsage() {
		ChatClient client = client(FakeChatModel.usageMissing(), service(RequestLimit.NONE, USER_DAILY));

		client.prompt("hi").stream().chatResponse().blockLast();

		assertThat(this.ledger.current(ALICE_TODAY).committed().tokens()).isEqualTo(estimateForHi());
	}

	@Test
	void chargesEstimateWhenStreamUsageHasZeroTokens() {
		ChatClient client = client(FakeChatModel.usageZero(), service(RequestLimit.NONE, USER_DAILY));

		client.prompt("hi").stream().chatResponse().blockLast();

		assertThat(this.ledger.current(ALICE_TODAY).committed().tokens()).isEqualTo(estimateForHi());
	}

	@Test
	void releasesReservationWhenStreamFailsBeforeFirstChunk() {
		ChatClient client = client(new FailingChatModel(false), service(RequestLimit.NONE, USER_DAILY));

		assertThatThrownBy(() -> client.prompt("hi").stream().chatResponse().blockLast())
			.hasMessage(FailingChatModel.MESSAGE);

		assertThat(this.ledger.current(ALICE_TODAY)).isEqualTo(new Spend(Amount.ZERO, Amount.ZERO));
	}

	@Test
	void chargesEstimateWhenStreamFailsAfterFirstChunk() {
		ChatClient client = client(new FailingChatModel(true), service(RequestLimit.NONE, USER_DAILY));

		assertThatThrownBy(() -> client.prompt("hi").stream().chatResponse().blockLast())
			.hasMessage(FailingChatModel.MESSAGE);

		assertThat(this.ledger.current(ALICE_TODAY).committed().tokens()).isEqualTo(estimateForHi());
		assertThat(this.ledger.current(ALICE_TODAY).reserved()).isEqualTo(Amount.ZERO);
	}

	@Test
	void chargesEstimateWhenStreamCancelled() {
		ChatClient client = client(answering(), service(RequestLimit.NONE, USER_DAILY));

		client.prompt("hi").stream().chatResponse().take(1).blockLast();

		assertThat(this.ledger.current(ALICE_TODAY).committed().tokens()).isEqualTo(estimateForHi());
		assertThat(this.ledger.current(ALICE_TODAY).reserved()).isEqualTo(Amount.ZERO);
	}

	@Test
	void releasesCancelledStreamWithReleasePolicy() {
		ChatClient client = client(answering(), service(RequestLimit.NONE, USER_DAILY),
				InterruptedStreamPolicy.RELEASE);

		client.prompt("hi").stream().chatResponse().take(1).blockLast();

		assertThat(this.ledger.current(ALICE_TODAY)).isEqualTo(new Spend(Amount.ZERO, Amount.ZERO));
	}

	@Test
	void chargesEachModelCallOfStreamedToolLoop() {
		ChatClient client = client(FakeChatModel.toolCallThenAnswer(), service(RequestLimit.NONE, USER_DAILY));

		client.prompt("What time is it?").tools(new TimeTool()).stream().chatResponse().blockLast();

		long bothCalls = (120 + 15) + (160 + 40);
		assertThat(this.ledger.current(ALICE_TODAY).committed().tokens()).isEqualTo(bothCalls);
		assertThat(this.ledger.current(ALICE_TODAY).reserved()).isEqualTo(Amount.ZERO);
	}

	@Test
	void deniesStreamBeforeModelCallWhenEstimateExceedsLimit() {
		FakeChatModel model = answering();
		ChatClient client = client(model, service(RequestLimit.NONE, SMALL_USER_DAILY));

		assertThatThrownBy(() -> client.prompt("hi").stream().chatResponse().blockLast())
			.isInstanceOf(BudgetExceededException.class);

		assertThat(model.receivedPrompts()).isEmpty();
	}

	@Test
	void cutsOffStreamedToolLoopAtMaxModelCalls() {
		FakeChatModel model = FakeChatModel.toolCallThenAnswer();
		ChatClient client = client(model, service(new RequestLimit(1, null), USER_DAILY));

		assertThatThrownBy(
				() -> client.prompt("What time is it?").tools(new TimeTool()).stream().chatResponse().blockLast())
			.isInstanceOf(BudgetExceededException.class)
			.hasMessage("request model call limit reached");

		assertThat(model.receivedPrompts()).hasSize(1);
		assertThat(this.ledger.current(ALICE_TODAY)).isEqualTo(new Spend(new Amount(120 + 15, new Money(150)),
				Amount.ZERO));
	}

	/** The first round's cost must reach the request context before the tool loop starts the second. */
	@Test
	void stopsStreamedRequestWhenRequestCostReached() {
		FakeChatModel model = FakeChatModel.toolCallThenAnswer();
		ChatClient client = client(model, service(new RequestLimit(null, new Money(1)), USER_DAILY));

		assertThatThrownBy(
				() -> client.prompt("What time is it?").tools(new TimeTool()).stream().chatResponse().blockLast())
			.isInstanceOf(BudgetExceededException.class)
			.hasMessage("request cost limit reached");

		assertThat(model.receivedPrompts()).hasSize(1);
	}

	@Test
	void failsWhenAdmissionAdvisorMissing() {
		ChatClient client = ChatClient.builder(answering())
			.defaultAdvisors(new MeteringAdvisor(service(RequestLimit.NONE, USER_DAILY), this.estimator))
			.build();

		assertThatThrownBy(() -> client.prompt("hi").call().content())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("AdmissionAdvisor");
	}

	@Test
	void ordersAfterToolCalling() {
		assertThat(MeteringAdvisor.ORDER).isGreaterThan(ToolCallingAdvisor.DEFAULT_ORDER);
	}

	private ChatClient client(ChatModel model, BudgetService service) {
		return client(model, service, InterruptedStreamPolicy.CHARGE_ESTIMATE);
	}

	private ChatClient client(ChatModel model, BudgetService service, InterruptedStreamPolicy policy) {
		return ChatClient.builder(model)
			.defaultAdvisors(new AdmissionAdvisor(ALICE, service), new MeteringAdvisor(service, this.estimator, policy))
			.build();
	}

	private long estimateForHi() {
		return this.estimator.estimateInputTokens("hi") + MeteringAdvisor.DEFAULT_OUTPUT_TOKENS;
	}

	private BudgetService service(RequestLimit requestLimit, Limit... limits) {
		ConfiguredPriceCatalog catalog = new ConfiguredPriceCatalog(Map.of(FakeChatModel.MODEL_NAME, PRICE),
				UnknownModelPolicy.REJECT, null);
		return new BudgetService(this.ledger, catalog, List.of(limits), requestLimit, EnforcementMode.ENFORCE,
				this.clock, ZONE);
	}

	private static FakeChatModel answering() {
		return FakeChatModel.replying(ScriptedReply.answer("Hello there.", 5, 3));
	}

	/** A model whose provider is down: every call throws, and a stream fails before or after its first chunk. */
	private static final class FailingChatModel implements ChatModel {

		static final String MESSAGE = "provider unavailable";

		private final boolean failAfterFirstChunk;

		FailingChatModel(boolean failAfterFirstChunk) {
			this.failAfterFirstChunk = failAfterFirstChunk;
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			throw new IllegalStateException(MESSAGE);
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			Flux<ChatResponse> failure = Flux.error(new IllegalStateException(MESSAGE));
			if (!this.failAfterFirstChunk) {
				return failure;
			}
			ChatResponse firstChunk = ScriptedReply.answerWithoutUsage("Hello ").toChatResponse(FakeChatModel.MODEL_NAME);
			return Flux.concat(Flux.just(firstChunk), failure);
		}

		@Override
		public ChatOptions getOptions() {
			return ChatOptions.builder().model(FakeChatModel.MODEL_NAME).build();
		}
	}

	/** Tool the fake model asks for in the tool-loop tests. */
	public static class TimeTool {

		@Tool(name = FakeChatModel.TIME_TOOL_NAME, description = "Returns the current time")
		public String currentTime() {
			return "12:00 PM";
		}
	}
}
