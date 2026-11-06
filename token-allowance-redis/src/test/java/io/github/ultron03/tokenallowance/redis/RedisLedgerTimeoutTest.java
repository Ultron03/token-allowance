package io.github.ultron03.tokenallowance.redis;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import io.github.ultron03.tokenallowance.core.BudgetKey;
import io.github.ultron03.tokenallowance.core.LedgerUnavailableException;
import io.github.ultron03.tokenallowance.core.Scope;
import io.github.ultron03.tokenallowance.core.Window;

class RedisLedgerTimeoutTest extends RedisTestSupport {

	private static final Duration COMMAND_TIMEOUT = Duration.ofMillis(200);

	private final BudgetKey alice = BudgetKey.of(Scope.USER, "alice", Window.DAILY, Instant.now(), ZoneOffset.UTC);

	private LettuceConnectionFactory shortTimeoutFactory;

	private RedisUsageLedger ledger;

	@BeforeEach
	void createLedger() {
		LettuceClientConfiguration client = LettuceClientConfiguration.builder().commandTimeout(COMMAND_TIMEOUT).build();
		this.shortTimeoutFactory = new LettuceConnectionFactory(
				new RedisStandaloneConfiguration(redis.getHost(), redis.getFirstMappedPort()), client);
		this.shortTimeoutFactory.afterPropertiesSet();
		this.shortTimeoutFactory.start();
		this.ledger = new RedisUsageLedger(new StringRedisTemplate(this.shortTimeoutFactory), ZoneOffset.UTC);
	}

	@AfterEach
	void closeLedger() {
		this.shortTimeoutFactory.destroy();
	}

	@Test
	void currentThrowsLedgerUnavailableWhenRedisTimesOut() {
		// Connect first, so the paused Redis gives a command timeout and not a connect error.
		this.ledger.current(this.alice);
		redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec();
		try {
			assertThatThrownBy(() -> this.ledger.current(this.alice)).isInstanceOf(LedgerUnavailableException.class)
				.hasCauseInstanceOf(QueryTimeoutException.class);
		}
		finally {
			redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec();
		}
	}
}
