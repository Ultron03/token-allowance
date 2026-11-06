package io.github.ultron03.tokenallowance.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.ultron03.tokenallowance.core.Amount;
import io.github.ultron03.tokenallowance.core.BudgetKey;
import io.github.ultron03.tokenallowance.core.Limit;
import io.github.ultron03.tokenallowance.core.Money;
import io.github.ultron03.tokenallowance.core.Reservation;
import io.github.ultron03.tokenallowance.core.ReservationResult;
import io.github.ultron03.tokenallowance.core.Scope;
import io.github.ultron03.tokenallowance.core.Spend;
import io.github.ultron03.tokenallowance.core.Window;

class RedisUsageLedgerTest extends RedisTestSupport {

	private static final ZoneId UTC = ZoneOffset.UTC;

	private static final Amount ESTIMATE = new Amount(1000, new Money(500));

	private static final Amount ACTUAL = new Amount(300, new Money(150));

	private static final int THREADS = 50;

	private static final int RACES = 100;

	private static final int GLOBAL_ROOM = 10;

	private final BudgetKey alice = BudgetKey.of(Scope.USER, "alice", Window.DAILY, Instant.now(), UTC);

	private final BudgetKey global = BudgetKey.of(Scope.GLOBAL, "global", Window.MONTHLY, Instant.now(), UTC);

	private RedisUsageLedger ledger;

	@BeforeEach
	void createLedger() {
		this.ledger = new RedisUsageLedger(redisTemplate, UTC);
	}

	@Test
	void commitReplacesEstimateWithActual() {
		Reservation reservation = this.ledger.reserveUnchecked(Set.of(this.alice), ESTIMATE);

		this.ledger.commit(reservation, ACTUAL);

		assertThat(this.ledger.current(this.alice)).isEqualTo(new Spend(ACTUAL, Amount.ZERO));
	}

	@Test
	void releaseRemovesEstimateWithoutCharging() {
		Reservation reservation = this.ledger.reserveUnchecked(Set.of(this.alice), ESTIMATE);

		this.ledger.release(reservation);

		assertThat(this.ledger.current(this.alice)).isEqualTo(Spend.ZERO);
	}

	@Test
	void settlesEveryKeyOfReservation() {
		Reservation reservation = this.ledger.reserveUnchecked(Set.of(this.alice, this.global), ESTIMATE);

		this.ledger.commit(reservation, ACTUAL);

		assertThat(this.ledger.current(this.alice)).isEqualTo(new Spend(ACTUAL, Amount.ZERO));
		assertThat(this.ledger.current(this.global)).isEqualTo(new Spend(ACTUAL, Amount.ZERO));
	}

	@Test
	void leavesOtherReservationsReserved() {
		Reservation first = this.ledger.reserveUnchecked(Set.of(this.alice), ESTIMATE);
		this.ledger.reserveUnchecked(Set.of(this.alice), ESTIMATE);

		this.ledger.commit(first, ACTUAL);

		assertThat(this.ledger.current(this.alice)).isEqualTo(new Spend(ACTUAL, ESTIMATE));
	}

	@Test
	void settlingTwiceChangesNothing() {
		Reservation reservation = this.ledger.reserveUnchecked(Set.of(this.alice), ESTIMATE);

		this.ledger.commit(reservation, ACTUAL);
		this.ledger.release(reservation);

		assertThat(this.ledger.current(this.alice)).isEqualTo(new Spend(ACTUAL, Amount.ZERO));
	}

	@Test
	void ignoresUnknownReservation() {
		Reservation unknown = new Reservation("never-reserved", Set.of(this.alice), ESTIMATE);

		this.ledger.commit(unknown, ACTUAL);

		assertThat(this.ledger.current(this.alice)).isEqualTo(Spend.ZERO);
		assertThat(redisTemplate.hasKey(RedisKeys.budget(this.alice))).isFalse();
	}

	@Test
	void currentIsZeroForUnknownKey() {
		assertThat(this.ledger.current(this.alice)).isEqualTo(Spend.ZERO);
	}

	@Test
	void keepsBudgetKeyUntilOneDayAfterWindowEnd() {
		Reservation reservation = this.ledger.reserveUnchecked(Set.of(this.alice), ESTIMATE);

		this.ledger.commit(reservation, ACTUAL);

		assertThat(redisTemplate.getExpire(RedisKeys.budget(this.alice))).isBetween(Duration.ofDays(1).toSeconds(),
				Duration.ofDays(2).toSeconds());
	}

	@Test
	void reservesEstimateOnEveryKey() {
		ReservationResult result = this.ledger.reserve(Map.of(this.alice, userTokens(10_000), this.global,
				globalTokens(10_000)), ESTIMATE);

		assertThat(result).isInstanceOf(ReservationResult.Reserved.class);
		assertThat(this.ledger.current(this.alice)).isEqualTo(new Spend(Amount.ZERO, ESTIMATE));
		assertThat(this.ledger.current(this.global)).isEqualTo(new Spend(Amount.ZERO, ESTIMATE));
	}

	@Test
	void deniesWithoutTouchingAnyKeyWhenOneWouldExceed() {
		Limit globalLimit = globalTokens(999);
		Map<BudgetKey, Limit> limits = new LinkedHashMap<>();
		limits.put(this.alice, userTokens(10_000));
		limits.put(this.global, globalLimit);

		ReservationResult result = this.ledger.reserve(limits, ESTIMATE);

		assertThat(result).isEqualTo(new ReservationResult.Exceeded(this.global, globalLimit));
		assertThat(redisTemplate.keys(RedisKeys.PREFIX + "*")).isEmpty();
	}

