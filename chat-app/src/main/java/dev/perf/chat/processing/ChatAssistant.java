package dev.perf.chat.processing;

import java.util.UUID;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * One call = LLM (asks for the tool) -> @Tool -> LLM (answers with the tool result).
 * The ChatClient runs that loop itself; the memory advisor loads/stores the last 20
 * messages of the conversation in Redis.
 */
@Component
@Profile({ "sync", "worker" })
public class ChatAssistant {

	private final ChatClient chatClient;

	public ChatAssistant(ChatClient.Builder builder, ChatMemory chatMemory, WeatherTools weatherTools) {
		this.chatClient = builder
			.defaultSystem("Você é um assistente de previsão do tempo. Use a ferramenta disponível para responder.")
			.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
			.defaultTools(weatherTools)
			.build();
	}

	public String reply(UUID conversationId, String userMessage) {
		return this.chatClient.prompt()
			.user(userMessage)
			.advisors((advisor) -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId.toString()))
			.call()
			.content();
	}

}
