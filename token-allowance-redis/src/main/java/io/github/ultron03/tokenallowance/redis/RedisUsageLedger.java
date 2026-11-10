package io.github.ultron03.tokenallowance.redis;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import io.github.ultron03.tokenallowance.core.Amount;
import io.github.ultron03.tokenallowance.core.BudgetKey;
import io.github.ultron03.tokenallowance.core.LedgerUnavailableException;
import io.github.ultron03.tokenallowance.core.Limit;
import io.github.ultron03.tokenallowance.core.Money;
import io.github.ultron03.tokenallowance.core.Reservation;
import io.github.ultron03.tokenallowance.core.ReservationResult;
import io.github.ultron03.tokenallowance.core.Spend;
import io.github.ultron03.tokenallowance.core.UsageLedger;

/**
 * Usage ledger kept in Redis, so every instance of an app shares the same budgets. Each change runs
 * as one Lua script, which Redis executes without any other command in between.
 *
 * <p>Each budget keeps its open reservations in a sorted set by timeout, so reserving drops the
 * ones a crashed instance never settled.
 *
 * <p>A refused connection or a command timeout becomes {@link LedgerUnavailableException}; other
 * Redis errors, such as a failing script, are bugs and pass through unchanged.
 */
public final class RedisUsageLedger implements UsageLedger {

	private static final Logger log = LoggerFactory.getLogger(RedisUsageLedger.class);

	@SuppressWarnings("rawtypes")
	private static final RedisScript<List> SETTLE = RedisScript
		.of(new ClassPathResource("settle.lua", RedisUsageLedger.class), List.class);

	private static final RedisScript<Long> RESERVE = RedisScript
		.of(new ClassPathResource("reserve.lua", RedisUsageLedger.class), Long.class);

	private static final RedisScript<Long> REFUND = RedisScript
		.of(new ClassPathResource("refund.lua", RedisUsageLedger.class), Long.class);

	private static final long RESERVED = 0;

	private static final String NO_CAP = "-1";

	private static final String UNAVAILABLE = "Redis is unavailable";

	private final StringRedisTemplate redis;

	private final Clock clock;

	private final ZoneId zone;

	/** Creates a ledger on the given connection that times reservations out by the system clock. */
	public RedisUsageLedger(StringRedisTemplate redis, ZoneId zone) {
		this(redis, Clock.systemUTC(), zone);
	}

	/**
	 * Creates a ledger on the given connection.
	 * @param clock tells when a reservation has timed out; instances sharing a Redis need clocks that agree
	 * @param zone decides when daily and monthly windows end
	 */
	public RedisUsageLedger(StringRedisTemplate redis, Clock clock, ZoneId zone) {
		this.redis = Objects.requireNonNull(redis, "redis can't be null");
		this.clock = Objects.requireNonNull(clock, "clock can't be null");
		this.zone = Objects.requireNonNull(zone, "zone can't be null");
	}

	@Override
	public ReservationResult reserve(Map<BudgetKey, Limit> limits, Amount estimate) {
		Objects.requireNonNull(limits, "limits can't be null");
		Objects.requireNonNull(estimate, "estimate can't be null");
		requireMatchingLimits(limits);

		// The script answers with a position in this list, so it must keep one order.
		List<BudgetKey> keys = List.copyOf(limits.keySet());
		String id = UUID.randomUUID().toString();
		long position = runReserve(id, keys, limits, estimate);
		if (position == RESERVED) {
			return new ReservationResult.Reserved(new Reservation(id, limits.keySet(), estimate));
		}

		BudgetKey exceeded = keys.get((int) position - 1);
		return new ReservationResult.Exceeded(exceeded, limits.get(exceeded));
	}

	@Override
	public Reservation reserveUnchecked(Set<BudgetKey> keys, Amount estimate) {
		Objects.requireNonNull(keys, "keys can't be null");
		Objects.requireNonNull(estimate, "estimate can't be null");
		String id = UUID.randomUUID().toString();
		runReserve(id, List.copyOf(keys), Map.of(), estimate);
		return new Reservation(id, keys, estimate);
	}

	@Override
	public Map<BudgetKey, Amount> commit(Reservation reservation, Amount actual) {
		Objects.requireNonNull(actual, "actual can't be null");
		return settle(reservation, actual);
	}

	@Override
	public void release(Reservation reservation) {
		settle(reservation, Amount.ZERO);
	}

	@Override
	public Spend current(BudgetKey key) {
		Objects.requireNonNull(key, "key can't be null");
		List<String> values;
		try {
			values = this.redis.<String, String>opsForHash()
				.multiGet(RedisKeys.budget(key), List.of(RedisKeys.COMMITTED_TOKENS, RedisKeys.COMMITTED_MICROS,
						RedisKeys.RESERVED_TOKENS, RedisKeys.RESERVED_MICROS));
		}
		catch (DataAccessResourceFailureException | QueryTimeoutException ex) {
			throw new LedgerUnavailableException(UNAVAILABLE, ex);
		}
		return new Spend(amount(values.get(0), values.get(1)), amount(values.get(2), values.get(3)));
	}

