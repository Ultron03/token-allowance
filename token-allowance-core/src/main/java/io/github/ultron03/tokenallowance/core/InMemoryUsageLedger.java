package io.github.ultron03.tokenallowance.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps spend in memory, for a single application instance. Spend is lost on restart.
 *
 * <p>One lock guards all counters, so a reservation across several keys is atomic. A new window
 * gets a new {@link BudgetKey}, so it starts empty; counters of ended windows are dropped once a
 * day, unless a reservation on them is still open.
 */
public class InMemoryUsageLedger implements UsageLedger {

	private static final Duration EVICTION_INTERVAL = Duration.ofDays(1);

	private static final Logger log = LoggerFactory.getLogger(InMemoryUsageLedger.class);

	private final Clock clock;

	private final ZoneId zone;

	private final Object lock = new Object();

	private final Map<BudgetKey, Spend> spendByKey = new HashMap<>();

	private final Map<String, Reservation> openReservations = new HashMap<>();

	private Instant nextEviction;

	/**
	 * Creates an empty ledger.
	 * @param zone the zone budget keys are built in, used to find when a window ends
	 */
	public InMemoryUsageLedger(Clock clock, ZoneId zone) {
		this.clock = Objects.requireNonNull(clock, "clock can't be null");
		this.zone = Objects.requireNonNull(zone, "zone can't be null");
		this.nextEviction = clock.instant().plus(EVICTION_INTERVAL);
	}

	@Override
	public ReservationResult reserve(Map<BudgetKey, Limit> limits, Amount estimate) {
		Objects.requireNonNull(limits, "limits can't be null");
		Objects.requireNonNull(estimate, "estimate can't be null");
		requireMatchingLimits(limits);

		synchronized (this.lock) {
			evictEndedWindowsIfDue();
			for (Map.Entry<BudgetKey, Limit> entry : limits.entrySet()) {
				Amount afterReserve = spendOf(entry.getKey()).total().plus(estimate);
				if (entry.getValue().isExceededBy(afterReserve)) {
					return new ReservationResult.Exceeded(entry.getKey(), entry.getValue());
				}
			}

			return new ReservationResult.Reserved(hold(limits.keySet(), estimate));
		}
	}

	@Override
	public Reservation reserveUnchecked(Set<BudgetKey> keys, Amount estimate) {
		Objects.requireNonNull(keys, "keys can't be null");
		Objects.requireNonNull(estimate, "estimate can't be null");
		synchronized (this.lock) {
			evictEndedWindowsIfDue();
			return hold(keys, estimate);
		}
	}

	@Override
	public void commit(Reservation reservation, Amount actual) {
		Objects.requireNonNull(actual, "actual can't be null");
		settle(reservation, actual);
	}

	@Override
	public void release(Reservation reservation) {
		settle(reservation, Amount.ZERO);
	}

	@Override
	public Spend current(BudgetKey key) {
		Objects.requireNonNull(key, "key can't be null");
		synchronized (this.lock) {
			return spendOf(key);
		}
	}

	private static void requireMatchingLimits(Map<BudgetKey, Limit> limits) {
		for (Map.Entry<BudgetKey, Limit> entry : limits.entrySet()) {
			if (!entry.getValue().appliesTo(entry.getKey())) {
				throw new IllegalArgumentException("Limit " + entry.getValue() + " doesn't match the scope and window of "
						+ entry.getKey().scope() + "/" + entry.getKey().window());
			}
		}
	}

	private Reservation hold(Set<BudgetKey> keys, Amount estimate) {
		Reservation reservation = new Reservation(UUID.randomUUID().toString(), keys, estimate);
		for (BudgetKey key : reservation.keys()) {
			this.spendByKey.put(key, spendOf(key).plusReserved(estimate));
		}
		this.openReservations.put(reservation.id(), reservation);
		return reservation;
	}

	private void settle(Reservation reservation, Amount charged) {
		Objects.requireNonNull(reservation, "reservation can't be null");
		synchronized (this.lock) {
			@Nullable Reservation open = this.openReservations.get(reservation.id());
			if (open == null) {
				return;
			}

			// Compute every counter first, so an overflow leaves the ledger unchanged.
			Map<BudgetKey, Spend> settled = new HashMap<>();
			for (BudgetKey key : open.keys()) {
				settled.put(key, spendOf(key).minusReserved(open.amount()).plusCommitted(charged));
			}
			this.spendByKey.putAll(settled);
			this.openReservations.remove(open.id());
		}
	}

	private Spend spendOf(BudgetKey key) {
		return this.spendByKey.getOrDefault(key, Spend.ZERO);
	}

	private void evictEndedWindowsIfDue() {
		Instant now = this.clock.instant();
		if (now.isBefore(this.nextEviction)) {
			return;
		}

		Set<BudgetKey> inUse = new HashSet<>();
		for (Reservation open : this.openReservations.values()) {
			inUse.addAll(open.keys());
		}
		int before = this.spendByKey.size();
		this.spendByKey.keySet().removeIf(key -> !inUse.contains(key) && hasEnded(key, now));
		this.nextEviction = now.plus(EVICTION_INTERVAL);
		log.debug("Evicted {} budget counters of ended windows", before - this.spendByKey.size());
	}

	private boolean hasEnded(BudgetKey key, Instant now) {
		return !now.isBefore(key.window().endOf(key.windowStart(), this.zone));
	}
}
