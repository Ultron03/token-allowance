package io.github.ultron03.tokenallowance.springai.metrics;

import java.util.Locale;
import java.util.Objects;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.jspecify.annotations.Nullable;

import io.github.ultron03.tokenallowance.core.BudgetListener;
import io.github.ultron03.tokenallowance.core.CallSettled;
import io.github.ultron03.tokenallowance.core.DecisionMade;
import io.github.ultron03.tokenallowance.core.Scope;

/**
 * Turns budget decisions and charges into Micrometer meters. Tags are limited to model, stage,
 * outcome, scope and charge, never a subject id, so the number of series stays small.
 */
public class MicrometerBudgetListener implements BudgetListener {

	/** Counter of decisions, tagged by stage, outcome and the scope of a used-up budget. */
	public static final String DECISIONS = "token.allowance.decisions";

	/** Counter of charged cost in US dollars, tagged by model and charge. */
	public static final String COST = "token.allowance.cost";

	/** Counter of charged tokens, tagged by model and charge. */
	public static final String TOKENS = "token.allowance.tokens";

	/** Reported tokens divided by estimated tokens per model call, tagged by model. */
	public static final String ESTIMATE_RATIO = "token.allowance.estimate.ratio";

	private static final String STAGE_TAG = "stage";

	private static final String OUTCOME_TAG = "outcome";

	private static final String SCOPE_TAG = "scope";

	private static final String MODEL_TAG = "model";

	private static final String CHARGE_TAG = "charge";

	private static final String NO_SCOPE = "none";

	private static final String CHARGED_USAGE = "usage";

	private static final String CHARGED_ESTIMATE = "estimate";

	private static final double MICROS_PER_DOLLAR = 1_000_000.0;

	private final MeterRegistry registry;

	/** Creates the listener; meters are registered on first use. */
	public MicrometerBudgetListener(MeterRegistry registry) {
		this.registry = Objects.requireNonNull(registry, "registry can't be null");
	}

	@Override
	public void onDecision(DecisionMade event) {
		Counter.builder(DECISIONS)
			.description("Budget decisions")
			.tag(STAGE_TAG, tagValue(event.stage()))
			.tag(OUTCOME_TAG, tagValue(event.outcome()))
			.tag(SCOPE_TAG, scopeTag(event.scope()))
			.register(this.registry)
			.increment();
	}

	/** Records nothing for a released call, since nothing was charged. */
	@Override
	public void onSettled(CallSettled event) {
		if (event.outcome() == CallSettled.Outcome.RELEASED) {
			return;
		}
		boolean reported = event.outcome() == CallSettled.Outcome.CHARGED;
		Tags tags = Tags.of(MODEL_TAG, event.model(), CHARGE_TAG, reported ? CHARGED_USAGE : CHARGED_ESTIMATE);

		Counter.builder(COST)
			.description("Cost charged to budgets")
			.baseUnit("usd")
			.tags(tags)
			.register(this.registry)
			.increment(event.charged().cost().micros() / MICROS_PER_DOLLAR);
		Counter.builder(TOKENS)
			.description("Tokens charged to budgets")
			.baseUnit("tokens")
			.tags(tags)
			.register(this.registry)
			.increment(event.charged().tokens());

		long estimatedTokens = event.estimate().tokens();
		if (reported && estimatedTokens > 0) {
			DistributionSummary.builder(ESTIMATE_RATIO)
				.description("Reported tokens divided by the estimate reserved before the call")
				.tag(MODEL_TAG, event.model())
				.register(this.registry)
				.record((double) event.reported().total() / estimatedTokens);
		}
	}

	private static String scopeTag(@Nullable Scope scope) {
		return (scope != null) ? tagValue(scope) : NO_SCOPE;
	}

	private static String tagValue(Enum<?> value) {
		return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
	}
}
