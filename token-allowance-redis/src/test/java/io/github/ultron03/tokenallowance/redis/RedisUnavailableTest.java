package io.github.ultron03.tokenallowance.redis;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import io.github.ultron03.tokenallowance.core.Amount;
import io.github.ultron03.tokenallowance.core.BudgetKey;
import io.github.ultron03.tokenallowance.core.LedgerUnavailableException;
import io.github.ultron03.tokenallowance.core.Limit;
import io.github.ultron03.tokenallowance.core.Money;
import io.github.ultron03.tokenallowance.core.Reservation;
import io.github.ultron03.tokenallowance.core.Scope;
import io.github.ultron03.tokenallowance.core.Window;

/** Runs without Docker: the ledger points at a port nothing listens on. */
class RedisUnavailableTest {

	private static final int CLOSED_PORT = 1;

	private static final Amount ESTIMATE = new Amount(1000, new Money(500));

	private static final BudgetKey ALICE = BudgetKey.of(Scope.USER, "alice", Window.DAILY, Instant.now(),
			ZoneOffset.UTC);

	private static LettuceConnectionFactory connectionFactory;

	private static RedisUsageLedger ledger;

	@BeforeAll
	static void connect() {
		connectionFactory = new LettuceConnectionFactory("localhost", CLOSED_PORT);
		connectionFactory.afterPropertiesSet();
		connectionFactory.start();
		ledger = new RedisUsageLedger(new StringRedisTemplate(connectionFactory), ZoneOffset.UTC);
	}

	@AfterAll
	static void disconnect() {
		connectionFactory.destroy();
	}

	@Test
	void reserveThrowsLedgerUnavailableWhenRedisIsDown() {
		Map<BudgetKey, Limit> limits = Map.of(ALICE, new Limit(Scope.USER, Window.DAILY, 10_000L, null));

		assertThatThrownBy(() -> ledger.reserve(limits, ESTIMATE)).isInstanceOf(LedgerUnavailableException.class)
			.hasCauseInstanceOf(RedisConnectionFailureException.class);
	}

	@Test
	void commitThrowsLedgerUnavailableWhenRedisIsDown() {
		Reservation reservation = new Reservation("r1", Set.of(ALICE), ESTIMATE);

		assertThatThrownBy(() -> ledger.commit(reservation, ESTIMATE)).isInstanceOf(LedgerUnavailableException.class)
			.hasCauseInstanceOf(RedisConnectionFailureException.class);
	}

	@Test
	void currentThrowsLedgerUnavailableWhenRedisIsDown() {
		assertThatThrownBy(() -> ledger.current(ALICE)).isInstanceOf(LedgerUnavailableException.class)
			.hasCauseInstanceOf(RedisConnectionFailureException.class);
	}
}
