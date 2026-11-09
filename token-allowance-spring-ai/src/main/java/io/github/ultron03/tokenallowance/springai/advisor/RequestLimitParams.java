package io.github.ultron03.tokenallowance.springai.advisor;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClientRequest;

import io.github.ultron03.tokenallowance.core.Money;
import io.github.ultron03.tokenallowance.core.RequestLimit;

/**
 * Advisor param keys an endpoint sets to replace the configured per-request caps, usually once on its
 * client: {@code builder.defaultAdvisors(a -> a.param(RequestLimitParams.MAX_MODEL_CALLS, 10))}.
 * A cap the endpoint doesn't set comes from {@code token-allowance.per-request}.
 */
public final class RequestLimitParams {

	/** Key for the most model calls one request may make, as a whole number. */
	public static final String MAX_MODEL_CALLS = "token-allowance.max-model-calls";

	/** Key for the most dollars one request may cost, such as {@code "0.50"}. */
	public static final String MAX_REQUEST_COST = "token-allowance.max-request-cost";

	private RequestLimitParams() {
	}

	/**
	 * Reads the caps the request's params set.
	 * @throws IllegalArgumentException if a param is not a number greater than zero
	 */
	static RequestLimit of(ChatClientRequest request) {
		@Nullable Object maxModelCalls = request.context().get(MAX_MODEL_CALLS);
		@Nullable Object maxCost = request.context().get(MAX_REQUEST_COST);
		try {
			return new RequestLimit((maxModelCalls != null) ? Integer.valueOf(maxModelCalls.toString().strip()) : null,
					(maxCost != null) ? Money.ofDollars(maxCost.toString().strip()) : null);
		}
		catch (IllegalArgumentException | ArithmeticException ex) {
			throw new IllegalArgumentException("Advisor params " + MAX_MODEL_CALLS + "=" + maxModelCalls + " and "
					+ MAX_REQUEST_COST + "=" + maxCost + " must be numbers greater than zero", ex);
		}
	}
}
