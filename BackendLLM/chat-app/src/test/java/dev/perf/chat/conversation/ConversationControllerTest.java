package dev.perf.chat.conversation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConversationController.class)
@ActiveProfiles("sync")
class ConversationControllerTest {

	private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

	@Autowired
	MockMvc mvc;

	@MockitoBean
	ConversationService service;

	private final UUID conversationId = UUID.randomUUID();

	private ChatTurn turn(TurnStatus status, String answer) {
		return new ChatTurn(UUID.randomUUID(), this.conversationId, "Recife", answer, status, null, T0, null, null);
	}

	@Test
	void createsConversation() throws Exception {
		when(this.service.createConversation()).thenReturn(this.conversationId);

		this.mvc.perform(post("/conversations"))
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", "/conversations/" + this.conversationId))
			.andExpect(jsonPath("$.conversationId").value(this.conversationId.toString()));
	}

	@Test
	void returns200WhenTurnFinishedInline() throws Exception {
		ChatTurn done = turn(TurnStatus.DONE, "Faz 25°C");
		when(this.service.sendMessage(this.conversationId, "Recife")).thenReturn(done);

		this.mvc
			.perform(post("/conversations/{id}/messages", this.conversationId).contentType(MediaType.APPLICATION_JSON)
				.content("{\"content\":\"Recife\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.messageId").value(done.id().toString()))
			.andExpect(jsonPath("$.status").value("DONE"))
			.andExpect(jsonPath("$.response").value("Faz 25°C"));
	}

	@Test
	void returns202WhenTurnIsStillPending() throws Exception {
		when(this.service.sendMessage(this.conversationId, "Recife")).thenReturn(turn(TurnStatus.PENDING, null));

		this.mvc
			.perform(post("/conversations/{id}/messages", this.conversationId).contentType(MediaType.APPLICATION_JSON)
				.content("{\"content\":\"Recife\"}"))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("PENDING"));
	}

	@Test
	void rejectsBlankContent() throws Exception {
		this.mvc
			.perform(post("/conversations/{id}/messages", this.conversationId).contentType(MediaType.APPLICATION_JSON)
				.content("{\"content\":\"  \"}"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void rejectsContentLongerThan4000Characters() throws Exception {
		String tooLong = "a".repeat(4001);

		this.mvc
			.perform(post("/conversations/{id}/messages", this.conversationId).contentType(MediaType.APPLICATION_JSON)
				.content("{\"content\":\"" + tooLong + "\"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void returns404ForUnknownConversation() throws Exception {
		when(this.service.sendMessage(any(), any())).thenThrow(new NotFoundException("Conversa não encontrada"));

		this.mvc
			.perform(post("/conversations/{id}/messages", this.conversationId).contentType(MediaType.APPLICATION_JSON)
				.content("{\"content\":\"Recife\"}"))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.detail").value("Conversa não encontrada"));
	}

	@Test
	void returnsTurnById() throws Exception {
		ChatTurn done = turn(TurnStatus.DONE, "Faz 25°C");
		when(this.service.getTurn(done.id())).thenReturn(done);

		this.mvc.perform(get("/messages/{id}", done.id()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DONE"))
			.andExpect(jsonPath("$.content").value("Recife"))
			.andExpect(jsonPath("$.createdAt").value("2026-01-01T10:00:00Z"));
	}

	@Test
	void returnsConversationHistory() throws Exception {
		ChatTurn done = turn(TurnStatus.DONE, "Faz 25°C");
		when(this.service.history(this.conversationId)).thenReturn(List.of(done));

		this.mvc.perform(get("/conversations/{id}/messages", this.conversationId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].messageId").value(done.id().toString()));
	}

}
