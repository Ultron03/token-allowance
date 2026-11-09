package io.github.ultron03.tokenallowance.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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

	private final RequestLimit requestLimit;

	private final Set<String> modelsWithoutUsage;

	private final LedgerFailureMode failureMode;

	private final AtomicBoolean ledgerDown;

	private static final int MIN_THRESHOLD = 1;

	private final List<Integer> thresholds;

	private final List<BudgetListener> listeners;

	/**
	 * Creates the service without per-request caps, failing open in observe mode and closed in enforce mode.
	 * @param limits the configured limits; at most one per scope and window
	 * @param zone the zone windows start in
	 * @throws IllegalArgumentException if two limits share a scope and window
	 */
	public BudgetService(UsageLedger ledger, PriceCatalog priceCatalog, List<Limit> limits, EnforcementMode mode,
			Clock clock, ZoneId zone) {
		this(ledger, priceCatalog, limits, RequestLimit.NONE, mode, LedgerFailureMode.defaultFor(mode), clock, zone);
	}

	/**
	 * Creates the service without thresholds or listeners.
	 * @param limits the configured limits; at most one per scope and window
	 * @param requestLimit the caps on each single request
	 * @param failureMode what happens to new calls while the ledger can't be reached
	 * @param zone the zone windows start in
	 * @throws IllegalArgumentException if two limits share a scope and window
	 */
	public BudgetService(UsageLedger ledger, PriceCatalog priceCatalog, List<Limit> limits, RequestLimit requestLimit,
			EnforcementMode mode, LedgerFailureMode failureMode, Clock clock, ZoneId zone) {
		this(ledger, priceCatalog, limits, requestLimit, mode, failureMode, clock, zone, List.of(), List.of());
	}

	/**
	 * Creates the service.
	 * @param limits the configured limits; at most one per scope and window
	 * @param requestLimit the caps on each single request
	 * @param failureMode what happens to new calls while the ledger can't be reached
	 * @param zone the zone windows start in
	 * @param thresholds percents of a limit at which listeners are told, each 1 to 100
	 * @param listeners told about every decision, settled call and reached threshold, in this order
	 * @throws IllegalArgumentException if two limits share a scope and window, or a threshold is out of range
	 */
	public BudgetService(UsageLedger ledger, PriceCatalog priceCatalog, List<Limit> limits, RequestLimit requestLimit,
			EnforcementMode mode, LedgerFailureMode failureMode, Clock clock, ZoneId zone,
			List<Integer> thresholds, List<BudgetListener> listeners) {
		Objects.requireNonNull(ledger, "ledger can't be null");
		Objects.requireNonNull(priceCatalog, "priceCatalog can't be null");
		Objects.requireNonNull(limits, "limits can't be null");
		Objects.requireNonNull(requestLimit, "requestLimit can't be null");
		Objects.requireNonNull(mode, "mode can't be null");
		Objects.requireNonNull(failureMode, "failureMode can't be null");
		Objects.requireNonNull(clock, "clock can't be null");
		Objects.requireNonNull(zone, "zone can't be null");
		Objects.requireNonNull(thresholds, "thresholds can't be null");
		Objects.requireNonNull(listeners, "listeners can't be null");
		requireOneLimitPerScopeAndWindow(limits);
		requireValidThresholds(thresholds);

		this.ledger = ledger;
		this.priceCatalog = priceCatalog;
		this.limits = List.copyOf(limits);
		this.requestLimit = requestLimit;
		this.mode = mode;
		this.failureMode = failureMode;
		this.clock = clock;
		this.zone = zone;
		this.modelsWithoutUsage = ConcurrentHashMap.newKeySet();
		this.ledgerDown = new AtomicBoolean(false);
		this.thresholds = List.copyOf(new TreeSet<>(thresholds));
		this.listeners = List.copyOf(listeners);
	}

	/**
	 * Checks once per request whether finished calls have already used up a budget. Calls still in
	 * flight are not counted here; {@link #reserve} checks those.
	 * @param subjectIds who is calling, one id per scope; {@link Scope#GLOBAL} needs none
	 * @return {@link Decision.Allowed}, or {@link Decision.Denied} in enforce mode
	 * @throws LedgerUnavailableException if the ledger can't be reached and the failure mode is fail-closed
	 */
	public Decision admit(Map<Scope, String> subjectIds) {
		Objects.requireNonNull(subjectIds, "subjectIds can't be null");
		Instant now = this.clock.instant();
		Map<BudgetKey, Limit> keys = keysFor(subjectIds, now);

		try {
			Decision decision = admitOnLedger(keys, now);
			markLedgerUp();
			return decision;
		}
		catch (LedgerUnavailableException ex) {
			notifyDecision(DecisionMade.Stage.ADMISSION, DecisionMade.Outcome.LEDGER_UNAVAILABLE, null);
			onLedgerUnavailable(ex);
			return new Decision.Allowed();
		}
	}

	private Decision admitOnLedger(Map<BudgetKey, Limit> keys, Instant now) {
		@Nullable Scope observedScope = null;
		for (Map.Entry<BudgetKey, Limit> entry : keys.entrySet()) {
			Amount committed = this.ledger.current(entry.getKey()).committed();
			if (entry.getValue().isReachedBy(committed)) {
				if (this.mode == EnforcementMode.ENFORCE) {
					notifyDecision(DecisionMade.Stage.ADMISSION, DecisionMade.Outcome.DENIED, entry.getKey().scope());
					return denied(entry.getKey(), now);
				}
				logObserved(entry.getKey());
				if (observedScope == null) {
					observedScope = entry.getKey().scope();
				}
			}
		}
		DecisionMade.Outcome outcome = (observedScope == null) ? DecisionMade.Outcome.ALLOWED
				: DecisionMade.Outcome.OBSERVED;
		notifyDecision(DecisionMade.Stage.ADMISSION, outcome, observedScope);
		return new Decision.Allowed();
	}

	/**
	 * Checks before each model call whether the request has used up its own caps. Only finished
	 * calls count, so a request can go over the cost cap by at most one call.
	 * @param callsSoFar model calls this request has already made
	 * @param costSoFar what those calls cost
	 * @return {@link Decision.Allowed}, or {@link Decision.Denied} in enforce mode, with no wait
	 *         because the next request starts with fresh counters
	 */
	public Decision checkRequest(int callsSoFar, Money costSoFar) {
		Objects.requireNonNull(costSoFar, "costSoFar can't be null");
		@Nullable Integer maxModelCalls = this.requestLimit.maxModelCalls();
		@Nullable Money maxCost = this.requestLimit.maxCost();

		@Nullable String reason = null;
		if (maxModelCalls != null && callsSoFar >= maxModelCalls) {
			reason = "request model call limit reached";
		}
		else if (maxCost != null && !maxCost.isGreaterThan(costSoFar)) {
			reason = "request cost limit reached";
		}

		if (reason == null) {
			return new Decision.Allowed();
		}
		if (this.mode == EnforcementMode.ENFORCE) {
			notifyDecision(DecisionMade.Stage.REQUEST, DecisionMade.Outcome.DENIED, null);
			return new Decision.Denied(reason, Duration.ZERO);
		}
		notifyDecision(DecisionMade.Stage.REQUEST, DecisionMade.Outcome.OBSERVED, null);
		log.warn("Observe mode: {}, call allowed", reason);
		return new Decision.Allowed();
	}

	/**
	 * Holds the estimate of one model call on every budget that applies to the caller.
	 * @param subjectIds who is calling, one id per scope; {@link Scope#GLOBAL} needs none
	 * @param model the model the call goes to, used to price the estimate
	 * @param estimate the expected usage of the call
	 * @return {@link Decision.Reserved}, or {@link Decision.Denied} in enforce mode
	 * @throws UnknownModelException if the model has no price and the unknown-model policy rejects it
	 * @throws LedgerUnavailableException if the ledger can't be reached and the failure mode is fail-closed
	 */
	public Decision reserve(Map<Scope, String> subjectIds, String model, TokenUsage estimate) {
		Objects.requireNonNull(subjectIds, "subjectIds can't be null");
		Objects.requireNonNull(model, "model can't be null");
		Objects.requireNonNull(estimate, "estimate can't be null");
		Instant now = this.clock.instant();
		Map<BudgetKey, Limit> keys = keysFor(subjectIds, now);
		Amount amount = new Amount(estimate.total(), costOf(model, estimate));

		try {
			Decision decision = reserveOnLedger(keys, amount, now);
			markLedgerUp();
			return decision;
		}
		catch (LedgerUnavailableException ex) {
			notifyDecision(DecisionMade.Stage.RESERVATION, DecisionMade.Outcome.LEDGER_UNAVAILABLE, null);
			onLedgerUnavailable(ex);
			// Never stored, so settling it later changes nothing.
			return new Decision.Reserved(new Reservation(UUID.randomUUID().toString(), keys.keySet(), amount));
		}
	}

	private Decision reserveOnLedger(Map<BudgetKey, Limit> keys, Amount amount, Instant now) {
		ReservationResult result = this.ledger.reserve(keys, amount);
		if (result instanceof ReservationResult.Reserved reserved) {
			notifyDecision(DecisionMade.Stage.RESERVATION, DecisionMade.Outcome.ALLOWED, null);
			return new Decision.Reserved(reserved.reservation());
		}

		BudgetKey exceededKey = ((ReservationResult.Exceeded) result).key();
		if (this.mode == EnforcementMode.ENFORCE) {
			notifyDecision(DecisionMade.Stage.RESERVATION, DecisionMade.Outcome.DENIED, exceededKey.scope());
			return denied(exceededKey, now);
		}
		logObserved(exceededKey);
		Reservation reservation = this.ledger.reserveUnchecked(keys.keySet(), amount);
		notifyDecision(DecisionMade.Stage.RESERVATION, DecisionMade.Outcome.OBSERVED, exceededKey.scope());
		return new Decision.Reserved(reservation);
	}

	/**
	 * Charges the actual usage of a finished call. Never denied, because the call already happened.
	 * Charges the estimate instead if the provider reported no usage. If the ledger can't be reached
	 * the charge is lost and logged, in every failure mode.
	 * @param model the model the call went to, used to price the usage
	 * @return what was charged, so the caller can add it to the request's total
	 */
	public Amount settle(Reservation reservation, String model, TokenUsage actual) {
		Objects.requireNonNull(reservation, "reservation can't be null");
		Objects.requireNonNull(model, "model can't be null");
		Objects.requireNonNull(actual, "actual can't be null");

		Amount charged;
		if (actual.isZero()) {
			warnUsageMissingOnce(model);
			charged = reservation.amount();
		}
		else {
			charged = new Amount(actual.total(), costOf(model, actual));
		}

		Map<BudgetKey, Amount> committedAfter = Map.of();
		try {
			committedAfter = this.ledger.commit(reservation, charged);
			markLedgerUp();
		}
		catch (LedgerUnavailableException ex) {
			markLedgerDown(ex);
			log.debug("Reservation {} not settled, budget store unavailable", reservation.id());
		}
		CallSettled.Outcome outcome = actual.isZero() ? CallSettled.Outcome.ESTIMATE_CHARGED
				: CallSettled.Outcome.CHARGED;
		notifySettled(new CallSettled(model, reservation.keys(), reservation.amount(), actual, charged, outcome));
		notifyThresholdsReached(committedAfter, charged);
		return charged;
	}

	/**
	 * Drops the reservation of a call that failed, without charging it. Never throws if the ledger can't be reached.
	 * @param model the model the call went to, passed on to listeners
	 */
	public void release(Reservation reservation, String model) {
		Objects.requireNonNull(reservation, "reservation can't be null");
		Objects.requireNonNull(model, "model can't be null");
		try {
			this.ledger.release(reservation);
			markLedgerUp();
		}
		catch (LedgerUnavailableException ex) {
			markLedgerDown(ex);
			log.debug("Reservation {} not released, budget store unavailable", reservation.id());
		}
		notifySettled(new CallSettled(model, reservation.keys(), reservation.amount(), TokenUsage.ZERO, Amount.ZERO,
				CallSettled.Outcome.RELEASED));
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

	/**
	 * Reads where a subject stands against each limit of a scope in the current window. Changes
	 * nothing and applies no failure mode.
	 * @param subjectId ignored for {@link Scope#GLOBAL}, which has one shared budget
	 * @return one status per limit of the scope, in configuration order; empty if the scope has none
	 * @throws LedgerUnavailableException if the ledger can't be reached
	 */
	public List<BudgetStatus> statusOf(Scope scope, String subjectId) {
		Objects.requireNonNull(scope, "scope can't be null");
		Objects.requireNonNull(subjectId, "subjectId can't be null");
		Instant now = this.clock.instant();

		List<BudgetStatus> statuses = new ArrayList<>();
		for (Limit limit : this.limits) {
			if (limit.scope() == scope) {
				BudgetKey key = BudgetKey.of(scope, subjectId, limit.window(), now, this.zone);
				statuses.add(new BudgetStatus(limit, key.windowStart(), limit.window().endOf(now, this.zone),
						this.ledger.current(key)));
			}
		}
		return statuses;
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

	/** Rethrows in fail-closed mode; in fail-open mode the caller goes ahead without the ledger. */
	private void onLedgerUnavailable(LedgerUnavailableException ex) {
		markLedgerDown(ex);
		if (this.failureMode == LedgerFailureMode.FAIL_CLOSED) {
			throw ex;
		}
	}

	private void markLedgerDown(LedgerUnavailableException ex) {
		if (this.ledgerDown.compareAndSet(false, true)) {
			String consequence = (this.failureMode == LedgerFailureMode.FAIL_OPEN)
					? "calls go ahead without budget checks and their spend is not recorded"
					: "new calls are rejected until it is back";
			log.warn("Budget store unavailable; {}", consequence, ex);
		}
	}

	private void markLedgerUp() {
		if (this.ledgerDown.compareAndSet(true, false)) {
			log.info("Budget store reachable again");
		}
	}

	private void warnUsageMissingOnce(String model) {
		if (this.modelsWithoutUsage.add(model)) {
			log.warn("Model '{}' reported no token usage, charging the estimate instead. "
					+ "Enable usage reporting on the provider (for OpenAI streaming: stream usage).", model);
		}
	}

	private void notifyDecision(DecisionMade.Stage stage, DecisionMade.Outcome outcome, @Nullable Scope scope) {
		DecisionMade event = new DecisionMade(stage, outcome, scope);
		for (BudgetListener listener : this.listeners) {
			listener.onDecision(event);
		}
	}

	private void notifySettled(CallSettled settled) {
		for (BudgetListener listener : this.listeners) {
			listener.onSettled(settled);
		}
	}

	private static void requireValidThresholds(List<Integer> thresholds) {
		for (int threshold : thresholds) {
			if (threshold < MIN_THRESHOLD || threshold > Limit.FULL_PERCENT) {
				throw new IllegalArgumentException("Threshold " + threshold + " must be between " + MIN_THRESHOLD
						+ " and " + Limit.FULL_PERCENT);
			}
		}
	}

	private @Nullable Limit limitFor(BudgetKey key) {
		for (Limit limit : this.limits) {
			if (limit.appliesTo(key)) {
				return limit;
			}
		}
		return null;
	}

	/** Tells listeners about each threshold this charge crossed; commits on one key are serialised, so only one call crosses it. */
	private void notifyThresholdsReached(Map<BudgetKey, Amount> committedAfter, Amount charged) {
		for (Map.Entry<BudgetKey, Amount> entry : committedAfter.entrySet()) {
			BudgetKey key = entry.getKey();
			Amount after = entry.getValue();
			@Nullable Limit limit = limitFor(key);
			if (limit == null) {
				continue;
			}
			Amount before = after.minus(charged);
			Instant resetsAt = key.window().endOf(key.windowStart(), this.zone);
			for (int percent : this.thresholds) {
				if (!limit.reachesPercent(before, percent) && limit.reachesPercent(after, percent)) {
					BudgetThresholdReached event = new BudgetThresholdReached(key, limit, percent, after, resetsAt);
					for (BudgetListener listener : this.listeners) {
						listener.onThresholdReached(event);
					}
				}
			}
		}
	}
}
