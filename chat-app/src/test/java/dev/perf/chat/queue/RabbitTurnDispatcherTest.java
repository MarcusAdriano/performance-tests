package dev.perf.chat.queue;

import java.net.ConnectException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import dev.perf.chat.conversation.ChatTurn;
import dev.perf.chat.conversation.ChatTurnRepository;
import org.junit.jupiter.api.Test;

import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class RabbitTurnDispatcherTest {

	private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

	private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);

	private final ChatTurnRepository turns = mock(ChatTurnRepository.class);

	private final RabbitTurnDispatcher dispatcher = new RabbitTurnDispatcher(this.rabbitTemplate, this.turns,
			Clock.fixed(NOW, ZoneOffset.UTC));

	private final ChatTurn pending = ChatTurn.pending(UUID.randomUUID(), "Recife", NOW);

	@Test
	void publishesTurnIdAndReturnsPendingTurn() {
		ChatTurn result = this.dispatcher.dispatch(this.pending);

		verify(this.rabbitTemplate).convertAndSend("chat.turns", "process", this.pending.id().toString());
		verifyNoInteractions(this.turns);
		assertThat(result).isSameAs(this.pending);
	}

	@Test
	void marksTurnFailedAndThrowsWhenPublishFails() {
		doThrow(new AmqpConnectException(new ConnectException("refused"))).when(this.rabbitTemplate)
			.convertAndSend(anyString(), anyString(), any(Object.class));

		assertThatThrownBy(() -> this.dispatcher.dispatch(this.pending)).isInstanceOf(DispatchFailedException.class);

		verify(this.turns).markFailed(eq(this.pending.id()), startsWith("Falha ao publicar na fila"), eq(NOW));
	}

}
