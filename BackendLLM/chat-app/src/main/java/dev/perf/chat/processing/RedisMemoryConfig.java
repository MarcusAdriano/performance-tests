package dev.perf.chat.processing;

import redis.clients.jedis.ConnectionPoolConfig;
import redis.clients.jedis.RedisClient;

import org.springframework.ai.model.chat.memory.repository.redis.autoconfigure.RedisChatMemoryRepositoryProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RedisMemoryConfig {

	/**
	 * Replaces the starter's RedisClient (@ConditionalOnMissingBean) only to make the
	 * Jedis pool size tunable. Jedis defaults to 8 connections: with thousands of virtual
	 * threads this is a likely bottleneck worth observing (REDIS_POOL_MAX).
	 */
	@Bean
	RedisClient jedisClient(RedisChatMemoryRepositoryProperties properties,
			@Value("${app.redis.pool-max}") int poolMax) {
		ConnectionPoolConfig pool = new ConnectionPoolConfig();
		pool.setMaxTotal(poolMax);
		pool.setMaxIdle(poolMax);
		return RedisClient.builder().hostAndPort(properties.getHost(), properties.getPort()).poolConfig(pool).build();
	}

}
