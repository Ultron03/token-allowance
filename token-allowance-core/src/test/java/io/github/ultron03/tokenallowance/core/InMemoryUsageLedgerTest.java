package io.github.ultron03.tokenallowance.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

class InMemoryUsageLedgerTest {

	private static final Instant START = Instant.parse("2026-10-22T10:00:00Z");

	private static final BudgetKey USER_KEY = BudgetKey.of(Scope.USER, "alice", Window.DAILY, START, ZoneOffset.UTC);

	private static final BudgetKey GLOBAL_KEY = BudgetKey.of(Scope.GLOBAL, BudgetKey.GLOBAL_SUBJECT, Window.MONTHLY,
			START, ZoneOffset.UTC);

	private static final Limit USER_LIMIT = new Limit(Scope.USER, Window.DAILY, 100L, Money.ofDollars("1.00"));

	private static final Limit GLOBAL_LIMIT = new Limit(Scope.GLOBAL, Window.MONTHLY, 1000L, null);

	private static final int THREADS = 50;

	private static final int RACES = 200;

	private final MutableClock clock = new MutableClock(START);

	private final InMemoryUsageLedger ledger = new InMemoryUsageLedger(this.clock, ZoneOffset.UTC);

	@Test
	void reservesEstimateWhenUnderLimit() {
		ReservationResult result = this.ledger.reserve(Map.of(USER_KEY, USER_LIMIT), amount(40, "0.40"));

		assertThat(result).isInstanceOf(ReservationResult.Reserved.class);
		assertThat(this.ledger.current(USER_KEY)).isEqualTo(new Spend(Amount.ZERO, amount(40, "0.40")));
	}

	@Test
	void allowsReservationThatReachesLimitExactly() {
		ReservationResult result = this.ledger.reserve(Map.of(USER_KEY, USER_LIMIT), amount(100, "1.00"));

		assertThat(result).isInstanceOf(ReservationResult.Reserved.class);
	}

	@Test
	void deniesReservationThatWouldExceedTokenLimit() {
		this.ledger.reserve(Map.of(USER_KEY, USER_LIMIT), amount(60, "0.10"));

		ReservationResult result = this.ledger.reserve(Map.of(USER_KEY, USER_LIMIT), amount(50, "0.10"));

		assertThat(result).isEqualTo(new ReservationResult.Exceeded(USER_KEY, USER_LIMIT));
		assertThat(this.ledger.current(USER_KEY).reserved()).isEqualTo(amount(60, "0.10"));
	}

	@Test
	void deniesReservationThatWouldExceedCostLimit() {
		ReservationResult result = this.ledger.reserve(Map.of(USER_KEY, USER_LIMIT), amount(10, "1.000001"));

		assertThat(result).isEqualTo(new ReservationResult.Exceeded(USER_KEY, USER_LIMIT));
	}

	@Test
	void reservesOnEveryKey() {
		this.ledger.reserve(Map.of(USER_KEY, USER_LIMIT, GLOBAL_KEY, GLOBAL_LIMIT), amount(40, "0.40"));

		assertThat(this.ledger.current(USER_KEY).reserved()).isEqualTo(amount(40, "0.40"));
		assertThat(this.ledger.current(GLOBAL_KEY).reserved()).isEqualTo(amount(40, "0.40"));
	}

	@Test
	void deniedReservationReservesNothingOnAnyKey() {
		ReservationResult result = this.ledger.reserve(Map.of(USER_KEY, USER_LIMIT, GLOBAL_KEY, GLOBAL_LIMIT),
				amount(101, "0.10"));

		assertThat(result).isEqualTo(new ReservationResult.Exceeded(USER_KEY, USER_LIMIT));
		assertThat(this.ledger.current(GLOBAL_KEY)).isEqualTo(Spend.ZERO);
	}

	@Test
	void commitReplacesReservationWithActualAmount() {
		Reservation reservation = reserve(amount(50, "0.50"));

		this.ledger.commit(reservation, amount(30, "0.30"));

		assertThat(this.ledger.current(USER_KEY)).isEqualTo(new Spend(amount(30, "0.30"), Amount.ZERO));
	}

	@Test
	void commitIsNeverDeniedEvenWhenActualExceedsLimit() {
		Reservation reservation = reserve(amount(50, "0.50"));

		this.ledger.commit(reservation, amount(150, "1.50"));

		assertThat(this.ledger.current(USER_KEY).committed()).isEqualTo(amount(150, "1.50"));
	}

	@Test
	void committedSpendCountsAgainstLaterReservations() {
		this.ledger.commit(reserve(amount(50, "0.50")), amount(80, "0.80"));

		ReservationResult result = this.ledger.reserve(Map.of(USER_KEY, USER_LIMIT), amount(30, "0.10"));

		assertThat(result).isEqualTo(new ReservationResult.Exceeded(USER_KEY, USER_LIMIT));
	}

	@Test
	void releaseDropsReservationWithoutCharging() {
		Reservation reservation = reserve(amount(50, "0.50"));

		this.ledger.release(reservation);

		assertThat(this.ledger.current(USER_KEY)).isEqualTo(Spend.ZERO);
	}

	@Test
	void settlingTwiceChargesOnlyOnce() {
		Reservation reservation = reserve(amount(50, "0.50"));

		this.ledger.commit(reservation, amount(30, "0.30"));
		this.ledger.commit(reservation, amount(30, "0.30"));
		this.ledger.release(reservation);

		assertThat(this.ledger.current(USER_KEY)).isEqualTo(new Spend(amount(30, "0.30"), Amount.ZERO));
	}

