package dev.perf.chat.conversation;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationServiceTest {

	private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

	private final ConversationRepository conversations = mock(ConversationRepository.class);

	private final ChatTurnRepository turns = mock(ChatTurnRepository.class);

	private final TurnDispatcher dispatcher = mock(TurnDispatcher.class);

	private final ConversationService service = new ConversationService(this.conversations, this.turns,
			this.dispatcher, Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void createsConversation() {
		UUID id = this.service.createConversation();

		verify(this.conversations).insert(id, NOW);
	}

	@Test
	void sendMessageInsertsPendingTurnAndReturnsDispatcherResult() {
		UUID conversationId = UUID.randomUUID();
		when(this.conversations.exists(conversationId)).thenReturn(true);
		ChatTurn dispatched = ChatTurn.pending(conversationId, "Recife", NOW);
		when(this.dispatcher.dispatch(any())).thenReturn(dispatched);

		ChatTurn result = this.service.sendMessage(conversationId, "Recife");

		ArgumentCaptor<ChatTurn> inserted = ArgumentCaptor.forClass(ChatTurn.class);
		verify(this.turns).insert(inserted.capture());
		assertThat(inserted.getValue().status()).isEqualTo(TurnStatus.PENDING);
		assertThat(inserted.getValue().userContent()).isEqualTo("Recife");
		assertThat(inserted.getValue().createdAt()).isEqualTo(NOW);
		verify(this.dispatcher).dispatch(inserted.getValue());
		assertThat(result).isSameAs(dispatched);
	}

	@Test
	void sendMessageToUnknownConversationFails() {
		UUID conversationId = UUID.randomUUID();
		when(this.conversations.exists(conversationId)).thenReturn(false);

		assertThatThrownBy(() -> this.service.sendMessage(conversationId, "Recife"))
			.isInstanceOf(NotFoundException.class);
		verify(this.turns, never()).insert(any());
	}

	@Test
	void getTurnFailsWhenMissing() {
		UUID id = UUID.randomUUID();
		when(this.turns.findById(id)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> this.service.getTurn(id)).isInstanceOf(NotFoundException.class);
	}

	@Test
	void historyFailsForUnknownConversation() {
		UUID conversationId = UUID.randomUUID();
		when(this.conversations.exists(conversationId)).thenReturn(false);

		assertThatThrownBy(() -> this.service.history(conversationId)).isInstanceOf(NotFoundException.class);
	}

	@Test
	void historyReturnsTurnsOfConversation() {
		UUID conversationId = UUID.randomUUID();
		when(this.conversations.exists(conversationId)).thenReturn(true);
		List<ChatTurn> history = List.of(ChatTurn.pending(conversationId, "Recife", NOW));
		when(this.turns.findByConversation(conversationId)).thenReturn(history);

		assertThat(this.service.history(conversationId)).isEqualTo(history);
	}

}
