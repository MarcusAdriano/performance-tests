package dev.perf.chat.conversation;

/**
 * How a freshly inserted PENDING turn gets processed. sync: inline in the request
 * thread; api: published to RabbitMQ for the worker.
 */
public interface TurnDispatcher {

	/** @return the turn state to report to the client */
	ChatTurn dispatch(ChatTurn pendingTurn);

}
