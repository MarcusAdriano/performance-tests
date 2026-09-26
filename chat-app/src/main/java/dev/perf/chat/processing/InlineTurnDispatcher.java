package dev.perf.chat.processing;

import dev.perf.chat.conversation.ChatTurn;
import dev.perf.chat.conversation.ChatTurnRepository;
import dev.perf.chat.conversation.TurnDispatcher;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** sync variant: the HTTP request thread (a virtual thread) waits for the whole turn. */
@Component
@Profile("sync")
public class InlineTurnDispatcher implements TurnDispatcher {

	private final ConversationProcessor processor;

	private final ChatTurnRepository turns;

	public InlineTurnDispatcher(ConversationProcessor processor, ChatTurnRepository turns) {
		this.processor = processor;
		this.turns = turns;
	}

	@Override
	public ChatTurn dispatch(ChatTurn pendingTurn) {
		this.processor.process(pendingTurn.id());
		return this.turns.findById(pendingTurn.id()).orElseThrow();
	}

}
