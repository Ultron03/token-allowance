package io.github.ultron03.tokenallowance.redis;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for tests that need a real Redis: starts one container per test class and empties it
 * before each test. Tests are skipped when Docker isn't available.
 */
@Testcontainers(disabledWithoutDocker = true)
abstract class RedisTestSupport {

	private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7.4-alpine");

	private static final int REDIS_PORT = 6379;

	@Container
	static final GenericContainer<?> redis = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(REDIS_PORT);

	private static LettuceConnectionFactory connectionFactory;

	static StringRedisTemplate redisTemplate;

	@BeforeAll
	static void connect() {
		connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(REDIS_PORT));
		connectionFactory.afterPropertiesSet();
		connectionFactory.start();
		redisTemplate = new StringRedisTemplate(connectionFactory);
	}

	@BeforeEach
	void flushRedis() {
		redisTemplate.execute((RedisCallback<Void>) connection -> {
			connection.serverCommands().flushAll();
			return null;
		});
	}

	@AfterAll
	static void disconnect() {
		connectionFactory.destroy();
	}
}
