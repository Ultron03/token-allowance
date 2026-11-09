package io.github.ultron03.tokenallowance.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
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
 * Applies the caller's limits to a request: admits it, reserves before each model call and
 * settles after. Adapters only pass in who is calling and what was used; the rules live here.
 */
public class BudgetService {

	private static final Logger log = LoggerFactory.getLogger(BudgetService.class);

	private final UsageLedger ledger;

	private final PriceCatalog priceCatalog;

	private final LimitResolver limitResolver;

	private final EnforcementMode mode;

	private final Clock clock;

	private final ZoneId zone;

	private final RequestLimit requestLimit;

	private final Set<String> modelsWithoutUsage;

	private final Set<String> uncostedModels;

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
		this(ledger, priceCatalog, new PlanLimits(limits, Map.of()), requestLimit, mode, failureMode, clock, zone,
				List.of(), List.of());
	}

	/**
	 * Creates the service.
	 * @param limitResolver decides which limits apply to each caller
	 * @param requestLimit the caps on each single request, unless an endpoint sets its own
	 * @param failureMode what happens to new calls while the ledger can't be reached
	 * @param zone the zone windows start in
	 * @param thresholds percents of a limit at which listeners are told, each 1 to 100
	 * @param listeners told about every decision, settled call and reached threshold, in this order
	 * @throws IllegalArgumentException if a threshold is out of range
	 */
	public BudgetService(UsageLedger ledger, PriceCatalog priceCatalog, LimitResolver limitResolver,
			RequestLimit requestLimit,
			EnforcementMode mode, LedgerFailureMode failureMode, Clock clock, ZoneId zone,
			List<Integer> thresholds, List<BudgetListener> listeners) {
		Objects.requireNonNull(ledger, "ledger can't be null");
		Objects.requireNonNull(priceCatalog, "priceCatalog can't be null");
		Objects.requireNonNull(limitResolver, "limitResolver can't be null");
		Objects.requireNonNull(requestLimit, "requestLimit can't be null");
		Objects.requireNonNull(mode, "mode can't be null");
		Objects.requireNonNull(failureMode, "failureMode can't be null");
		Objects.requireNonNull(clock, "clock can't be null");
		Objects.requireNonNull(zone, "zone can't be null");
		Objects.requireNonNull(thresholds, "thresholds can't be null");
		Objects.requireNonNull(listeners, "listeners can't be null");
		requireValidThresholds(thresholds);

		this.ledger = ledger;
		this.priceCatalog = priceCatalog;
		this.limitResolver = limitResolver;
		this.requestLimit = requestLimit;
		this.mode = mode;
		this.failureMode = failureMode;
		this.clock = clock;
		this.zone = zone;
		this.modelsWithoutUsage = ConcurrentHashMap.newKeySet();
		this.uncostedModels = ConcurrentHashMap.newKeySet();
		this.ledgerDown = new AtomicBoolean(false);
		this.thresholds = List.copyOf(new TreeSet<>(thresholds));
		this.listeners = List.copyOf(listeners);
	}

	/**
	 * Checks once per request whether finished calls have already used up a budget. Calls still in
	 * flight are not counted here; {@link #reserve} checks those.
	 * @return {@link Decision.Allowed}, or {@link Decision.Denied} in enforce mode
	 * @throws LedgerUnavailableException if the ledger can't be reached and the failure mode is fail-closed
	 * @throws IllegalArgumentException if the caller's limits have two of one scope and window
	 */
	public Decision admit(Caller caller) {
		Objects.requireNonNull(caller, "caller can't be null");
		Instant now = this.clock.instant();
		Map<BudgetKey, Limit> keys = keysFor(caller, now);

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
	 * @param endpointLimit caps the endpoint set for its requests; a cap it doesn't set comes from configuration
	 * @param callsSoFar model calls this request has already made
	 * @param costSoFar what those calls cost
	 * @return {@link Decision.Allowed}, or {@link Decision.Denied} in enforce mode, with no wait
	 *         because the next request starts with fresh counters
	 */
	public Decision checkRequest(RequestLimit endpointLimit, int callsSoFar, Money costSoFar) {
		Objects.requireNonNull(endpointLimit, "endpointLimit can't be null");
		Objects.requireNonNull(costSoFar, "costSoFar can't be null");
		RequestLimit limit = endpointLimit.orElse(this.requestLimit);
		@Nullable Integer maxModelCalls = limit.maxModelCalls();
		@Nullable Money maxCost = limit.maxCost();

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
	 * @param endpointLimit the caps the endpoint set for its requests, used to tell whether a cost cap applies
	 * @param model the model the call goes to, used to price the estimate
	 * @param estimate the expected usage of the call
	 * @return {@link Decision.Reserved}, or {@link Decision.Denied} in enforce mode
	 * @throws UnknownModelException if the model has no price, the unknown-model policy rejects it, and a
	 *         cost cap applies to the call; without one, its tokens are counted with no cost
	 * @throws LedgerUnavailableException if the ledger can't be reached and the failure mode is fail-closed
	 * @throws IllegalArgumentException if the caller's limits have two of one scope and window
	 */
	public Decision reserve(Caller caller, RequestLimit endpointLimit, String model, TokenUsage estimate) {
		Objects.requireNonNull(caller, "caller can't be null");
		Objects.requireNonNull(endpointLimit, "endpointLimit can't be null");
		Objects.requireNonNull(model, "model can't be null");
		Objects.requireNonNull(estimate, "estimate can't be null");
		Instant now = this.clock.instant();
		Map<BudgetKey, Limit> keys = keysFor(caller, now);
		boolean costCapped = hasCostCap(keys.values(), endpointLimit.orElse(this.requestLimit));
		Amount amount = new Amount(estimate.total(), costOf(model, estimate, costCapped));

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
	 * @param caller who made the call; thresholds are measured against their limits
	 * @param model the model the call went to, used to price the usage
	 * @return what was charged, so the caller can add it to the request's total
	 */
	public Amount settle(Caller caller, Reservation reservation, String model, TokenUsage actual) {
		Objects.requireNonNull(caller, "caller can't be null");
		Objects.requireNonNull(reservation, "reservation can't be null");
		Objects.requireNonNull(model, "model can't be null");
		Objects.requireNonNull(actual, "actual can't be null");

		Amount charged;
		if (actual.isZero()) {
			warnUsageMissingOnce(model);
			charged = reservation.amount();
		}
		else {
			// Reserve already accepted this model, so a missing price never fails the charge.
			charged = new Amount(actual.total(), costOf(model, actual, false));
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
		if (!committedAfter.isEmpty()) {
			notifyThresholdsReached(limitsOf(caller), committedAfter, charged);
		}
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

	/**
	 * Reads where a subject stands against each limit of a scope in the current window. Changes
	 * nothing and applies no failure mode. The limits are resolved for a caller that has only this
	 * one subject id, so a custom {@link LimitResolver} can't see the subject's other ids.
	 * @param subjectId ignored for {@link Scope#GLOBAL}, which has one shared budget
	 * @param plan the subject's plan, or null for the limits every caller has
	 * @return one status per limit of the scope, in the resolver's order; empty if the scope has none
	 * @throws LedgerUnavailableException if the ledger can't be reached
	 */
	public List<BudgetStatus> statusOf(Scope scope, String subjectId, @Nullable String plan) {
		Objects.requireNonNull(scope, "scope can't be null");
		Objects.requireNonNull(subjectId, "subjectId can't be null");
		Instant now = this.clock.instant();

		List<BudgetStatus> statuses = new ArrayList<>();
		for (Limit limit : limitsOf(new Caller(Map.of(scope, subjectId), plan))) {
			if (limit.scope() == scope) {
				BudgetKey key = BudgetKey.of(scope, subjectId, limit.window(), now, this.zone);
				statuses.add(new BudgetStatus(limit, key.windowStart(), limit.window().endOf(now, this.zone),
						this.ledger.current(key)));
			}
		}
		return statuses;
	}

	/**
	 * Gives a subject room back, for example after a bug burnt its budget: takes {@code amount} off
	 * its committed spend in the current window of every window length, never below zero. Calls in
	 * flight keep their reservations. Applies no failure mode.
	 * @param subjectId ignored for {@link Scope#GLOBAL}, which has one shared budget
	 * @throws LedgerUnavailableException if the ledger can't be reached
	 */
	public void topUp(Scope scope, String subjectId, Amount amount) {
		Objects.requireNonNull(amount, "amount can't be null");
		for (BudgetKey key : currentKeysOf(scope, subjectId)) {
			this.ledger.refund(key, amount);
		}
	}

	/**
	 * Clears a subject's committed spend in the current window of every window length. Calls in
	 * flight keep their reservations, and a call that settles while this runs stays charged.
	 * @param subjectId ignored for {@link Scope#GLOBAL}, which has one shared budget
	 * @throws LedgerUnavailableException if the ledger can't be reached
	 */
	public void reset(Scope scope, String subjectId) {
		for (BudgetKey key : currentKeysOf(scope, subjectId)) {
			this.ledger.refund(key, this.ledger.current(key).committed());
		}
	}

	private List<BudgetKey> currentKeysOf(Scope scope, String subjectId) {
		Objects.requireNonNull(scope, "scope can't be null");
		Objects.requireNonNull(subjectId, "subjectId can't be null");
		Instant now = this.clock.instant();
		List<BudgetKey> keys = new ArrayList<>();
		for (Window window : Window.values()) {
			keys.add(BudgetKey.of(scope, subjectId, window, now, this.zone));
		}
		return keys;
	}

	private List<Limit> limitsOf(Caller caller) {
		List<Limit> limits = this.limitResolver.limitsFor(caller);
		PlanLimits.requireOnePerScopeAndWindow(limits);
		return limits;
	}

	private Map<BudgetKey, Limit> keysFor(Caller caller, Instant now) {
		Map<BudgetKey, Limit> keys = new LinkedHashMap<>();
		for (Limit limit : limitsOf(caller)) {
			@Nullable String subjectId = (limit.scope() == Scope.GLOBAL) ? BudgetKey.GLOBAL_SUBJECT
					: caller.subjectIds().get(limit.scope());
			if (subjectId != null) {
				keys.put(BudgetKey.of(limit.scope(), subjectId, limit.window(), now, this.zone), limit);
			}
		}
		return keys;
	}

	/** Prices usage; a model without a price is only rejected when a cost cap needs its cost. */
	private Money costOf(String model, TokenUsage usage, boolean costCapped) {
		try {
			return this.priceCatalog.priceFor(model).map(price -> price.costOf(usage)).orElse(Money.ZERO);
		}
		catch (UnknownModelException ex) {
			if (costCapped) {
				throw ex;
			}
			warnUncostedOnce(model);
			return Money.ZERO;
		}
	}

	private static boolean hasCostCap(Collection<Limit> limits, RequestLimit requestLimit) {
		return requestLimit.maxCost() != null || limits.stream().anyMatch(limit -> limit.maxCost() != null);
	}

	private void warnUncostedOnce(String model) {
		if (this.uncostedModels.add(model)) {
			log.warn("Model '{}' has no price and no cost cap applies, so its tokens are counted without cost", model);
		}
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

	private static @Nullable Limit limitFor(List<Limit> limits, BudgetKey key) {
		for (Limit limit : limits) {
			if (limit.appliesTo(key)) {
				return limit;
			}
		}
		return null;
	}

	/** Tells listeners about each threshold this charge crossed; commits on one key are serialised, so only one call crosses it. */
	private void notifyThresholdsReached(List<Limit> limits, Map<BudgetKey, Amount> committedAfter, Amount charged) {
		for (Map.Entry<BudgetKey, Amount> entry : committedAfter.entrySet()) {
			BudgetKey key = entry.getKey();
			Amount after = entry.getValue();
			@Nullable Limit limit = limitFor(limits, key);
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
