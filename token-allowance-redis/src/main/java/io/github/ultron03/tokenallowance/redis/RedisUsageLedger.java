package io.github.ultron03.tokenallowance.redis;

import java.time.ZoneId;
import java.util.ArrayList;
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
 * <p>A refused connection or a command timeout becomes {@link LedgerUnavailableException}; other
 * Redis errors, such as a failing script, are bugs and pass through unchanged.
 */
public final class RedisUsageLedger implements UsageLedger {

	private static final Logger log = LoggerFactory.getLogger(RedisUsageLedger.class);

	private static final RedisScript<Long> SETTLE = RedisScript
		.of(new ClassPathResource("settle.lua", RedisUsageLedger.class), Long.class);

	private static final RedisScript<Long> RESERVE = RedisScript
		.of(new ClassPathResource("reserve.lua", RedisUsageLedger.class), Long.class);

	private static final long NOT_SETTLED = 0;

	private static final long RESERVED = 0;

	private static final String NO_CAP = "-1";

	private static final String UNAVAILABLE = "Redis is unavailable";

	private final StringRedisTemplate redis;

	private final ZoneId zone;

	/** Creates a ledger on the given connection; {@code zone} decides when daily and monthly windows end. */
	public RedisUsageLedger(StringRedisTemplate redis, ZoneId zone) {
		this.redis = Objects.requireNonNull(redis, "redis can't be null");
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
		List<String> redisKeys = new ArrayList<>();
		redisKeys.add(RedisKeys.reservation(id));

		List<String> args = new ArrayList<>();
		args.add(String.valueOf(estimate.tokens()));
		args.add(String.valueOf(estimate.cost().micros()));
		args.add(String.valueOf(RedisKeys.RESERVATION_TTL.toSeconds()));

		for (BudgetKey key : keys) {
			Limit limit = limits.get(key);
			redisKeys.add(RedisKeys.budget(key));
			args.add(maxTokensOf(limit));
			args.add(maxMicrosOf(limit));
			args.add(expiryOf(key));
		}

		try {
			// execute only returns null inside a pipeline or transaction, which this class never opens.
			return Objects.requireNonNull(this.redis.execute(RESERVE, redisKeys, args.toArray()),
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

	private void settle(Reservation reservation, Amount charged) {
		Objects.requireNonNull(reservation, "reservation can't be null");

		List<String> keys = new ArrayList<>();
		keys.add(RedisKeys.reservation(reservation.id()));

		List<String> args = new ArrayList<>();
		args.add(String.valueOf(-reservation.amount().tokens()));
		args.add(String.valueOf(-reservation.amount().cost().micros()));
		args.add(String.valueOf(charged.tokens()));
		args.add(String.valueOf(charged.cost().micros()));

		// One loop for both lists, so the script finds each key's expiry at the same position.
		for (BudgetKey key : reservation.keys()) {
			keys.add(RedisKeys.budget(key));
			args.add(expiryOf(key));
		}

		Long settled;
		try {
			settled = this.redis.execute(SETTLE, keys, args.toArray());
		}
		catch (DataAccessResourceFailureException | QueryTimeoutException ex) {
			throw new LedgerUnavailableException(UNAVAILABLE, ex);
		}
		if (settled == null || settled == NOT_SETTLED) {
			log.debug("Reservation {} was already settled or has expired", reservation.id());
		}
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
