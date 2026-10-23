package io.github.ultron03.tokenallowance.springai.advisor;

import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

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
public class MeteringAdvisor implements CallAdvisor {

	/** Innermost, just before the model call, so the estimate sees the final prompt. */
	public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 100;

	/** Output tokens reserved when the request sets no max tokens. */
	public static final int DEFAULT_OUTPUT_TOKENS = 1024;

	/** Model name used when the request names none, so the unknown-model policy decides. */
	public static final String UNKNOWN_MODEL = "unknown";

	private final BudgetService budgetService;

	private final TokenEstimator tokenEstimator;

	/** Creates the advisor. */
	public MeteringAdvisor(BudgetService budgetService, TokenEstimator tokenEstimator) {
		this.budgetService = Objects.requireNonNull(budgetService, "budgetService can't be null");
		this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator can't be null");
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
		RequestBudgetContext context = RequestBudgetContext.from(request)
			.orElseThrow(() -> new IllegalStateException(
					"No request budget context: register AdmissionAdvisor on the same ChatClient"));
		throwIfDenied(this.budgetService.checkRequest(context.modelCalls(), context.cost()));

		Prompt prompt = request.prompt();
		String model = modelOf(prompt);
		Reservation reservation = reserve(context, model, estimateOf(prompt));
		context.recordModelCall();

		ChatClientResponse response;
		try {
			response = chain.nextCall(request);
		}
		catch (Throwable ex) {
			this.budgetService.release(reservation);
			throw ex;
		}

		Amount charged = this.budgetService.settle(reservation, model,
				UsageMapping.toTokenUsage(response.chatResponse()));
		context.addCost(charged.cost());
		return response;
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
}
