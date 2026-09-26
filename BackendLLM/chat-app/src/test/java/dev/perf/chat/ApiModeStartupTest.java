package dev.perf.chat;

import dev.perf.chat.support.TestContainers;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.RedisClient;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The api container only needs Postgres + RabbitMQ: no Redis, no LLM. Nothing here points
 * at Redis or the mock, so any leftover LLM / chat-memory wiring would either show up as
 * a bean or break startup / health.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
class ApiModeStartupTest {

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		TestContainers.registerPostgres(registry);
		TestContainers.registerRabbit(registry);
	}

	@Autowired
	ApplicationContext context;

	@Autowired
	MockMvc mvc;

	@Test
	void startsWithoutLlmOrChatMemoryStack() {
		assertThat(this.context.getBeanNamesForType(ChatModel.class)).isEmpty();
		assertThat(this.context.getBeanNamesForType(ChatClient.Builder.class)).isEmpty();
		assertThat(this.context.getBeanNamesForType(ToolCallingManager.class)).isEmpty();
		assertThat(this.context.getBeanNamesForType(ChatMemory.class)).isEmpty();
		assertThat(this.context.getBeanNamesForType(ChatMemoryRepository.class)).isEmpty();
		assertThat(this.context.getBeanNamesForType(RedisClient.class)).isEmpty();
		assertThat(this.context.getBeanNamesForType(RedisConnectionFactory.class)).isEmpty();
	}

	@Test
	void healthIsUpWithoutRedisComponent() throws Exception {
		this.mvc.perform(get("/actuator/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"))
			.andExpect(jsonPath("$.components.db.status").value("UP"))
			.andExpect(jsonPath("$.components.rabbit.status").value("UP"))
			.andExpect(jsonPath("$.components.redis").doesNotExist());
	}

}
