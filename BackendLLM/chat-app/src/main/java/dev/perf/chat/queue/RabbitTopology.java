package dev.perf.chat.queue;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * chat.turns (direct) -> chat.turns.process; rejected messages dead-letter to
 * chat.turns.dlq. No x-max-length: the backlog is unbounded on purpose, so you can watch
 * it grow during a spike. Declared by RabbitAdmin on first connection.
 */
@Configuration(proxyBeanMethods = false)
@Profile({ "api", "worker" })
public class RabbitTopology {

	public static final String EXCHANGE = "chat.turns";

	public static final String QUEUE = "chat.turns.process";

	public static final String ROUTING_KEY = "process";

	public static final String DLX = "chat.turns.dlx";

	public static final String DLQ = "chat.turns.dlq";

	@Bean
	DirectExchange turnsExchange() {
		return new DirectExchange(EXCHANGE);
	}

	@Bean
	DirectExchange deadLetterExchange() {
		return new DirectExchange(DLX);
	}

	@Bean
	Queue processQueue() {
		return QueueBuilder.durable(QUEUE).deadLetterExchange(DLX).deadLetterRoutingKey(DLQ).build();
	}

	@Bean
	Queue deadLetterQueue() {
		return QueueBuilder.durable(DLQ).build();
	}

	@Bean
	Binding processBinding(Queue processQueue, DirectExchange turnsExchange) {
		return BindingBuilder.bind(processQueue).to(turnsExchange).with(ROUTING_KEY);
	}

	@Bean
	Binding deadLetterBinding(Queue deadLetterQueue, DirectExchange deadLetterExchange) {
		return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(DLQ);
	}

}