	@Test
	void allowsReachingCapExactly() {
		ReservationResult result = this.ledger.reserve(Map.of(this.alice, userTokens(ESTIMATE.tokens())), ESTIMATE);

		assertThat(result).isInstanceOf(ReservationResult.Reserved.class);
	}

	@Test
	void countsOpenReservationsAgainstCap() {
		Map<BudgetKey, Limit> limits = Map.of(this.alice, userTokens(1500));
		this.ledger.reserve(limits, ESTIMATE);

		assertThat(this.ledger.reserve(limits, ESTIMATE)).isInstanceOf(ReservationResult.Exceeded.class);
	}

	@Test
	void countsCommittedSpendAgainstCap() {
		this.ledger.commit(this.ledger.reserveUnchecked(Set.of(this.alice), ESTIMATE), ACTUAL);

		ReservationResult result = this.ledger.reserve(Map.of(this.alice, userTokens(1200)), ESTIMATE);

		assertThat(result).isInstanceOf(ReservationResult.Exceeded.class);
	}

	@Test
	void deniesWhenCostCapWouldBeExceeded() {
		Limit costLimit = new Limit(Scope.USER, Window.DAILY, null, new Money(499));

		ReservationResult result = this.ledger.reserve(Map.of(this.alice, costLimit), ESTIMATE);

		assertThat(result).isEqualTo(new ReservationResult.Exceeded(this.alice, costLimit));
	}

	@Test
	void ignoresTokensWhenOnlyCostIsCapped() {
		Limit costLimit = new Limit(Scope.USER, Window.DAILY, null, new Money(10_000));

		ReservationResult result = this.ledger.reserve(Map.of(this.alice, costLimit), ESTIMATE);

		assertThat(result).isInstanceOf(ReservationResult.Reserved.class);
	}

	@Test
	void reserveUncheckedHoldsEstimate() {
		this.ledger.reserveUnchecked(Set.of(this.alice), ESTIMATE);

		assertThat(this.ledger.current(this.alice)).isEqualTo(new Spend(Amount.ZERO, ESTIMATE));
	}

	@Test
	void setsExpiryOnBudgetAndReservationKeys() {
		Reservation reservation = this.ledger.reserveUnchecked(Set.of(this.alice), ESTIMATE);

		assertThat(redisTemplate.getExpire(RedisKeys.budget(this.alice))).isBetween(Duration.ofDays(1).toSeconds(),
				Duration.ofDays(2).toSeconds());
		assertThat(redisTemplate.getExpire(RedisKeys.reservation(reservation.id())))
			.isBetween(RedisKeys.RESERVATION_TTL.toSeconds() - 60, RedisKeys.RESERVATION_TTL.toSeconds());
	}

	@Test
	void rejectsLimitThatDoesNotMatchItsKey() {
		Map<BudgetKey, Limit> limits = Map.of(this.alice, globalTokens(10_000));

		assertThatThrownBy(() -> this.ledger.reserve(limits, ESTIMATE)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void parallelReservationsNeverExceedSharedLimit() throws Exception {
		Limit globalLimit = globalTokens(GLOBAL_ROOM * ESTIMATE.tokens());
		List<BudgetKey> users = new ArrayList<>();
		for (int i = 0; i < THREADS; i++) {
			users.add(BudgetKey.of(Scope.USER, "user-" + i, Window.DAILY, Instant.now(), UTC));
		}

		ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<ReservationResult>> results = new ArrayList<>();
		try {
			for (BudgetKey user : users) {
				results.add(pool.submit(() -> {
					start.await();
					return this.ledger.reserve(Map.of(user, userTokens(10_000), this.global, globalLimit), ESTIMATE);
				}));
			}
			start.countDown();

			int reserved = 0;
			for (Future<ReservationResult> result : results) {
				if (result.get() instanceof ReservationResult.Reserved) {
					reserved++;
				}
			}
			assertThat(reserved).isEqualTo(GLOBAL_ROOM);
		}
		finally {
			pool.shutdownNow();
		}

		long reservedOnUsers = users.stream().mapToLong(user -> this.ledger.current(user).reserved().tokens()).sum();
		assertThat(this.ledger.current(this.global).reserved().tokens()).isEqualTo(GLOBAL_ROOM * ESTIMATE.tokens());
		assertThat(reservedOnUsers).isEqualTo(GLOBAL_ROOM * ESTIMATE.tokens());
	}

	@Test
	void racingCommitAndReleaseSettleEachReservationOnce() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (int i = 0; i < RACES; i++) {
				Reservation reservation = this.ledger.reserveUnchecked(Set.of(this.alice), ESTIMATE);
				CountDownLatch start = new CountDownLatch(1);
				Future<?> commit = pool.submit(() -> {
					start.await();
					this.ledger.commit(reservation, ACTUAL);
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

		Spend spend = this.ledger.current(this.alice);
		assertThat(spend.reserved()).isEqualTo(Amount.ZERO);
		assertThat(spend.committed().tokens()).isLessThanOrEqualTo(RACES * ACTUAL.tokens());
	}

	private static Limit userTokens(long maxTokens) {
		return new Limit(Scope.USER, Window.DAILY, maxTokens, null);
	}

	private static Limit globalTokens(long maxTokens) {
		return new Limit(Scope.GLOBAL, Window.MONTHLY, maxTokens, null);
	}
}