	@Test
	void newWindowStartsWithNoSpend() {
		this.ledger.commit(reserve(amount(100, "1.00")), amount(100, "1.00"));
		this.clock.advance(Duration.ofDays(1));
		BudgetKey tomorrow = BudgetKey.of(Scope.USER, "alice", Window.DAILY, this.clock.instant(), ZoneOffset.UTC);

		ReservationResult result = this.ledger.reserve(Map.of(tomorrow, USER_LIMIT), amount(100, "1.00"));

		assertThat(result).isInstanceOf(ReservationResult.Reserved.class);
	}

	@Test
	void evictsEndedWindowsOnceADay() {
		this.ledger.commit(reserve(amount(30, "0.30")), amount(30, "0.30"));
		this.clock.advance(Duration.ofDays(1));

		reserveTomorrow();

		assertThat(this.ledger.current(USER_KEY)).isEqualTo(Spend.ZERO);
	}

	@Test
	void keepsWindowThatHasNotEndedWhenEvicting() {
		Reservation reservation = reserveOn(Map.of(GLOBAL_KEY, GLOBAL_LIMIT), amount(30, "0.30"));
		this.ledger.commit(reservation, amount(30, "0.30"));
		this.clock.advance(Duration.ofDays(1));

		reserveTomorrow();

		assertThat(this.ledger.current(GLOBAL_KEY).committed()).isEqualTo(amount(30, "0.30"));
	}

	@Test
	void keepsEndedWindowWhileReservationIsOpen() {
		Reservation reservation = reserve(amount(40, "0.40"));
		this.clock.advance(Duration.ofDays(1));
		reserveTomorrow();

		this.ledger.commit(reservation, amount(30, "0.30"));

		assertThat(this.ledger.current(USER_KEY)).isEqualTo(new Spend(amount(30, "0.30"), Amount.ZERO));
	}

	@Test
	void uncheckedReservationGoesOverLimit() {
		reserve(amount(100, "1.00"));

		Reservation reservation = this.ledger.reserveUnchecked(Set.of(USER_KEY), amount(50, "0.50"));
		this.ledger.commit(reservation, amount(50, "0.50"));

		assertThat(this.ledger.current(USER_KEY))
				.isEqualTo(new Spend(amount(50, "0.50"), amount(100, "1.00")));
	}

	@Test
	void overflowingCommitLeavesLedgerUnchanged() {
		this.ledger.commit(reserve(amount(30, "0.30")), amount(30, "0.30"));
		Reservation reservation = reserve(amount(40, "0.40"));

		assertThatThrownBy(() -> this.ledger.commit(reservation, new Amount(Long.MAX_VALUE, Money.ZERO)))
				.isInstanceOf(ArithmeticException.class);

		assertThat(this.ledger.current(USER_KEY)).isEqualTo(new Spend(amount(30, "0.30"), amount(40, "0.40")));
		this.ledger.release(reservation);
		assertThat(this.ledger.current(USER_KEY)).isEqualTo(new Spend(amount(30, "0.30"), Amount.ZERO));
	}

	@Test
	void racingCommitAndReleaseSettleEachReservationOnce() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (int i = 0; i < RACES; i++) {
				Reservation reservation = this.ledger.reserveUnchecked(Set.of(USER_KEY), amount(10, "0.10"));
				CountDownLatch start = new CountDownLatch(1);
				Future<?> commit = pool.submit(() -> {
					start.await();
					this.ledger.commit(reservation, amount(10, "0.10"));
					return null;
				});
				Future<?> release = pool.submit(() -> {
					start.await();
					this.ledger.release(reservation);
					return null;
				});
				start.countDown();
				commit.get();
				release.get();
			}
		}
		finally {
			pool.shutdownNow();
		}

		Spend spend = this.ledger.current(USER_KEY);
		assertThat(spend.reserved()).isEqualTo(Amount.ZERO);
		assertThat(spend.committed().tokens()).isLessThanOrEqualTo(RACES * 10L);
	}

	@Test
	void rejectsLimitThatDoesNotMatchKey() {
		assertThatThrownBy(() -> this.ledger.reserve(Map.of(USER_KEY, GLOBAL_LIMIT), amount(10, "0.10")))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void parallelReservationsNeverExceedLimit() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<ReservationResult>> results = new ArrayList<>();
		try {
			for (int i = 0; i < THREADS; i++) {
				results.add(pool.submit(() -> {
					start.await();
					return this.ledger.reserve(Map.of(USER_KEY, USER_LIMIT), amount(10, "0.01"));
				}));
			}
			start.countDown();

			int reserved = 0;
			for (Future<ReservationResult> result : results) {
				if (result.get() instanceof ReservationResult.Reserved) {
					reserved++;
				}
			}

			assertThat(reserved).isEqualTo(10);
			assertThat(this.ledger.current(USER_KEY).reserved().tokens()).isEqualTo(100);
		}
		finally {
			pool.shutdownNow();
		}
	}

	private Reservation reserve(Amount estimate) {
		return reserveOn(Map.of(USER_KEY, USER_LIMIT), estimate);
	}

	private void reserveTomorrow() {
		BudgetKey tomorrow = BudgetKey.of(Scope.USER, "alice", Window.DAILY, this.clock.instant(), ZoneOffset.UTC);
		reserveOn(Map.of(tomorrow, USER_LIMIT), amount(1, "0.01"));
	}

	private Reservation reserveOn(Map<BudgetKey, Limit> limits, Amount estimate) {
		ReservationResult result = this.ledger.reserve(limits, estimate);
		assertThat(result).isInstanceOf(ReservationResult.Reserved.class);
		return ((ReservationResult.Reserved) result).reservation();
	}

	private static Amount amount(long tokens, String dollars) {
		return new Amount(tokens, Money.ofDollars(dollars));
	}
}
