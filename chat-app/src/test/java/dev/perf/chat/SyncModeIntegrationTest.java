package dev.perf.chat;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.jayway.jsonpath.JsonPath;
import dev.perf.chat.support.TestContainers;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.RedisClient;

import org.springframework.ai.chat.memory.ChatMemory;
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

	@Autowired
	RedisClient redis;

	@Autowired
	ChatMemory chatMemory;

	@Test
	void processesTurnInlineThroughLlmToolLlmAndKeepsMemory() throws Exception {
		String conversationId = createConversation();

		String first = sendMessage(conversationId, "Recife");
		assertThat(JsonPath.<String>read(first, "$.status")).isEqualTo("DONE");
		String firstAnswer = JsonPath.read(first, "$.response");
		// The mock only answers with text after receiving the tool result: proves LLM -> @Tool -> LLM.
		assertThat(firstAnswer).contains("Previsão para Recife");

		// Final LLM call of turn N = system + 2(N-1) remembered messages (user + final
		// assistant of every earlier turn) + user N + assistant(tool_calls) + tool = 2N + 2.
		assertThat(contextSize(firstAnswer)).isEqualTo(4);

		String second = sendMessage(conversationId, "Natal");
		String secondAnswer = JsonPath.read(second, "$.response");
		assertThat(secondAnswer).contains("Previsão para Natal");
		// Redis memory: the second turn sends the previous exchange (user + assistant) too.
		assertThat(contextSize(secondAnswer)).isEqualTo(6);

		String third = sendMessage(conversationId, "Recife");
		assertThat(contextSize(JsonPath.read(third, "$.response"))).isEqualTo(8);

		String messageId = JsonPath.read(first, "$.messageId");
		this.mvc.perform(get("/messages/{id}", messageId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DONE"))
			.andExpect(jsonPath("$.startedAt").isNotEmpty())
			.andExpect(jsonPath("$.completedAt").isNotEmpty());

		this.mvc.perform(get("/conversations/{id}/messages", conversationId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(3))
			.andExpect(jsonPath("$[0].content").value("Recife"))
			.andExpect(jsonPath("$[1].content").value("Natal"));
	}

	/**
	 * Runs past both the FT.SEARCH default page (10 docs, relevant to clear() inside
	 * saveAll) and the 20-message window, proving memory neither loses nor duplicates
	 * messages.
	 * <p>
	 * MessageChatMemoryAdvisor reads memory before storing the new user message, so the
	 * final call of turn N has |memory before N| + 4 messages (system, user N,
	 * assistant(tool_calls), tool). Memory grows by 2 per turn (user + final assistant):
	 * 0, 2, ..., 18 after turn 9, 20 after turn 10. In turn 11 adding user 11 makes 21 &gt;
	 * 20: MessageWindowChatMemory cuts 1 message, snapped forward to the next USER, so
	 * the oldest pair (user 1, assistant 1) goes -> 19, then assistant 11 -> 20. From then
	 * on memory stays at 20, so turns 1..10 send 2N + 2 (4..22) and every later turn 24.
	 */
	@Test
	void memoryWindowCapsContextWithoutDuplicates() throws Exception {
		String conversationId = createConversation();
		for (int turn = 1; turn <= 13; turn++) {
			String answer = JsonPath.read(sendMessage(conversationId, "Cidade" + turn), "$.response");
			int expected = (turn <= 10) ? 2 * turn + 2 : 24;
			assertThat(contextSize(answer)).as("context size of turn %d", turn).isEqualTo(expected);
		}
		// Redis holds exactly the window: 20 message documents (all of them indexed, no
		// leftovers from clear-then-add) plus the repository's timestamp counter key.
		assertThat(this.redis.keys("chat-memory:" + conversationId + ":*")).hasSize(20);
		assertThat(this.redis.keys("chat-memory:counter:" + conversationId)).hasSize(1);
		assertThat(this.chatMemory.get(conversationId)).hasSize(20);
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
