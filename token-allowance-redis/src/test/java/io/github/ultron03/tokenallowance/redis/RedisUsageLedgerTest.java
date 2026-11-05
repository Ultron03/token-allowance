package io.github.ultron03.tokenallowance.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

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

	private static Limit userTokens(long maxTokens) {
		return new Limit(Scope.USER, Window.DAILY, maxTokens, null);
	}

	private static Limit globalTokens(long maxTokens) {
		return new Limit(Scope.GLOBAL, Window.MONTHLY, maxTokens, null);
	}
}
