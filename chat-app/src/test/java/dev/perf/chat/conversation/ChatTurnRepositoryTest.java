package dev.perf.chat.conversation;

import java.time.Instant;
import java.util.UUID;

import dev.perf.chat.support.TestContainers;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ ConversationRepository.class, ChatTurnRepository.class })
class ChatTurnRepositoryTest {

	private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		TestContainers.registerPostgres(registry);
	}

	@Autowired
	ConversationRepository conversations;

	@Autowired
	ChatTurnRepository turns;

	private UUID newConversation() {
		UUID id = UUID.randomUUID();
		this.conversations.insert(id, T0);
		return id;
	}

	@Test
	void conversationExistsAfterInsert() {
		UUID id = newConversation();

		assertThat(this.conversations.exists(id)).isTrue();
		assertThat(this.conversations.exists(UUID.randomUUID())).isFalse();
	}

	@Test
	void insertsAndReadsPendingTurn() {
		ChatTurn turn = ChatTurn.pending(newConversation(), "Recife", T0);

		this.turns.insert(turn);

		assertThat(this.turns.findById(turn.id())).contains(turn);
	}

	@Test
	void markProcessingOnlyTransitionsPendingTurns() {
		ChatTurn turn = ChatTurn.pending(newConversation(), "Recife", T0);
		this.turns.insert(turn);
		Instant startedAt = T0.plusSeconds(2);

		assertThat(this.turns.markProcessing(turn.id(), startedAt)).isTrue();
		assertThat(this.turns.markProcessing(turn.id(), startedAt.plusSeconds(1))).isFalse();

		ChatTurn processing = this.turns.findById(turn.id()).orElseThrow();
		assertThat(processing.status()).isEqualTo(TurnStatus.PROCESSING);
		assertThat(processing.startedAt()).isEqualTo(startedAt);
	}

	@Test
	void markDoneStoresAnswer() {
		ChatTurn turn = ChatTurn.pending(newConversation(), "Recife", T0);
		this.turns.insert(turn);
		Instant completedAt = T0.plusSeconds(7);

		this.turns.markDone(turn.id(), "Faz 25°C", completedAt);

		ChatTurn done = this.turns.findById(turn.id()).orElseThrow();
		assertThat(done.status()).isEqualTo(TurnStatus.DONE);
		assertThat(done.assistantContent()).isEqualTo("Faz 25°C");
		assertThat(done.completedAt()).isEqualTo(completedAt);
		assertThat(done.isFinished()).isTrue();
	}

	@Test
	void markFailedStoresError() {
		ChatTurn turn = ChatTurn.pending(newConversation(), "Recife", T0);
		this.turns.insert(turn);

		this.turns.markFailed(turn.id(), "LLM timeout", T0.plusSeconds(30));

		ChatTurn failed = this.turns.findById(turn.id()).orElseThrow();
		assertThat(failed.status()).isEqualTo(TurnStatus.FAILED);
		assertThat(failed.error()).isEqualTo("LLM timeout");
	}

	@Test
	void findByConversationReturnsTurnsInChronologicalOrder() {
		UUID conversationId = newConversation();
		ChatTurn second = ChatTurn.pending(conversationId, "segunda", T0.plusSeconds(10));
		ChatTurn first = ChatTurn.pending(conversationId, "primeira", T0);
		this.turns.insert(second);
		this.turns.insert(first);
		this.turns.insert(ChatTurn.pending(newConversation(), "outra conversa", T0));

		assertThat(this.turns.findByConversation(conversationId)).extracting(ChatTurn::userContent)
			.containsExactly("primeira", "segunda");
	}

	@Test
	void findByIdReturnsEmptyForUnknownTurn() {
		assertThat(this.turns.findById(UUID.randomUUID())).isEmpty();
	}

}
