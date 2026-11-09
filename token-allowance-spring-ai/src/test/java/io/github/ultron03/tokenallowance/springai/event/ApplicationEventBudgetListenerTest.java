package io.github.ultron03.tokenallowance.springai.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.ultron03.tokenallowance.core.Amount;
import io.github.ultron03.tokenallowance.core.BudgetKey;
import io.github.ultron03.tokenallowance.core.BudgetThresholdReached;
import io.github.ultron03.tokenallowance.core.CallSettled;
import io.github.ultron03.tokenallowance.core.Limit;
import io.github.ultron03.tokenallowance.core.Money;
import io.github.ultron03.tokenallowance.core.Scope;
import io.github.ultron03.tokenallowance.core.TokenUsage;
import io.github.ultron03.tokenallowance.core.Window;

class ApplicationEventBudgetListenerTest {

	private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");

	private static final BudgetKey ALICE_TODAY = BudgetKey.of(Scope.USER, "alice", Window.DAILY, NOW, ZoneOffset.UTC);

	private static final BudgetThresholdReached REACHED = new BudgetThresholdReached(ALICE_TODAY,
			new Limit(Scope.USER, Window.DAILY, 1000L, null), 80, new Amount(800, Money.ZERO),
			Instant.parse("2026-11-09T00:00:00Z"));

	private static final CallSettled SETTLED = new CallSettled("small-model", Set.of(ALICE_TODAY),
			new Amount(1000, Money.ZERO), new TokenUsage(600, 200, 0, 0), new Amount(800, Money.ZERO),
			CallSettled.Outcome.CHARGED);

	private final List<Object> published = new ArrayList<>();

	private final ApplicationEventBudgetListener listener = new ApplicationEventBudgetListener(this.published::add);

	@Test
	void publishesReachedThreshold() {
		this.listener.onThresholdReached(REACHED);

		assertThat(this.published).containsExactly(REACHED);
	}

	@Test
	void publishesSettledCall() {
		this.listener.onSettled(SETTLED);

		assertThat(this.published).containsExactly(SETTLED);
	}

	@Test
	void keepsGoingWhenEventListenerFails() {
		ApplicationEventBudgetListener failing = new ApplicationEventBudgetListener(event -> {
			throw new IllegalStateException("mail server down");
		});

		assertThatCode(() -> failing.onThresholdReached(REACHED)).doesNotThrowAnyException();
	}
}
