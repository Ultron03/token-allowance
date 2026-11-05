package io.github.ultron03.tokenallowance.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.ultron03.tokenallowance.core.BudgetKey;
import io.github.ultron03.tokenallowance.core.Scope;
import io.github.ultron03.tokenallowance.core.Window;

class RedisSchemaTest extends RedisTestSupport {

	@Test
	void storesSpendInBudgetHashThatExpiresAfterWindow() {
		BudgetKey key = BudgetKey.of(Scope.USER, "a:{b}", Window.DAILY, Instant.now(), ZoneOffset.UTC);
		String redisKey = RedisKeys.budget(key);

		redisTemplate.opsForHash().increment(redisKey, RedisKeys.COMMITTED_TOKENS, 120);
		redisTemplate.opsForHash().increment(redisKey, RedisKeys.COMMITTED_MICROS, 450);
		redisTemplate.expireAt(redisKey, RedisKeys.expiresAt(key, ZoneOffset.UTC));

		Map<Object, Object> fields = redisTemplate.opsForHash().entries(redisKey);
		assertThat(fields).containsOnly(Map.entry(RedisKeys.COMMITTED_TOKENS, "120"),
				Map.entry(RedisKeys.COMMITTED_MICROS, "450"));
		assertThat(redisTemplate.getExpire(redisKey)).isBetween(Duration.ofDays(1).toSeconds(),
				Duration.ofDays(2).toSeconds());
	}
}
