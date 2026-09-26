package dev.perf.chat.processing;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Local, fast and deterministic on purpose: the cost of the tool in each turn is the
 * extra LLM round-trip, not the tool itself.
 */
@Component
@Profile({ "sync", "worker" })
public class WeatherTools {

	@Tool(description = "Consulta a previsão do tempo atual de uma cidade")
	public String consultarPrevisaoTempo(@ToolParam(description = "Nome da cidade") String cidade) {
		int temperatura = 15 + Math.floorMod(cidade.hashCode(), 15);
		return "Previsão para %s: %d°C, céu parcialmente nublado".formatted(cidade, temperatura);
	}

}
