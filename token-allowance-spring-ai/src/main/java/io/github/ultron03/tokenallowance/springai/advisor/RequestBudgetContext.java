package io.github.ultron03.tokenallowance.springai.advisor;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClientRequest;

import io.github.ultron03.tokenallowance.core.Money;
import io.github.ultron03.tokenallowance.core.Scope;

/**
 * Who a request is for and what it has used so far, shared by every model call of one request.
 *
 * <p>Counters are atomic because a stream may continue on another thread.
 */
public final class RequestBudgetContext {

	/** Key of this object in the advisor context. */
	public static final String CONTEXT_KEY = "token-allowance.request-budget";

	private final Map<Scope, String> subjectIds;

	private final AtomicInteger modelCalls = new AtomicInteger();

	private final AtomicReference<Money> cost = new AtomicReference<>(Money.ZERO);

	/** Creates the context for one request. */
	public RequestBudgetContext(Map<Scope, String> subjectIds) {
		Objects.requireNonNull(subjectIds, "subjectIds can't be null");
		this.subjectIds = Map.copyOf(subjectIds);
	}

	/** Returns the context the admission advisor stored in the request, if any. */
	public static Optional<RequestBudgetContext> from(ChatClientRequest request) {
		@Nullable Object value = request.context().get(CONTEXT_KEY);
		if (value instanceof RequestBudgetContext context) {
			return Optional.of(context);
		}
		return Optional.empty();
	}

	/** Returns who the request is for, one id per scope; empty if no subject was found. */
	public Map<Scope, String> subjectIds() {
		return this.subjectIds;
	}

	/** Counts one more model call and returns the new total. */
	public int recordModelCall() {
		return this.modelCalls.incrementAndGet();
	}

	/** Returns how many model calls this request has made. */
	public int modelCalls() {
		return this.modelCalls.get();
	}

	/** Adds the cost of one model call to the request's total. */
	public void addCost(Money amount) {
		Objects.requireNonNull(amount, "amount can't be null");
		this.cost.accumulateAndGet(amount, Money::plus);
	}

	/** Returns what this request has cost so far. */
	public Money cost() {
		return this.cost.get();
	}
}
