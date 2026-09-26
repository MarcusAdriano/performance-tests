package dev.perf.chat.processing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WeatherToolsTest {

	private final WeatherTools tools = new WeatherTools();

	@Test
	void returnsDeterministicForecastForCity() {
		String forecast = this.tools.consultarPrevisaoTempo("Recife");

		assertThat(forecast).startsWith("Previsão para Recife: ").endsWith("°C, céu parcialmente nublado");
		assertThat(this.tools.consultarPrevisaoTempo("Recife")).isEqualTo(forecast);
	}

}
