package io.github.ultron03.tokenallowance.springai.advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.tool.annotation.Tool;

import io.github.ultron03.tokenallowance.core.Amount;
import io.github.ultron03.tokenallowance.core.BudgetKey;
import io.github.ultron03.tokenallowance.core.BudgetService;
import io.github.ultron03.tokenallowance.core.ConfiguredPriceCatalog;
import io.github.ultron03.tokenallowance.core.EnforcementMode;
import io.github.ultron03.tokenallowance.core.InMemoryUsageLedger;
import io.github.ultron03.tokenallowance.core.Limit;
import io.github.ultron03.tokenallowance.core.Money;
import io.github.ultron03.tokenallowance.core.Scope;
import io.github.ultron03.tokenallowance.core.UnknownModelPolicy;
import io.github.ultron03.tokenallowance.core.Window;
import io.github.ultron03.tokenallowance.springai.BudgetExceededException;
import io.github.ultron03.tokenallowance.springai.fixture.FakeChatModel;
import io.github.ultron03.tokenallowance.springai.fixture.ScriptedReply;
import io.github.ultron03.tokenallowance.springai.subject.SubjectResolver;
import reactor.core.publisher.Flux;

class AdmissionAdvisorTest {

	private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

	private static final ZoneId ZONE = ZoneOffset.UTC;

	private static final long DAILY_TOKENS = 100;

	private static final Limit USER_DAILY = new Limit(Scope.USER, Window.DAILY, DAILY_TOKENS, null);

	private static final Limit GLOBAL_DAILY = new Limit(Scope.GLOBAL, Window.DAILY, DAILY_TOKENS, null);

	private static final SubjectResolver ALICE = request -> Map.of(Scope.USER, "alice");

	private final Clock clock = Clock.fixed(NOW, ZONE);

	private final InMemoryUsageLedger ledger = new InMemoryUsageLedger(this.clock, ZONE);

	private final CapturingAdvisor capturing = new CapturingAdvisor();

	@Test
	void passesRequestBudgetContextToLaterAdvisors() {
		ChatClient client = client(answering(), ALICE, service(EnforcementMode.ENFORCE, USER_DAILY));

		client.prompt("hi").call().content();

		assertThat(this.capturing.seen).singleElement()
			.satisfies(context -> assertThat(context.subjectIds()).containsExactly(Map.entry(Scope.USER, "alice")));
	}

	@Test
	void resolvesSubjectOncePerRequestDuringToolLoop() {
		AtomicInteger resolves = new AtomicInteger();
		SubjectResolver counting = request -> {
			resolves.incrementAndGet();
			return Map.of(Scope.USER, "alice");
		};
		ChatClient client = client(FakeChatModel.toolCallThenAnswer(), counting,
				service(EnforcementMode.ENFORCE, USER_DAILY));

		client.prompt("What time is it?").tools(new TimeTool()).call().content();

		assertThat(resolves).hasValue(1);
		assertThat(this.capturing.seen).hasSize(2);
		assertThat(this.capturing.seen.get(1)).isSameAs(this.capturing.seen.get(0));
	}

	@Test
	void deniesRequestWhenDailyTokenLimitReached() {
		spend(Scope.USER, "alice", DAILY_TOKENS);
		FakeChatModel model = answering();
		ChatClient client = client(model, ALICE, service(EnforcementMode.ENFORCE, USER_DAILY));

		assertThatThrownBy(() -> client.prompt("hi").call().content())
			.isInstanceOf(BudgetExceededException.class);
		assertThat(model.receivedPrompts()).isEmpty();
	}

	@Test
	void deniedExceptionCarriesRetryAfter() {
		spend(Scope.USER, "alice", DAILY_TOKENS);
		ChatClient client = client(answering(), ALICE, service(EnforcementMode.ENFORCE, USER_DAILY));

		assertThatThrownBy(() -> client.prompt("hi").call().content())
			.isInstanceOfSatisfying(BudgetExceededException.class,
					ex -> assertThat(ex.decision().retryAfter()).isPositive());
	}

	@Test
	void allowsOtherUserWhenOneUserReachedLimit() {
		spend(Scope.USER, "bob", DAILY_TOKENS);
		FakeChatModel model = answering();
		ChatClient client = client(model, ALICE, service(EnforcementMode.ENFORCE, USER_DAILY));

		client.prompt("hi").call().content();

		assertThat(model.receivedPrompts()).hasSize(1);
	}

