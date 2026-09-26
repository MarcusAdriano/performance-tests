package dev.perf.chat.support;

import java.nio.file.Path;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * JVM-wide singleton containers shared by all test classes. {@code start()} is a no-op
 * when a container is already running.
 */
public final class TestContainers {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

	private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.6").withExposedPorts(6379);

	private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.3-management");

	// Built from ../llm-mock/Dockerfile so tests exercise the real mock contract; 10ms keeps them fast.
	private static final GenericContainer<?> LLM_MOCK = new GenericContainer<>(
			new ImageFromDockerfile("backendllm/llm-mock-test", false).withFileFromPath(".", Path.of("../llm-mock")))
		.withEnv("MIN_DELAY_MS", "10")
		.withEnv("MAX_DELAY_MS", "10")
		.withExposedPorts(9000)
		.waitingFor(Wait.forHttp("/healthz"));

	private TestContainers() {
	}

	public static void registerPostgres(DynamicPropertyRegistry registry) {
		POSTGRES.start();
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	/** Postgres + Redis (chat memory) + llm-mock: everything the sync and worker modes need. */
	public static void registerChatDependencies(DynamicPropertyRegistry registry) {
		registerPostgres(registry);
		REDIS.start();
		LLM_MOCK.start();
		registry.add("spring.ai.chat.memory.repository.redis.host", REDIS::getHost);
		registry.add("spring.ai.chat.memory.repository.redis.port", () -> REDIS.getMappedPort(6379));
		registry.add("spring.ai.openai.base-url",
				() -> "http://%s:%d/v1".formatted(LLM_MOCK.getHost(), LLM_MOCK.getMappedPort(9000)));
	}

	public static void registerRabbit(DynamicPropertyRegistry registry) {
		RABBIT.start();
		registry.add("spring.rabbitmq.host", RABBIT::getHost);
		registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
		registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
		registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
	}

}
