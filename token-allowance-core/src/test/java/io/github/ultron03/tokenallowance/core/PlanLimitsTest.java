package io.github.ultron03.tokenallowance.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class PlanLimitsTest {

	private static final Limit USER_DAILY = new Limit(Scope.USER, Window.DAILY, null, Money.ofDollars("1.00"));

	private static final Limit GLOBAL_MONTHLY = new Limit(Scope.GLOBAL, Window.MONTHLY, null, Money.ofDollars("500"));

	private static final Limit PRO_USER_DAILY = new Limit(Scope.USER, Window.DAILY, null, Money.ofDollars("10.00"));

	private static final Limit PRO_USER_MINUTE = new Limit(Scope.USER, Window.MINUTE, 20_000L, null);

	private final PlanLimits planLimits = new PlanLimits(List.of(USER_DAILY, GLOBAL_MONTHLY),
			Map.of("pro", List.of(PRO_USER_DAILY, PRO_USER_MINUTE)));

	@Test
	void callerWithoutPlanGetsTheConfiguredLimits() {
		assertThat(this.planLimits.limitsFor(caller(null))).containsExactly(USER_DAILY, GLOBAL_MONTHLY);
	}

	@Test
	void planLimitReplacesLimitOfSameScopeAndWindowAndAddsTheOthers() {
		assertThat(this.planLimits.limitsFor(caller("pro"))).containsExactly(PRO_USER_DAILY, GLOBAL_MONTHLY,
				PRO_USER_MINUTE);
	}

	@Test
	void matchesPlanIgnoringCase() {
		assertThat(this.planLimits.limitsFor(caller("PRO"))).contains(PRO_USER_DAILY);
	}

	@Test
	void unknownPlanGetsTheConfiguredLimits() {
		assertThat(this.planLimits.limitsFor(caller("enterprise"))).containsExactly(USER_DAILY, GLOBAL_MONTHLY);
	}

	@Test
	void rejectsGlobalLimitInPlan() {
		assertThatThrownBy(() -> new PlanLimits(List.of(), Map.of("pro", List.of(GLOBAL_MONTHLY))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("pro");
	}

	@Test
	void rejectsTwoLimitsWithSameScopeAndWindowInPlan() {
		assertThatThrownBy(() -> new PlanLimits(List.of(), Map.of("pro", List.of(PRO_USER_DAILY, USER_DAILY))))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsPlanNamesThatDifferOnlyInCase() {
		assertThatThrownBy(() -> new PlanLimits(List.of(),
				Map.of("pro", List.of(PRO_USER_DAILY), "Pro", List.of(PRO_USER_MINUTE))))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private static Caller caller(String plan) {
		return new Caller(Map.of(Scope.USER, "alice"), plan);
	}
}
