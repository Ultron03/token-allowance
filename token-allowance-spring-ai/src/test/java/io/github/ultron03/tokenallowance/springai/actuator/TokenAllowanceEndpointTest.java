package io.github.ultron03.tokenallowance.springai.actuator;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.ultron03.tokenallowance.core.BudgetService;
import io.github.ultron03.tokenallowance.core.Caller;
import io.github.ultron03.tokenallowance.core.ConfiguredPriceCatalog;
import io.github.ultron03.tokenallowance.core.Decision;
import io.github.ultron03.tokenallowance.core.EnforcementMode;
import io.github.ultron03.tokenallowance.core.InMemoryUsageLedger;
import io.github.ultron03.tokenallowance.core.LedgerFailureMode;
import io.github.ultron03.tokenallowance.core.Limit;
import io.github.ultron03.tokenallowance.core.ModelPrice;
import io.github.ultron03.tokenallowance.core.Money;
import io.github.ultron03.tokenallowance.core.PlanLimits;
import io.github.ultron03.tokenallowance.core.RequestLimit;
import io.github.ultron03.tokenallowance.core.Scope;
import io.github.ultron03.tokenallowance.core.TokenUsage;
import io.github.ultron03.tokenallowance.core.UnknownModelPolicy;
import io.github.ultron03.tokenallowance.core.Window;
import io.github.ultron03.tokenallowance.springai.actuator.TokenAllowanceEndpoint.AmountDescriptor;
import io.github.ultron03.tokenallowance.springai.actuator.TokenAllowanceEndpoint.BudgetDescriptor;
import io.github.ultron03.tokenallowance.springai.actuator.TokenAllowanceEndpoint.SubjectBudgets;

class TokenAllowanceEndpointTest {

	private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");

	private static final String MODEL = "small-model";

	private static final Limit USER_DAILY = new Limit(Scope.USER, Window.DAILY, 2000L, Money.ofDollars("1.00"));

	private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

	private static final Limit PRO_USER_DAILY = new Limit(Scope.USER, Window.DAILY, 20_000L, null);

	private final BudgetService budgetService = new BudgetService(new InMemoryUsageLedger(this.clock, ZoneOffset.UTC),
			new ConfiguredPriceCatalog(
					Map.of(MODEL, new ModelPrice(Money.ofDollars("1.00"), Money.ofDollars("2.00"), null, null)),
					UnknownModelPolicy.REJECT, null),
			new PlanLimits(List.of(USER_DAILY), Map.of("pro", List.of(PRO_USER_DAILY))), RequestLimit.NONE,
			EnforcementMode.ENFORCE, LedgerFailureMode.FAIL_CLOSED, this.clock, ZoneOffset.UTC, List.of(), List.of());

	private final TokenAllowanceEndpoint endpoint = new TokenAllowanceEndpoint(this.budgetService);

	@Test
	void showsSpendAndCapsInDollars() {
		Decision decision = this.budgetService.reserve(new Caller(Map.of(Scope.USER, "alice"), null), MODEL,
				new TokenUsage(1000, 500, 0, 0));

		SubjectBudgets budgets = this.endpoint.budgets(Scope.USER, "alice", null);

		assertThat(decision).isInstanceOf(Decision.Reserved.class);
		assertThat(budgets).isEqualTo(new SubjectBudgets("user", "alice", null,
				List.of(new BudgetDescriptor("daily", Instant.parse("2026-11-08T00:00:00Z"),
						Instant.parse("2026-11-09T00:00:00Z"), 2000L, new BigDecimal("1.000000"),
						new AmountDescriptor(0, new BigDecimal("0.000000")),
						new AmountDescriptor(1500, new BigDecimal("0.002000"))))));
	}

	@Test
	void showsLimitOfGivenPlan() {
		SubjectBudgets budgets = this.endpoint.budgets(Scope.USER, "alice", "pro");

		assertThat(budgets.plan()).isEqualTo("pro");
		assertThat(budgets.budgets()).singleElement().extracting(BudgetDescriptor::maxTokens).isEqualTo(20_000L);
	}

	@Test
	void returnsNullWhenScopeHasNoLimits() {
		assertThat(this.endpoint.budgets(Scope.TENANT, "acme", null)).isNull();
	}
}
