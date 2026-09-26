package dev.perf.chat.queue;

import java.time.Clock;

import dev.perf.chat.conversation.ChatTurn;
import dev.perf.chat.conversation.ChatTurnRepository;
import dev.perf.chat.conversation.TurnDispatcher;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** queue variant (api): publish and return immediately; the worker processes the turn. */
@Component
@Profile("api")
public class RabbitTurnDispatcher implements TurnDispatcher {

	private final RabbitTemplate rabbitTemplate;

	private final ChatTurnRepository turns;

	private final Clock clock;

	public RabbitTurnDispatcher(RabbitTemplate rabbitTemplate, ChatTurnRepository turns, Clock clock) {
		this.rabbitTemplate = rabbitTemplate;
		this.turns = turns;
		this.clock = clock;
	}

	@Override
	public ChatTurn dispatch(ChatTurn pendingTurn) {
		try {
			this.rabbitTemplate.convertAndSend(RabbitTopology.EXCHANGE, RabbitTopology.ROUTING_KEY,
					pendingTurn.id().toString());
		}
		catch (AmqpException ex) {
			// No outbox: the turn is already committed as PENDING, so mark it FAILED to keep
			// the database consistent with what the client is told (503).
			this.turns.markFailed(pendingTurn.id(), "Falha ao publicar na fila: " + ex.getMessage(),
					this.clock.instant());
			throw new DispatchFailedException(ex);
		}
		// Return the in-memory PENDING state instead of re-reading: the worker may already
		// have picked the turn up, and the API contract for this variant is "202 PENDING".
		return pendingTurn;
	}

}