	@Test
	void appliesGlobalLimitWhenNoSubjectFound() {
		spend(Scope.GLOBAL, BudgetKey.GLOBAL_SUBJECT, DAILY_TOKENS);
		ChatClient client = client(answering(), request -> Map.of(),
				service(EnforcementMode.ENFORCE, USER_DAILY, GLOBAL_DAILY));

		assertThatThrownBy(() -> client.prompt("hi").call().content())
			.isInstanceOf(BudgetExceededException.class);
	}

	@Test
	void allowsRequestInObserveModeWhenLimitReached() {
		spend(Scope.USER, "alice", DAILY_TOKENS);
		FakeChatModel model = answering();
		ChatClient client = client(model, ALICE, service(EnforcementMode.OBSERVE, USER_DAILY));

		client.prompt("hi").call().content();

		assertThat(model.receivedPrompts()).hasSize(1);
	}

	@Test
	void streamPassesContextToLaterAdvisors() {
		ChatClient client = client(answering(), ALICE, service(EnforcementMode.ENFORCE, USER_DAILY));

		client.prompt("hi").stream().content().collectList().block();

		assertThat(this.capturing.seen).singleElement()
			.satisfies(context -> assertThat(context.subjectIds()).containsEntry(Scope.USER, "alice"));
	}

	@Test
	void streamFailsWithBudgetExceededWhenLimitReached() {
		spend(Scope.USER, "alice", DAILY_TOKENS);
		FakeChatModel model = answering();
		ChatClient client = client(model, ALICE, service(EnforcementMode.ENFORCE, USER_DAILY));

		Flux<String> content = client.prompt("hi").stream().content();

		assertThatThrownBy(() -> content.collectList().block()).isInstanceOf(BudgetExceededException.class);
		assertThat(model.receivedPrompts()).isEmpty();
	}

	@Test
	void streamResolvesSubjectOnSubscribingThread() {
		AtomicReference<Thread> resolvingThread = new AtomicReference<>();
		SubjectResolver recording = request -> {
			resolvingThread.set(Thread.currentThread());
			return Map.of(Scope.USER, "alice");
		};
		ChatClient client = client(answering(), recording, service(EnforcementMode.ENFORCE, USER_DAILY));

		client.prompt("hi").stream().content().collectList().block();

		assertThat(resolvingThread).hasValue(Thread.currentThread());
	}

	@Test
	void ordersBeforeChatMemoryAndToolCalling() {
		assertThat(AdmissionAdvisor.ORDER)
			.isLessThan(Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER)
			.isLessThan(ToolCallingAdvisor.DEFAULT_ORDER);
	}

	private ChatClient client(FakeChatModel model, SubjectResolver resolver, BudgetService service) {
		return ChatClient.builder(model)
			.defaultAdvisors(new AdmissionAdvisor(resolver, service), this.capturing)
			.build();
	}

	private BudgetService service(EnforcementMode mode, Limit... limits) {
		ConfiguredPriceCatalog catalog = new ConfiguredPriceCatalog(Map.of(), UnknownModelPolicy.ALLOW_UNCOSTED, null);
		return new BudgetService(this.ledger, catalog, List.of(limits), mode, this.clock, ZONE);
	}

	private void spend(Scope scope, String subjectId, long tokens) {
		BudgetKey key = BudgetKey.of(scope, subjectId, Window.DAILY, NOW, ZONE);
		Amount amount = new Amount(tokens, Money.ZERO);
		this.ledger.commit(this.ledger.reserveUnchecked(Set.of(key), amount), amount);
	}

	private static FakeChatModel answering() {
		return FakeChatModel.replying(ScriptedReply.answer("Hello there.", 5, 3));
	}

	/** Records the budget context each model call sees; ordered inside the tool loop. */
	private static final class CapturingAdvisor implements CallAdvisor, StreamAdvisor {

		private final List<RequestBudgetContext> seen = new CopyOnWriteArrayList<>();

		@Override
		public String getName() {
			return "capturing";
		}

		@Override
		public int getOrder() {
			return ToolCallingAdvisor.DEFAULT_ORDER + 1;
		}

		@Override
		public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
			capture(request);
			return chain.nextCall(request);
		}

		@Override
		public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
			capture(request);
			return chain.nextStream(request);
		}

		private void capture(ChatClientRequest request) {
			Optional<RequestBudgetContext> context = RequestBudgetContext.from(request);
			assertThat(context).as("budget context in request").isPresent();
			context.ifPresent(this.seen::add);
		}
	}

	/** Tool the fake model asks for in the tool-loop test. */
	public static class TimeTool {

		@Tool(name = FakeChatModel.TIME_TOOL_NAME, description = "Returns the current time")
		public String currentTime() {
			return "12:00 PM";
		}
	}
}
