package dev.perf.chat.processing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import dev.perf.chat.conversation.ChatTurn;
import dev.perf.chat.conversation.ChatTurnRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * The shared core of both variants: called inline by the API (sync) or by the RabbitMQ
 * listener (worker).
 */
@Service
@Profile({ "sync", "worker" })
public class ConversationProcessor {

	private static final Logger log = LoggerFactory.getLogger(ConversationProcessor.class);

	private final ChatTurnRepository turns;

	private final ChatAssistant assistant;

	private final Clock clock;

	private final MeterRegistry meterRegistry;

	private final String mode;

	private final AtomicInteger inflight;

	public ConversationProcessor(ChatTurnRepository turns, ChatAssistant assistant, Clock clock,
			MeterRegistry meterRegistry, @Value("${app.mode}") String mode) {
		this.turns = turns;
		this.assistant = assistant;
		this.clock = clock;
		this.meterRegistry = meterRegistry;
		this.mode = mode;
		this.inflight = meterRegistry.gauge("chat.turns.inflight", Tags.of("mode", mode), new AtomicInteger());
	}

	/**
	 * Deliberately NOT @Transactional: each repository call commits on its own, so no
	 * Hikari connection is held during the 4-10s of LLM calls. A transaction around this
	 * method would cap throughput at pool_size / turn_duration (~1.4 turns/s with 10
	 * connections) - the classic mistake this lab lets you reproduce.
	 */
	public void process(UUID turnId) {
		Instant startedAt = this.clock.instant();
		if (!this.turns.markProcessing(turnId, startedAt)) {
			log.info("Turn {} is no longer PENDING, skipping (redelivery?)", turnId);
			return;
		}
		ChatTurn turn = this.turns.findById(turnId).orElseThrow();
		Timer.builder("chat.turn.queue.wait")
			.tag("mode", this.mode)
			.register(this.meterRegistry)
			.record(Duration.between(turn.createdAt(), startedAt));

		String answer;
		this.inflight.incrementAndGet();
		try {
			answer = this.assistant.reply(turn.conversationId(), turn.userContent());
		}
		catch (RuntimeException ex) {
			log.warn("Turn {} failed: {}", turnId, ex.toString());
			String error = (ex.getMessage() != null) ? ex.getMessage() : ex.getClass().getSimpleName();
			this.turns.markFailed(turnId, error, this.clock.instant());
			record("failed", startedAt);
			return;
		}
		finally {
			this.inflight.decrementAndGet();
		}
		this.turns.markDone(turnId, answer, this.clock.instant());
		record("done", startedAt);
	}

	private void record(String outcome, Instant startedAt) {
		Tags tags = Tags.of("mode", this.mode, "outcome", outcome);
		this.meterRegistry.counter("chat.turns", tags).increment();
		Timer.builder("chat.turn.processing")
			.tags(tags)
			.register(this.meterRegistry)
			.record(Duration.between(startedAt, this.clock.instant()));
	}

}