	@Override
	public void refund(BudgetKey key, Amount amount) {
		Objects.requireNonNull(key, "key can't be null");
		Objects.requireNonNull(amount, "amount can't be null");
		try {
			this.redis.execute(REFUND, List.of(RedisKeys.budget(key)), String.valueOf(amount.tokens()),
					String.valueOf(amount.cost().micros()));
		}
		catch (DataAccessResourceFailureException | QueryTimeoutException ex) {
			throw new LedgerUnavailableException(UNAVAILABLE, ex);
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

	/** Runs the reserve script; a key without a limit gets no cap. Returns 0 or the position of the key without room. */
	private long runReserve(String id, List<BudgetKey> keys, Map<BudgetKey, Limit> limits, Amount estimate) {
		Instant now = this.clock.instant();
		List<String> args = new ArrayList<>();
		args.add(String.valueOf(estimate.tokens()));
		args.add(String.valueOf(estimate.cost().micros()));
		args.add(String.valueOf(now.getEpochSecond()));
		args.add(String.valueOf(now.plus(RESERVATION_TIMEOUT).getEpochSecond()));
		args.add(id);

		for (BudgetKey key : keys) {
			Limit limit = limits.get(key);
			args.add(maxTokensOf(limit));
			args.add(maxMicrosOf(limit));
			args.add(expiryOf(key));
		}

		try {
			// execute only returns null inside a pipeline or transaction, which this class never opens.
			return Objects.requireNonNull(this.redis.execute(RESERVE, scriptKeys(keys), args.toArray()),
					"reserve script returned no result");
		}
		catch (DataAccessResourceFailureException | QueryTimeoutException ex) {
			throw new LedgerUnavailableException(UNAVAILABLE, ex);
		}
	}

	private static String maxTokensOf(@Nullable Limit limit) {
		return limit == null || limit.maxTokens() == null ? NO_CAP : String.valueOf(limit.maxTokens());
	}

	private static String maxMicrosOf(@Nullable Limit limit) {
		Money maxCost = limit == null ? null : limit.maxCost();
		return maxCost == null ? NO_CAP : String.valueOf(maxCost.micros());
	}

	private Map<BudgetKey, Amount> settle(Reservation reservation, Amount charged) {
		Objects.requireNonNull(reservation, "reservation can't be null");

		// Iterated more than once below, so every loop must see the keys in one fixed order.
		List<BudgetKey> budgetKeys = new ArrayList<>(reservation.keys());
		Amount estimate = reservation.amount();

		List<String> args = new ArrayList<>();
		args.add(reservation.id() + ":" + estimate.tokens() + ":" + estimate.cost().micros());
		args.add(String.valueOf(-estimate.tokens()));
		args.add(String.valueOf(-estimate.cost().micros()));
		args.add(String.valueOf(charged.tokens()));
		args.add(String.valueOf(charged.cost().micros()));
		for (BudgetKey key : budgetKeys) {
			args.add(expiryOf(key));
		}

		@Nullable List<?> committed;
		try {
			committed = this.redis.execute(SETTLE, scriptKeys(budgetKeys), args.toArray());
		}
		catch (DataAccessResourceFailureException | QueryTimeoutException ex) {
			throw new LedgerUnavailableException(UNAVAILABLE, ex);
		}
		if (committed == null || committed.isEmpty()) {
			log.debug("Reservation {} was already settled or has timed out", reservation.id());
			return Map.of();
		}

		// The script returns tokens and micros per key, in the order of budgetKeys.
		Map<BudgetKey, Amount> committedByKey = new HashMap<>();
		for (int i = 0; i < budgetKeys.size(); i++) {
			long tokens = (Long) committed.get(2 * i);
			long micros = (Long) committed.get(2 * i + 1);
			committedByKey.put(budgetKeys.get(i), new Amount(tokens, new Money(micros)));
		}
		return Map.copyOf(committedByKey);
	}

	/** {@return the budget keys, then their sets of open reservations in the same order, as the scripts expect} */
	private static List<String> scriptKeys(List<BudgetKey> budgetKeys) {
		List<String> keys = new ArrayList<>();
		for (BudgetKey key : budgetKeys) {
			keys.add(RedisKeys.budget(key));
		}
		for (BudgetKey key : budgetKeys) {
			keys.add(RedisKeys.reservations(key));
		}
		return keys;
	}

	private String expiryOf(BudgetKey key) {
		return String.valueOf(RedisKeys.expiresAt(key, this.zone).getEpochSecond());
	}

	private static Amount amount(@Nullable String tokens, @Nullable String micros) {
		return new Amount(toLong(tokens), new Money(toLong(micros)));
	}

	private static long toLong(@Nullable String value) {
		return value == null ? 0 : Long.parseLong(value);
	}
}
