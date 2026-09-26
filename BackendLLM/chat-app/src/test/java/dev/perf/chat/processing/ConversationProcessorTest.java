package dev.perf.chat.processing;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import dev.perf.chat.conversation.ChatTurn;
import dev.perf.chat.conversation.ChatTurnRepository;
import dev.perf.chat.conversation.TurnStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConversationProcessorTest {

	private static final Instant CREATED = Instant.parse("2026-01-01T10:00:00Z");

	private static final Instant NOW = Instant.parse("2026-01-01T10:00:03Z");

	private final ChatTurnRepository turns = mock(ChatTurnRepository.class);

	private final ChatAssistant assistant = mock(ChatAssistant.class);

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

	private final ConversationProcessor processor = new ConversationProcessor(this.turns, this.assistant,
			Clock.fixed(NOW, ZoneOffset.UTC), this.registry, "worker");

	private final UUID conversationId = UUID.randomUUID();

	private final ChatTurn turn = new ChatTurn(UUID.randomUUID(), this.conversationId, "Recife", null,
			TurnStatus.PROCESSING, null, CREATED, NOW, null);

	private void turnIsPending() {
		when(this.turns.markProcessing(this.turn.id(), NOW)).thenReturn(true);
		when(this.turns.findById(this.turn.id())).thenReturn(Optional.of(this.turn));
	}

	@Test
	void marksTurnDoneWithAssistantAnswer() {
		turnIsPending();
		when(this.assistant.reply(this.conversationId, "Recife")).thenReturn("Faz 25°C");

		this.processor.process(this.turn.id());

		verify(this.turns).markDone(this.turn.id(), "Faz 25°C", NOW);
		verify(this.turns, never()).markFailed(any(), any(), any());
		assertThat(this.registry.get("chat.turns").tags("mode", "worker", "outcome", "done").counter().count())
			.isEqualTo(1.0);
		assertThat(this.registry.get("chat.turn.processing").tags("outcome", "done").timer().count()).isEqualTo(1);
	}

	@Test
	void marksTurnFailedWhenAssistantThrows() {
		turnIsPending();
		when(this.assistant.reply(this.conversationId, "Recife")).thenThrow(new IllegalStateException("LLM timeout"));

		this.processor.process(this.turn.id());

		verify(this.turns).markFailed(this.turn.id(), "LLM timeout", NOW);
		verify(this.turns, never()).markDone(any(), any(), any());
		assertThat(this.registry.get("chat.turns").tags("mode", "worker", "outcome", "failed").counter().count())
			.isEqualTo(1.0);
	}

	@Test
	void usesExceptionClassNameWhenMessageIsNull() {
		turnIsPending();
		when(this.assistant.reply(this.conversationId, "Recife")).thenThrow(new IllegalStateException());

		this.processor.process(this.turn.id());

		verify(this.turns).markFailed(this.turn.id(), "IllegalStateException", NOW);
	}

	@Test
	void skipsTurnThatIsNoLongerPending() {
		when(this.turns.markProcessing(this.turn.id(), NOW)).thenReturn(false);

		this.processor.process(this.turn.id());

		verifyNoInteractions(this.assistant);
		verify(this.turns, never()).markDone(any(), any(), any());
		verify(this.turns, never()).markFailed(any(), any(), any());
	}

	@Test
	void recordsQueueWaitFromCreationToStart() {
		turnIsPending();
		when(this.assistant.reply(this.conversationId, "Recife")).thenReturn("ok");

		this.processor.process(this.turn.id());

		assertThat(this.registry.get("chat.turn.queue.wait").tags("mode", "worker").timer().totalTime(TimeUnit.SECONDS))
			.isEqualTo(3.0);
	}

	@Test
	void inflightGaugeReturnsToZeroAfterProcessing() {
		turnIsPending();
		when(this.assistant.reply(this.conversationId, "Recife")).thenThrow(new IllegalStateException("boom"));

		this.processor.process(this.turn.id());

		assertThat(this.registry.get("chat.turns.inflight").tags("mode", "worker").gauge().value()).isZero();
	}

}
