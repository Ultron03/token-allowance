package io.github.ultron03.tokenallowance.springai.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.ultron03.tokenallowance.core.Amount;
import io.github.ultron03.tokenallowance.core.BudgetKey;
import io.github.ultron03.tokenallowance.core.BudgetThresholdReached;
import io.github.ultron03.tokenallowance.core.Limit;
import io.github.ultron03.tokenallowance.core.Money;
import io.github.ultron03.tokenallowance.core.Scope;
import io.github.ultron03.tokenallowance.core.Window;

class ApplicationEventBudgetListenerTest {

	private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");

	private static final BudgetThresholdReached REACHED = new BudgetThresholdReached(
			BudgetKey.of(Scope.USER, "alice", Window.DAILY, NOW, ZoneOffset.UTC),
			new Limit(Scope.USER, Window.DAILY, 1000L, null), 80, new Amount(800, Money.ZERO),
			Instant.parse("2026-11-09T00:00:00Z"));

	@Test
	void publishesReachedThreshold() {
		List<Object> published = new ArrayList<>();
		ApplicationEventBudgetListener listener = new ApplicationEventBudgetListener(published::add);

		listener.onThresholdReached(REACHED);

		assertThat(published).containsExactly(REACHED);
	}

	@Test
	void keepsGoingWhenEventListenerFails() {
		ApplicationEventBudgetListener listener = new ApplicationEventBudgetListener(event -> {
			throw new IllegalStateException("mail server down");
		});

		assertThatCode(() -> listener.onThresholdReached(REACHED)).doesNotThrowAnyException();
	}
}
