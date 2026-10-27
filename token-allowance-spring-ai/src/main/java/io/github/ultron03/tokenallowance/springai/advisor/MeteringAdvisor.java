package io.github.ultron03.tokenallowance.springai.advisor;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import io.github.ultron03.tokenallowance.core.Amount;
import io.github.ultron03.tokenallowance.core.BudgetService;
import io.github.ultron03.tokenallowance.core.Decision;
import io.github.ultron03.tokenallowance.core.Reservation;
import io.github.ultron03.tokenallowance.core.TokenEstimator;
import io.github.ultron03.tokenallowance.core.TokenUsage;
import io.github.ultron03.tokenallowance.springai.BudgetExceededException;

/**
 * Meters every model call, including each round of the tool loop: checks the per-request caps,
 * reserves an estimate, then charges the reported usage or releases the reservation if the call fails.
 *
 * <p>Needs {@link AdmissionAdvisor} on the same client.
 */
public class MeteringAdvisor implements CallAdvisor, StreamAdvisor {

	/** Innermost, just before the model call, so the estimate sees the final prompt. */
	public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 100;

	/** Output tokens reserved when the request sets no max tokens. */
	public static final int DEFAULT_OUTPUT_TOKENS = 1024;

	/** Model name used when the request names none, so the unknown-model policy decides. */
	public static final String UNKNOWN_MODEL = "unknown";

	private final BudgetService budgetService;

	private final TokenEstimator tokenEstimator;

	private final InterruptedStreamPolicy interruptedStreamPolicy;

	/** Creates the advisor; interrupted streams are charged the estimate. */
	public MeteringAdvisor(BudgetService budgetService, TokenEstimator tokenEstimator) {
		this(budgetService, tokenEstimator, InterruptedStreamPolicy.CHARGE_ESTIMATE);
	}

	/** Creates the advisor with the given charge for interrupted streams. */
	public MeteringAdvisor(BudgetService budgetService, TokenEstimator tokenEstimator,
			InterruptedStreamPolicy interruptedStreamPolicy) {
		this.budgetService = Objects.requireNonNull(budgetService, "budgetService can't be null");
		this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator can't be null");
		this.interruptedStreamPolicy = Objects.requireNonNull(interruptedStreamPolicy,
				"interruptedStreamPolicy can't be null");
	}

	@Override
	public String getName() {
		return "TokenAllowanceMeteringAdvisor";
	}

	@Override
	public int getOrder() {
		return ORDER;
	}

	/**
	 * @throws BudgetExceededException if a per-request cap or a budget is used up; the model is not called
	 * @throws IllegalStateException if {@link AdmissionAdvisor} is not registered
	 */
	@Override
	public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
		MeteredCall call = start(request);

		ChatClientResponse response;
		try {
			response = chain.nextCall(request);
		}
		catch (Throwable ex) {
			this.budgetService.release(call.reservation());
			throw ex;
		}

