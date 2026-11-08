package io.github.ultron03.tokenallowance.springai.event;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import io.github.ultron03.tokenallowance.core.BudgetListener;
import io.github.ultron03.tokenallowance.core.BudgetThresholdReached;

/**
 * Publishes reached thresholds as application events, so an app can react with
 * {@code @EventListener}. Event listeners run on the chat thread; slow work belongs behind
 * {@code @Async}.
 */
public class ApplicationEventBudgetListener implements BudgetListener {

	private static final Logger log = LoggerFactory.getLogger(ApplicationEventBudgetListener.class);

	private final ApplicationEventPublisher publisher;

	/** Creates the listener. */
	public ApplicationEventBudgetListener(ApplicationEventPublisher publisher) {
		this.publisher = Objects.requireNonNull(publisher, "publisher can't be null");
	}

	/** Publishes the event; a failing event listener is logged, so it can't fail a call that was already paid for. */
	@Override
	public void onThresholdReached(BudgetThresholdReached event) {
		try {
			this.publisher.publishEvent(event);
		}
		catch (RuntimeException ex) {
			log.warn("An event listener failed on a budget threshold; the chat call goes on", ex);
		}
	}
}
