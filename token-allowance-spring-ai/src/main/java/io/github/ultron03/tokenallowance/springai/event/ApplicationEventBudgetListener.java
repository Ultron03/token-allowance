package io.github.ultron03.tokenallowance.springai.event;

import java.util.Objects;

import org.springframework.context.ApplicationEventPublisher;

import io.github.ultron03.tokenallowance.core.BudgetListener;
import io.github.ultron03.tokenallowance.core.BudgetThresholdReached;
import io.github.ultron03.tokenallowance.core.CallSettled;

/**
 * Publishes reached thresholds and settled calls as application events, so an app can react with
 * {@code @EventListener}. Event listeners run on the chat thread; slow work belongs behind
 * {@code @Async}.
 */
public class ApplicationEventBudgetListener implements BudgetListener {

	private final ApplicationEventPublisher publisher;

	/** Creates the listener. */
	public ApplicationEventBudgetListener(ApplicationEventPublisher publisher) {
		this.publisher = Objects.requireNonNull(publisher, "publisher can't be null");
	}

	/** Publishes the event once per subject, window and threshold. */
	@Override
	public void onThresholdReached(BudgetThresholdReached event) {
		this.publisher.publishEvent(event);
	}

	/** Publishes the event once per model call, when it is charged or released. */
	@Override
	public void onSettled(CallSettled event) {
		this.publisher.publishEvent(event);
	}
}
