package io.github.ultron03.tokenallowance.springai.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import io.github.ultron03.tokenallowance.core.Amount;
import io.github.ultron03.tokenallowance.core.BudgetKey;
import io.github.ultron03.tokenallowance.core.CallSettled;
import io.github.ultron03.tokenallowance.core.DecisionMade;
import io.github.ultron03.tokenallowance.core.Money;
import io.github.ultron03.tokenallowance.core.Scope;
import io.github.ultron03.tokenallowance.core.TokenUsage;
import io.github.ultron03.tokenallowance.core.Window;

class MicrometerBudgetListenerTest {

	private static final String MODEL = "small-model";

	private static final BudgetKey ALICE_TODAY = BudgetKey.of(Scope.USER, "alice", Window.DAILY,
			Instant.parse("2026-11-08T10:00:00Z"), ZoneOffset.UTC);

	private static final Amount ESTIMATE = new Amount(2000, new Money(3000));

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

	private final MicrometerBudgetListener listener = new MicrometerBudgetListener(this.registry);

	@Test
	void countsDecisionByStageOutcomeAndScope() {
		this.listener.onDecision(new DecisionMade(DecisionMade.Stage.RESERVATION, DecisionMade.Outcome.DENIED,
				Scope.USER));

		double count = this.registry.get(MicrometerBudgetListener.DECISIONS)
			.tags("stage", "reservation", "outcome", "denied", "scope", "user")
			.counter()
			.count();
		assertThat(count).isEqualTo(1.0);
	}

	@Test
	void tagsDecisionWithoutUsedUpBudgetAsScopeNone() {
		this.listener.onDecision(new DecisionMade(DecisionMade.Stage.ADMISSION,
				DecisionMade.Outcome.LEDGER_UNAVAILABLE, null));

		double count = this.registry.get(MicrometerBudgetListener.DECISIONS)
			.tags("stage", "admission", "outcome", "ledger-unavailable", "scope", "none")
			.counter()
			.count();
		assertThat(count).isEqualTo(1.0);
	}

	@Test
	void recordsChargedCostInDollarsAndTokens() {
		this.listener.onSettled(settled(new TokenUsage(800, 200, 0, 0), new Amount(1000, new Money(1200)),
				CallSettled.Outcome.CHARGED));

		assertThat(this.registry.get(MicrometerBudgetListener.COST).tags("model", MODEL, "charge", "usage")
			.counter()
			.count()).isEqualTo(0.0012);
		assertThat(this.registry.get(MicrometerBudgetListener.TOKENS).tags("model", MODEL, "charge", "usage")
			.counter()
			.count()).isEqualTo(1000.0);
	}

	@Test
	void recordsReportedTokensAgainstEstimate() {
		this.listener.onSettled(settled(new TokenUsage(400, 100, 0, 0), new Amount(500, new Money(600)),
				CallSettled.Outcome.CHARGED));

		double ratio = this.registry.get(MicrometerBudgetListener.ESTIMATE_RATIO)
			.tags("model", MODEL)
			.summary()
			.max();
		assertThat(ratio).isEqualTo(0.25);
	}

	@Test
	void tagsEstimateChargeAndSkipsRatio() {
		this.listener.onSettled(settled(TokenUsage.ZERO, ESTIMATE, CallSettled.Outcome.ESTIMATE_CHARGED));

		assertThat(this.registry.get(MicrometerBudgetListener.TOKENS).tags("model", MODEL, "charge", "estimate")
			.counter()
			.count()).isEqualTo(2000.0);
		assertThat(this.registry.find(MicrometerBudgetListener.ESTIMATE_RATIO).meter()).isNull();
	}

	@Test
	void recordsNothingForReleasedCall() {
		this.listener.onSettled(settled(TokenUsage.ZERO, Amount.ZERO, CallSettled.Outcome.RELEASED));

		assertThat(this.registry.getMeters()).isEmpty();
	}

	@Test
	void neverTagsMetersWithSubjectId() {
		this.listener.onDecision(new DecisionMade(DecisionMade.Stage.ADMISSION, DecisionMade.Outcome.DENIED,
				Scope.USER));
		this.listener.onSettled(settled(new TokenUsage(800, 200, 0, 0), new Amount(1000, new Money(1200)),
				CallSettled.Outcome.CHARGED));

		assertThat(this.registry.getMeters()).flatExtracting(meter -> meter.getId().getTags())
			.extracting(Tag::getValue)
			.doesNotContain(ALICE_TODAY.subjectId());
	}

	private static CallSettled settled(TokenUsage reported, Amount charged, CallSettled.Outcome outcome) {
		return new CallSettled(MODEL, Set.of(ALICE_TODAY), ESTIMATE, reported, charged, outcome);
	}
}