		charge(call, UsageMapping.toTokenUsage(response.chatResponse()));
		return response;
	}

	/**
	 * Reserves on subscription, so each subscription is metered as its own model call. On complete,
	 * charges the usage of the last chunk that reported any, or the estimate. An error before the first
	 * chunk releases; a cancel, or an error after the first chunk, follows the {@link InterruptedStreamPolicy}.
	 */
	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
		return Flux.defer(() -> {
			StreamMeter meter = new StreamMeter(start(request));
			return chain.nextStream(request)
				.doOnNext(meter::onChunk)
				.doOnComplete(meter::onComplete)
				.doOnError(meter::onError)
				.doOnCancel(meter::onCancel);
		});
	}

	private MeteredCall start(ChatClientRequest request) {
		RequestBudgetContext context = RequestBudgetContext.from(request)
			.orElseThrow(() -> new IllegalStateException(
					"No request budget context: register AdmissionAdvisor on the same ChatClient"));
		throwIfDenied(this.budgetService.checkRequest(context.modelCalls(), context.cost()));

		Prompt prompt = request.prompt();
		String model = modelOf(prompt);
		TokenUsage estimate = estimateOf(prompt);
		Reservation reservation = reserve(context, model, estimate);
		context.recordModelCall();
		return new MeteredCall(context, model, estimate, reservation);
	}

	private void charge(MeteredCall call, TokenUsage usage) {
		Amount charged = this.budgetService.settle(call.reservation(), call.model(), usage);
		call.context().addCost(charged.cost());
	}

	private Reservation reserve(RequestBudgetContext context, String model, TokenUsage estimate) {
		Decision decision = this.budgetService.reserve(context.subjectIds(), model, estimate);
		throwIfDenied(decision);
		if (!(decision instanceof Decision.Reserved reserved)) {
			throw new IllegalStateException("Expected a reservation but got " + decision);
		}
		return reserved.reservation();
	}

	private TokenUsage estimateOf(Prompt prompt) {
		long input = this.tokenEstimator.estimateInputTokens(PromptText.of(prompt));
		return new TokenUsage(input, outputEstimateOf(prompt), 0, 0);
	}

	private static long outputEstimateOf(Prompt prompt) {
		@Nullable ChatOptions options = prompt.getOptions();
		@Nullable Integer maxTokens = (options != null) ? options.getMaxTokens() : null;
		return (maxTokens != null) ? maxTokens : DEFAULT_OUTPUT_TOKENS;
	}

	private static String modelOf(Prompt prompt) {
		@Nullable ChatOptions options = prompt.getOptions();
		@Nullable String model = (options != null) ? options.getModel() : null;
		return (model != null && !model.isBlank()) ? model : UNKNOWN_MODEL;
	}

	private static void throwIfDenied(Decision decision) {
		if (decision instanceof Decision.Denied denied) {
			throw new BudgetExceededException(denied);
		}
	}

	/** A model call that holds a reservation and still has to be charged or released. */
	private record MeteredCall(RequestBudgetContext context, String model, TokenUsage estimate,
			Reservation reservation) {
	}

	/**
	 * Tracks one subscription to a stream and settles its reservation exactly once, because complete,
	 * error and cancel can race. Settles in {@code doOn...} hooks rather than {@code doFinally}, so the
	 * cost reaches the request context before the tool loop starts its next round.
	 */
	private final class StreamMeter {

		private final MeteredCall call;

		private final AtomicReference<TokenUsage> lastUsage = new AtomicReference<>(TokenUsage.ZERO);

		private final AtomicBoolean anyChunk = new AtomicBoolean();

		private final AtomicBoolean settled = new AtomicBoolean();

		StreamMeter(MeteredCall call) {
			this.call = call;
		}

		void onChunk(ChatClientResponse chunk) {
			this.anyChunk.set(true);
			TokenUsage usage = UsageMapping.toTokenUsage(chunk.chatResponse());
			if (!usage.isZero()) {
				this.lastUsage.set(usage);
			}
		}

		/** A zero usage here means the provider reported none; {@code settle} then charges the estimate. */
		void onComplete() {
			if (claim()) {
				charge(this.call, this.lastUsage.get());
			}
		}

		void onError(Throwable ex) {
			if (!this.anyChunk.get()) {
				if (claim()) {
					MeteringAdvisor.this.budgetService.release(this.call.reservation());
				}
				return;
			}
			onInterrupted();
		}

		void onCancel() {
			onInterrupted();
		}

		private void onInterrupted() {
			if (!claim()) {
				return;
			}
			TokenUsage usage = this.lastUsage.get();
			if (!usage.isZero()) {
				charge(this.call, usage);
				return;
			}
			switch (MeteringAdvisor.this.interruptedStreamPolicy) {
				case CHARGE_ESTIMATE -> charge(this.call, this.call.estimate());
				case RELEASE -> MeteringAdvisor.this.budgetService.release(this.call.reservation());
			}
		}

		/** {@return true for the first caller only, who must then settle the reservation} */
		private boolean claim() {
			return this.settled.compareAndSet(false, true);
		}
	}
}
