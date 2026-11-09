package io.github.ultron03.tokenallowance.springai.actuator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.endpoint.OperationResponseBody;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;

import io.github.ultron03.tokenallowance.core.Amount;
import io.github.ultron03.tokenallowance.core.BudgetService;
import io.github.ultron03.tokenallowance.core.BudgetStatus;
import io.github.ultron03.tokenallowance.core.Money;
import io.github.ultron03.tokenallowance.core.Scope;

/**
 * Shows where a user, tenant, session or the global budget stands in the current windows, at
 * {@code /actuator/tokenallowance/{scope}/{subjectId}}, with the limits of {@code ?plan=} if given.
 * Answers 404 when the scope has no limits.
 */
@Endpoint(id = TokenAllowanceEndpoint.ID)
public class TokenAllowanceEndpoint {

	/** The endpoint id, used in the URL and in {@code management.endpoints.web.exposure.include}. */
	public static final String ID = "tokenallowance";

	private final BudgetService budgetService;

	/** Creates the endpoint. */
	public TokenAllowanceEndpoint(BudgetService budgetService) {
		this.budgetService = Objects.requireNonNull(budgetService, "budgetService can't be null");
	}

	/**
	 * Reads the subject's spend and limits.
	 * @param subjectId ignored for the global scope, which has one shared budget
	 * @param plan the subject's plan, or null for the limits every caller has
	 * @return the subject's budgets, or null if the scope has no limits
	 */
	@ReadOperation
	public @Nullable SubjectBudgets budgets(@Selector Scope scope, @Selector String subjectId, @Nullable String plan) {
		List<BudgetStatus> statuses = this.budgetService.statusOf(scope, subjectId, plan);
		if (statuses.isEmpty()) {
			return null;
		}
		return new SubjectBudgets(lowerCase(scope), subjectId, plan,
				statuses.stream().map(BudgetDescriptor::of).toList());
	}

	private static String lowerCase(Enum<?> value) {
		return value.name().toLowerCase(Locale.ROOT);
	}

	/**
	 * The response body.
	 *
	 * @param scope the scope asked for
	 * @param subjectId the subject asked for
	 * @param plan the plan asked for, or null for the limits every caller has
	 * @param budgets one entry per limit of the scope
	 */
	public record SubjectBudgets(String scope, String subjectId, @Nullable String plan, List<BudgetDescriptor> budgets)
			implements OperationResponseBody {
	}

	/**
	 * One limit and the spend against it in the current window. Costs are in dollars.
	 *
	 * @param window the limit's window
	 * @param windowStart when the current window began
	 * @param resetsAt when the current window ends
	 * @param maxTokens the token cap, or null if the limit has none
	 * @param maxCost the cost cap, or null if the limit has none
	 * @param committed what finished calls used
	 * @param reserved what calls in flight hold
	 */
	public record BudgetDescriptor(String window, Instant windowStart, Instant resetsAt, @Nullable Long maxTokens,
			@Nullable BigDecimal maxCost, AmountDescriptor committed, AmountDescriptor reserved) {

		static BudgetDescriptor of(BudgetStatus status) {
			@Nullable Money maxCost = status.limit().maxCost();
			return new BudgetDescriptor(lowerCase(status.limit().window()), status.windowStart(), status.resetsAt(),
					status.limit().maxTokens(), (maxCost != null) ? maxCost.toDollars() : null,
					AmountDescriptor.of(status.spend().committed()), AmountDescriptor.of(status.spend().reserved()));
		}
	}

	/**
	 * Tokens and their cost in dollars.
	 *
	 * @param tokens the tokens
	 * @param cost the cost in dollars
	 */
	public record AmountDescriptor(long tokens, BigDecimal cost) {

		static AmountDescriptor of(Amount amount) {
			return new AmountDescriptor(amount.tokens(), amount.cost().toDollars());
		}
	}
}
