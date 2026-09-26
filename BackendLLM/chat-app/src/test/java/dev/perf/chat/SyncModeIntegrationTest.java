package dev.perf.chat;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("sync")
class SyncModeIntegrationTest {

	private static final Pattern CONTEXT_SIZE = Pattern.compile("mensagens no contexto: (\\d+)");

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		TestContainers.registerChatDependencies(registry);
	}

	@Autowired
	MockMvc mvc;

	@Test
	void processesTurnInlineThroughLlmToolLlmAndKeepsMemory() throws Exception {
		String conversationId = createConversation();

		String first = sendMessage(conversationId, "Recife");
		assertThat(JsonPath.<String>read(first, "$.status")).isEqualTo("DONE");
		String firstAnswer = JsonPath.read(first, "$.response");
		// The mock only answers with text after receiving the tool result: proves LLM -> @Tool -> LLM.
		assertThat(firstAnswer).contains("Previsão para Recife");

		String second = sendMessage(conversationId, "Natal");
		String secondAnswer = JsonPath.read(second, "$.response");
		assertThat(secondAnswer).contains("Previsão para Natal");
		// Redis memory: the second turn sends the previous exchange to the LLM too.
		assertThat(contextSize(secondAnswer)).isGreaterThan(contextSize(firstAnswer));

		String messageId = JsonPath.read(first, "$.messageId");
		this.mvc.perform(get("/messages/{id}", messageId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DONE"))
			.andExpect(jsonPath("$.startedAt").isNotEmpty())
			.andExpect(jsonPath("$.completedAt").isNotEmpty());

		this.mvc.perform(get("/conversations/{id}/messages", conversationId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].content").value("Recife"))
			.andExpect(jsonPath("$[1].content").value("Natal"));
	}

	@Test
	void unknownConversationReturns404() throws Exception {
		this.mvc
			.perform(post("/conversations/{id}/messages", "00000000-0000-0000-0000-000000000000")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"content\":\"Recife\"}"))
			.andExpect(status().isNotFound());
	}

	private String createConversation() throws Exception {
		String body = this.mvc.perform(post("/conversations"))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(body, "$.conversationId");
	}

	private String sendMessage(String conversationId, String content) throws Exception {
		return this.mvc
			.perform(post("/conversations/{id}/messages", conversationId).contentType(MediaType.APPLICATION_JSON)
				.content("{\"content\":\"" + content + "\"}"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
	}

	private static int contextSize(String answer) {
		Matcher matcher = CONTEXT_SIZE.matcher(answer);
		assertThat(matcher.find()).as("mock answer should report the context size: %s", answer).isTrue();
		return Integer.parseInt(matcher.group(1));
	}

}
