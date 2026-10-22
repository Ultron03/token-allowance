package io.github.ultron03.tokenallowance.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the configured limits to a request: admits it, reserves before each model call and
 * settles after. Adapters only pass in who is calling and what was used; the rules live here.
 */
public class BudgetService {

	private static final Logger log = LoggerFactory.getLogger(BudgetService.class);

	private final UsageLedger ledger;

	private final PriceCatalog priceCatalog;

	private final List<Limit> limits;

	private final EnforcementMode mode;

	private final Clock clock;

	private final ZoneId zone;

	private final Set<String> modelsWithoutUsage;

	/**
	 * Creates the service.
	 * @param limits the configured limits; at most one per scope and window
	 * @param zone the zone windows start in
	 * @throws IllegalArgumentException if two limits share a scope and window
	 */
	public BudgetService(UsageLedger ledger, PriceCatalog priceCatalog, List<Limit> limits, EnforcementMode mode,
			Clock clock, ZoneId zone) {
		Objects.requireNonNull(ledger, "ledger can't be null");
		Objects.requireNonNull(priceCatalog, "priceCatalog can't be null");
		Objects.requireNonNull(limits, "limits can't be null");
		Objects.requireNonNull(mode, "mode can't be null");
		Objects.requireNonNull(clock, "clock can't be null");
		Objects.requireNonNull(zone, "zone can't be null");
		requireOneLimitPerScopeAndWindow(limits);

		this.ledger = ledger;
		this.priceCatalog = priceCatalog;
		this.limits = List.copyOf(limits);
		this.mode = mode;
		this.clock = clock;
		this.zone = zone;
		this.modelsWithoutUsage = ConcurrentHashMap.newKeySet();
	}

	/**
	 * Checks once per request whether finished calls have already used up a budget. Calls still in
	 * flight are not counted here; {@link #reserve} checks those.
	 * @param subjectIds who is calling, one id per scope; {@link Scope#GLOBAL} needs none
	 * @return {@link Decision.Allowed}, or {@link Decision.Denied} in enforce mode
	 */
	public Decision admit(Map<Scope, String> subjectIds) {
		Objects.requireNonNull(subjectIds, "subjectIds can't be null");
		Instant now = this.clock.instant();

		for (Map.Entry<BudgetKey, Limit> entry : keysFor(subjectIds, now).entrySet()) {
			Amount committed = this.ledger.current(entry.getKey()).committed();
			if (entry.getValue().isReachedBy(committed)) {
				if (this.mode == EnforcementMode.ENFORCE) {
					return denied(entry.getKey(), now);
				}
				logObserved(entry.getKey());
			}
		}
		return new Decision.Allowed();
	}

	/**
	 * Holds the estimate of one model call on every budget that applies to the caller.
	 * @param subjectIds who is calling, one id per scope; {@link Scope#GLOBAL} needs none
	 * @param model the model the call goes to, used to price the estimate
	 * @param estimate the expected usage of the call
	 * @return {@link Decision.Reserved}, or {@link Decision.Denied} in enforce mode
	 * @throws UnknownModelException if the model has no price and the unknown-model policy rejects it
	 */
	public Decision reserve(Map<Scope, String> subjectIds, String model, TokenUsage estimate) {
		Objects.requireNonNull(subjectIds, "subjectIds can't be null");
		Objects.requireNonNull(model, "model can't be null");
		Objects.requireNonNull(estimate, "estimate can't be null");
		Instant now = this.clock.instant();
		Map<BudgetKey, Limit> keys = keysFor(subjectIds, now);
		Amount amount = new Amount(estimate.total(), costOf(model, estimate));

		ReservationResult result = this.ledger.reserve(keys, amount);
		if (result instanceof ReservationResult.Reserved reserved) {
			return new Decision.Reserved(reserved.reservation());
		}

		BudgetKey exceededKey = ((ReservationResult.Exceeded) result).key();
		if (this.mode == EnforcementMode.ENFORCE) {
			return denied(exceededKey, now);
		}
		logObserved(exceededKey);
		return new Decision.Reserved(this.ledger.reserveUnchecked(keys.keySet(), amount));
	}

	/**
	 * Charges the actual usage of a finished call. Never denied, because the call already happened.
	 * Charges the estimate instead if the provider reported no usage.
	 * @param model the model the call went to, used to price the usage
	 */
	public void settle(Reservation reservation, String model, TokenUsage actual) {
		Objects.requireNonNull(reservation, "reservation can't be null");
		Objects.requireNonNull(model, "model can't be null");
		Objects.requireNonNull(actual, "actual can't be null");

		if (actual.isZero()) {
			warnUsageMissingOnce(model);
			this.ledger.commit(reservation, reservation.amount());
			return;
		}
		this.ledger.commit(reservation, new Amount(actual.total(), costOf(model, actual)));
	}

	/** Drops the reservation of a call that failed, without charging it. */
	public void release(Reservation reservation) {
		Objects.requireNonNull(reservation, "reservation can't be null");
		this.ledger.release(reservation);
	}

	private static void requireOneLimitPerScopeAndWindow(List<Limit> limits) {
		Set<String> seen = new HashSet<>();
		for (Limit limit : limits) {
			if (!seen.add(limit.scope() + "/" + limit.window())) {
				throw new IllegalArgumentException("More than one " + limit.scope() + " " + limit.window()
						+ " limit; put both caps in one limit");
			}
		}
	}

	private Map<BudgetKey, Limit> keysFor(Map<Scope, String> subjectIds, Instant now) {
		Map<BudgetKey, Limit> keys = new LinkedHashMap<>();
		for (Limit limit : this.limits) {
			@Nullable String subjectId = (limit.scope() == Scope.GLOBAL) ? BudgetKey.GLOBAL_SUBJECT
					: subjectIds.get(limit.scope());
			if (subjectId != null) {
				keys.put(BudgetKey.of(limit.scope(), subjectId, limit.window(), now, this.zone), limit);
			}
		}
		return keys;
	}

	private Money costOf(String model, TokenUsage usage) {
		return this.priceCatalog.priceFor(model).map(price -> price.costOf(usage)).orElse(Money.ZERO);
	}

	private Decision.Denied denied(BudgetKey key, Instant now) {
		String reason = (key.scope() + " " + key.window() + " budget exceeded").toLowerCase(Locale.ROOT);
		Duration retryAfter = Duration.between(now, key.window().endOf(now, this.zone));
		return new Decision.Denied(reason, retryAfter);
	}

	private void logObserved(BudgetKey key) {
		log.warn("Observe mode: {} {} budget exceeded, call allowed", key.scope(), key.window());
		log.debug("Observe mode: budget exceeded for subject '{}'", key.subjectId());
	}

	private void warnUsageMissingOnce(String model) {
		if (this.modelsWithoutUsage.add(model)) {
			log.warn("Model '{}' reported no token usage, charging the estimate instead. "
					+ "Enable usage reporting on the provider (for OpenAI streaming: stream usage).", model);
		}
	}
}
