package dev.perf.chat;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.health.DataRedisHealthContributorAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.health.DataRedisReactiveHealthContributorAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.observation.LettuceObservationAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * spring-boot-starter-data-redis comes in transitively with the Redis chat-memory
 * starter, but the chat memory talks to Redis through its own Jedis client. Nothing uses
 * Spring Data Redis / Lettuce, so its auto-configuration (connection factory, health
 * indicator, observation) is excluded in every mode. Excluded here rather than via
 * spring.autoconfigure.exclude so profile files can set their own exclude lists.
 */
@SpringBootApplication(exclude = { DataRedisAutoConfiguration.class, DataRedisReactiveAutoConfiguration.class,
		DataRedisRepositoriesAutoConfiguration.class, DataRedisHealthContributorAutoConfiguration.class,
		DataRedisReactiveHealthContributorAutoConfiguration.class, LettuceObservationAutoConfiguration.class })
public class ChatApplication {

	public static void main(String[] args) {
		SpringApplication.run(ChatApplication.class, args);
	}

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
