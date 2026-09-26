package dev.perf.chat.queue;

import java.util.UUID;

import dev.perf.chat.processing.ConversationProcessor;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Consumers = WORKER_CONCURRENCY, prefetch 1 (spring.rabbitmq.listener.simple.*), each on
 * a virtual thread (spring.threads.virtual.enabled). Business/LLM errors are handled by
 * the processor (turn FAILED, message acked); infrastructure errors propagate and the
 * message is rejected without requeue -> chat.turns.dlq.
 */
@Component
@Profile("worker")
public class TurnListener {

	private final ConversationProcessor processor;

	public TurnListener(ConversationProcessor processor) {
		this.processor = processor;
	}

	@RabbitListener(queues = RabbitTopology.QUEUE)
	public void onTurn(String turnId) {
		this.processor.process(UUID.fromString(turnId));
	}

}
