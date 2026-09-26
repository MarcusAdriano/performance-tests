package dev.perf.chat.conversation;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile({ "sync", "api" })
public class ConversationService {

	private final ConversationRepository conversations;

	private final ChatTurnRepository turns;

	private final TurnDispatcher dispatcher;

	private final Clock clock;

	public ConversationService(ConversationRepository conversations, ChatTurnRepository turns,
			TurnDispatcher dispatcher, Clock clock) {
		this.conversations = conversations;
		this.turns = turns;
		this.dispatcher = dispatcher;
		this.clock = clock;
	}

	public UUID createConversation() {
		UUID id = UUID.randomUUID();
		this.conversations.insert(id, this.clock.instant());
		return id;
	}

	/** Same DB writes in both variants: INSERT PENDING here, the rest in ConversationProcessor. */
	public ChatTurn sendMessage(UUID conversationId, String content) {
		requireConversation(conversationId);
		ChatTurn turn = ChatTurn.pending(conversationId, content, this.clock.instant());
		this.turns.insert(turn);
		return this.dispatcher.dispatch(turn);
	}

	public ChatTurn getTurn(UUID id) {
		return this.turns.findById(id).orElseThrow(() -> new NotFoundException("Mensagem não encontrada: " + id));
	}

	public List<ChatTurn> history(UUID conversationId) {
		requireConversation(conversationId);
		return this.turns.findByConversation(conversationId);
	}

	private void requireConversation(UUID conversationId) {
		if (!this.conversations.exists(conversationId)) {
			throw new NotFoundException("Conversa não encontrada: " + conversationId);
		}
	}

}
