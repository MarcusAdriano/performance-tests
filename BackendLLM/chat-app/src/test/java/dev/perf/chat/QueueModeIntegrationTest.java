package dev.perf.chat;

import java.time.Duration;

import com.jayway.jsonpath.JsonPath;
import dev.perf.chat.support.TestContainers;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** api + worker in one context: same code paths as the two containers, one JVM. */
@SpringBootTest(properties = "app.mode=worker")
@AutoConfigureMockMvc
@ActiveProfiles({ "api", "worker" })
class QueueModeIntegrationTest {

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		TestContainers.registerChatDependencies(registry);
		TestContainers.registerRabbit(registry);
	}

	@Autowired
	MockMvc mvc;

	@Test
	void acceptsTurnAndWorkerCompletesItAsynchronously() throws Exception {
		String created = this.mvc.perform(post("/conversations"))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String conversationId = JsonPath.read(created, "$.conversationId");

		String accepted = this.mvc
			.perform(post("/conversations/{id}/messages", conversationId).contentType(MediaType.APPLICATION_JSON)
				.content("{\"content\":\"Recife\"}"))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String messageId = JsonPath.read(accepted, "$.messageId");

		// Same polling loop the JMeter plan will use.
		await().atMost(Duration.ofSeconds(30))
			.pollInterval(Duration.ofMillis(200))
			.untilAsserted(() -> this.mvc.perform(get("/messages/{id}", messageId))
				.andExpect(jsonPath("$.status").value("DONE")));

		this.mvc.perform(get("/messages/{id}", messageId))
			.andExpect(jsonPath("$.response", containsString("Previsão para Recife")))
			.andExpect(jsonPath("$.startedAt").isNotEmpty());
	}

}
