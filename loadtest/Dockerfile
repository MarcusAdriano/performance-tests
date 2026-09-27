# JMeter só executa aqui; o plan.jmx pode ser editado na GUI de um JMeter local.
FROM eclipse-temurin:21-jre

ARG JMETER_VERSION=5.6.3
ARG JMETER_SHA512=5978a1a35edb5a7d428e270564ff49d2b1b257a65e17a759d259a9283fc17093e522fe46f474a043864aea6910683486340706d745fcdf3db1505fd71e689083

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
	&& curl -fsSL -o /tmp/jmeter.tgz "https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-${JMETER_VERSION}.tgz" \
	&& echo "${JMETER_SHA512}  /tmp/jmeter.tgz" | sha512sum -c - \
	&& tar -xzf /tmp/jmeter.tgz -C /opt && mv /opt/apache-jmeter-${JMETER_VERSION} /opt/jmeter && rm /tmp/jmeter.tgz

ENV PATH=/opt/jmeter/bin:$PATH \
	JVM_ARGS="-Xms1g -Xmx1g"
WORKDIR /loadtest
ENTRYPOINT ["jmeter"]
