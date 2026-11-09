package io.github.ultron03.tokenallowance.redis;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;

import io.github.ultron03.tokenallowance.core.BudgetKey;

/**
 * Names, fields and expiry of the Redis keys the ledger uses.
 */
final class RedisKeys {

	/** Hash tag that puts every key in one Redis Cluster slot, so a script can touch several of them. */
	static final String PREFIX = "{ta}";

	/** How long a daily or monthly key outlives its window, so a call that spans the window end can still settle. */
	static final Duration GRACE = Duration.ofDays(1);

	/** Grace for minute and hour keys; long enough for any call, short enough that old keys don't pile up. */
	static final Duration SHORT_WINDOW_GRACE = Duration.ofHours(1);

	/** How long an unsettled reservation is remembered; a later commit or release is ignored. */
	static final Duration RESERVATION_TTL = Duration.ofDays(1);

	static final String COMMITTED_TOKENS = "committed_tokens";

	static final String COMMITTED_MICROS = "committed_micros";

	static final String RESERVED_TOKENS = "reserved_tokens";

	static final String RESERVED_MICROS = "reserved_micros";

	private static final String SEPARATOR = ":";

	private RedisKeys() {
	}

	/** {@return the hash that holds a budget's spend; the subject goes last, since only it may contain a separator} */
	static String budget(BudgetKey key) {
		return String.join(SEPARATOR, PREFIX, "budget", lowerCase(key.scope()), lowerCase(key.window()),
				String.valueOf(key.windowStart().getEpochSecond()), key.subjectId());
	}

	/** {@return the key that marks a reservation as not yet settled} */
	static String reservation(String id) {
		return String.join(SEPARATOR, PREFIX, "reservation", id);
	}

	/** {@return when a budget key may be deleted: its window's grace after the window ends} */
	static Instant expiresAt(BudgetKey key, ZoneId zone) {
		Duration grace = switch (key.window()) {
			case MINUTE, HOUR -> SHORT_WINDOW_GRACE;
			case DAILY, MONTHLY -> GRACE;
		};
		return key.window().endOf(key.windowStart(), zone).plus(grace);
	}

	private static String lowerCase(Enum<?> value) {
		return value.name().toLowerCase(Locale.ROOT);
	}
}
