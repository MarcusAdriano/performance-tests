# Testes de carga JMeter — Design

- **Data:** 2026-09-26
- **Status:** aprovado em brainstorming, aguardando revisão do spec
- **Antecede:** `2026-09-26-backend-llm-design.md` (§13 deixou os planos JMeter para este spec)

## 1. Objetivo

Rodar **smoke, stress e spike** com JMeter contra as duas variantes do backend (`sync` e `queue`) sob **a mesma carga**, avaliar cada execução contra SLOs e gerar um **resumo comparativo lado a lado**, combinando a visão do cliente (JMeter) com a do servidor (Prometheus).

## 2. Decisões

| Tema | Decisão | Motivo |
|---|---|---|
| Propósito | Comparativo com SLOs de aprovação/reprovação | Comparar arquiteturas com critério objetivo |
| Modelo de carga | **Aberto** (taxa de chegada), *Open Model Thread Group* | As duas variantes recebem exatamente a mesma chegada de trabalho; no modelo fechado a variante lenta receberia menos carga |
| Unidade de chegada | 1 chegada = 1 conversa de **5 turnos**, **1s** de pausa entre turnos | Exercita a memória Redis (janela cresce a cada turno); turnos/s = 5 × conversas/s |
| Fonte dos números | `.jtl` do JMeter (cliente) + API do Prometheus existente (servidor) | Sem infraestrutura nova; SLOs de drenagem precisam do lado do servidor |
| Execução | JMeter 5.6.3 **em container** na rede do compose; orquestração e resumo em **Bash** (`jq`/`awk`/`curl`) | Evita o *port-forward* do Docker Desktop, que distorce latência sob centenas de conexões; reproduzível |

Se o cálculo em `awk` passar de ~50 linhas num único script, essa parte migra para Python (só stdlib). Decisão tomada na implementação, sem mudar interfaces.

## 3. Layout

```
loadtest/
├── Dockerfile                    # JMeter 5.6.3 sobre eclipse-temurin:21-jre
├── .dockerignore                 # contexto de build = só o Dockerfile
├── user.properties               # formato do .jtl (TAB) e saída forçada da JVM
├── plan.jmx                      # plano único, parametrizado por -J
├── data/cities.csv               # conteúdo das mensagens
├── tests/{smoke,stress,spike}.env
├── slo.env
├── run.sh                        # orquestração
├── summarize.sh                  # results → summary.md + exit code
├── lib/
│   ├── schedule.sh               # tests/*.env → string de schedule + janelas de fase
│   ├── jtl.sh                    # filtros, percentis, janelas
│   └── prom.sh                   # consultas PromQL
├── test/                         # test.sh + um *_test.sh por componente
├── results/                      # gitignored
└── README.md
```

Alterações fora de `loadtest/`:

- `docker-compose.yml`: serviço `jmeter` (profile `loadtest`, build `./loadtest`, sem limite de recursos, volume `./loadtest:/loadtest`).
- `.gitignore`: `loadtest/results/`.
- `README.md` raiz: seção "Testes de carga" apontando para `loadtest/README.md`.
- Spec anterior §13: remove "Planos JMeter" do fora de escopo, com referência a este spec.

## 4. Plano JMeter (`plan.jmx`)

```
Test Plan
├── HTTP Request Defaults  host=${__P(host)} port=${__P(port,8080)}
│     response timeout ${__P(post_timeout_ms,turn_timeout_s*1000)}
├── HTTP Header Manager    Content-Type: application/json
├── CSV Data Set           data/cities.csv → ${city}
├── Thread Group "marca-inicio"  1 thread, 1 loop, sem ramp-up
│   └── JSR223 Sampler "inicio"  (no-op, sucesso; marca o início do schedule — ver t0 em §6)
└── Open Model Thread Group  schedule=${__P(schedule)}
    ├── POST /conversations                          → JSON Extractor conversationId
    └── Loop ${__P(turns,5)}
        ├── Transaction Controller "turno" (generate parent sample)
        │   ├── POST /conversations/${conversationId}/messages {"content":"${city}"}
        │   │     → extrai messageId, status
        │   └── While status ∉ {DONE, FAILED, HTTP_ERROR, TIMEOUT_ERROR} e decorrido < ${__P(turn_timeout_s,120)}s
        │       ├── pausa ${__P(poll_ms,500)}
        │       └── GET /messages/${messageId}      → extrai status; response timeout ${__P(poll_timeout_ms,10000)}
        │   └── JSR223 Sampler "turno-resultado": sucesso se status == DONE; senão falha com
        │       mensagem FAILED | TIMEOUT | HTTP <código>
        └── pausa ${__P(think_ms,1000)}
```

- Cada chegada do Open Model Thread Group executa uma iteração = uma conversa.
- A Thread Group "marca-inicio" roda em paralelo ao Open Model Thread Group desde o instante em
  que o teste começa (`serialize_threadgroups=false`): sua única amostra, `inicio`, marca o
  início real do schedule — ver definição de **t0** em §6.
- **Métrica principal: o sample pai `turno`** — do `POST` até o status final. Comparável entre variantes: no `sync` é praticamente o `POST`; na `queue` soma `POST` + espera na fila + processamento + polling.
- Samples filhos (`POST mensagem`, `GET status`) ficam no `.jtl` para análise fina.
- O Transaction Controller roda **sem** "generate parent sample" e com "include timers": grava um sample `turno` (tempo de parede, incluindo pausas do polling) depois dos filhos. Sucesso do `turno` = todos os filhos com sucesso.
- **Timeouts (I2):** `post_timeout_ms` (POST mensagem, herdado do HTTP Request Defaults) tem por
  padrão o mesmo orçamento que um turno inteiro (`turn_timeout_s * 1000`), em vez de um valor fixo
  menor que o da `queue` — sem isso o `sync` estourava por `SocketTimeoutException` em turnos que a
  `queue` ainda completaria como `DONE`. `poll_timeout_ms` (GET status, padrão 10000ms) é
  independente. `-Jturn_timeout_s` sozinho já ajusta `post_timeout_ms`; passar `-Jpost_timeout_ms`
  explicitamente o desacopla.
- **Classificação de erro** na `responseMessage` do sample `turno-resultado` (a do `turno` é o texto fixo do Transaction Controller): `FAILED` (turno terminou em FAILED), `TIMEOUT` (excedeu `turn_timeout_s` **ou** um `POST`/`GET` sofreu timeout de leitura do cliente — `SocketTimeoutException` —, o que os pós-processadores "erro HTTP" tratam como `TIMEOUT_ERROR`, não `HTTP_ERROR`), `HTTP <código>` (qualquer outra resposta não 2xx ou erro de conexão). Em erro HTTP ou timeout de cliente o turno é encerrado e a conversa segue para o próximo turno.
- Falha em `POST /conversations`: `conversationId` recebe um UUID inexistente, os 5 turnos recebem `404` e contam como erro `HTTP`. Toda chegada produz exatamente 5 samples `turno`.
- Polling de 500ms introduz até 0,5s de erro de medição no `turno` da `queue` — aceito frente a turnos de 4–10s.
- `.jtl` em CSV **separado por TAB** (a mensagem do Transaction Controller contém vírgulas), com cabeçalho e os campos padrão (`timeStamp` = início do sample, em ms). Definido em `user.properties`, carregado com `-q`, que vale também para o relatório HTML.
- `user.properties` também liga `jmeterengine.force.system.exit=true`: sem isso a JVM do JMeter fica ~60s viva depois do fim (threads do pool do Open Model).

## 5. Perfis de carga

Arquivos `tests/<teste>.env` são a **fonte única**: `lib/schedule.sh` gera a string de `schedule` do JMeter e as janelas de fase usadas no resumo. Taxas em conversas/s.

| Teste | Definição | Duração | Pico (turnos/s) |
|---|---|---|---|
| smoke | `RATE=0.1`, `DURATION_S=120` | 2 min (~12 conversas / 60 turnos) | 0,5 |
| stress | `STEPS="0.5 1 1.5 2 3 4"`, `RAMP_S=30`, `HOLD_S=180` | ~21 min | 20 |
| spike | `BASE_RATE=0.5`, `BASE_S=180`, `PEAK_RATE=4`, `PEAK_S=60`, `JUMP_S=5`, `TAIL_S=360` | ~10 min | 20 |

- Stress: cada degrau = rampa de `RAMP_S` do degrau anterior (0 no primeiro) até o novo + patamar de `HOLD_S`.
- Spike: base → rampa de `JUMP_S` até o pico → pico → rampa de `JUMP_S` de volta à base → cauda.
- Pico de 4 conversas/s ≈ 3× a capacidade estimada da `queue` (≈ 50 / 7s ≈ 7 turnos/s ≈ 1,4 conversas/s), de propósito.
- **Pausa de drenagem:** ao fim do schedule, o Open Model Thread Group **interrompe** as conversas em andamento (verificado no 5.6.3). Por isso toda string de schedule termina com `pause(DRAIN_S)`, `DRAIN_S = turn_timeout_s + 60` (180s por padrão), e a análise considera **só turnos iniciados antes do fim das chegadas** (`fim principal`): todos eles terminam (DONE, FAILED ou TIMEOUT) dentro da pausa. Turnos iniciados durante a pausa são ignorados. A pausa não é encurtada por `TIME_SCALE` e sempre dura o valor inteiro.
  Pior caso de duração de um turno agora que `post_timeout_ms = turn_timeout_s * 1000` (I2a):
  `turn_timeout_s` (POST) + `poll_ms` (~0,5s) + `poll_timeout_ms` (10s, GET) + `connect_timeout`
  (5s) ≈ `turn_timeout_s` + 16s — cabe folgado na margem de 60s do `DRAIN_S`.
- `TIME_SCALE` (env, padrão `1`) divide todas as durações do perfil (não a pausa de drenagem). Serve para executar versões encurtadas durante a verificação da implementação.
- Sintaxe validada com o parser do JMeter 5.6.3: `rate(0/s) random_arrivals(30 s) rate(0.5/s) random_arrivals(180 s) rate(0.5/s) … pause(180 s)`; taxas iguais antes e depois de um `random_arrivals` = patamar, diferentes = rampa linear; decimais são aceitos.

## 6. SLOs (`slo.env`)

| Teste | Critério | Fonte |
|---|---|---|
| smoke | 0 erros de `turno`; p95 do `turno` ≤ `SMOKE_P95_S=12`; turnos medidos ≥ `SMOKE_MIN_TURNS=30` (esperados ≈ 60) | jtl |
| stress | Sem aprovação/reprovação. Reporta a **capacidade sustentável**: a taxa do último degrau aprovado antes do primeiro reprovado (`nenhum` se o primeiro reprovar). Degrau aprovado = tem turnos, p95 do `turno` ≤ `STRESS_P95_S=15` **e** erros < `STRESS_ERR_PCT=1` | jtl |
| spike | Erros < `SPIKE_ERR_PCT=1` no teste todo | jtl |
| spike | **Recuperação** ≤ `SPIKE_RECOVERY_S=120` | jtl |
| spike | **Drenagem** ≤ `SPIKE_DRAIN_S=180` | Prometheus |

Definições precisas:

- **t0** = `timeStamp` da amostra `inicio` (marca o início real do schedule, gravada pela Thread
  Group "marca-inicio" — ver §4). O modelo aberto gera chegadas aleatórias, então a primeira
  chegada de verdade pode ficar dezenas de segundos depois do início do schedule; sem a marca,
  `t0` (e todas as janelas de fase derivadas dele) ficaria deslocado por esse δ, diferente por
  variante e por execução. Na ausência da amostra `inicio` (`.jtl` antigo, gerado antes desta
  marca), `t0` cai para o menor `timeStamp` do arquivo — critério anterior. Todas as estatísticas
  usam turnos com início em [t0, t0 + fim principal). Fases e degraus são janelas relativas a t0,
  derivadas de `tests/<teste>.env` (com `TIME_SCALE`).
- **Atribuição a degrau (stress):** um `turno` pertence ao degrau em cujo **patamar** o seu `timeStamp` (início) cai. Turnos iniciados em rampas não entram nas linhas por degrau (entram nos totais).
- **Recuperação (spike):** janelas de 30s sobre os `turno` agrupados por instante de término (`timeStamp + elapsed`), avançando de 5s em 5s a partir do fim do pico. Tempo de recuperação = início da primeira janela a partir da qual **todas** as janelas seguintes até o fim do teste têm p95 ≤ `SPIKE_P95_S=15`, menos o instante de fim do pico. Se não recuperar, o SLO falha e o valor é reportado como `> TAIL_S`.
- **Drenagem (spike):** métrica de trabalho pendente — `queue`: `sum(rabbitmq_queue_messages_ready{queue="chat.turns.process"})`; `sync`: `sum(chat_turns_inflight{mode="sync"})`. Início do pico = início da rampa de subida. Linha de base = máximo da métrica nos 60s antes do início do pico, **sem passar de t0** (M5: em
`TIME_SCALE` grande o início do pico fica a menos de 60s de t0, e sem esse limite a janela vazaria
para dados de aquecimento/pré-teste) (0 se não houver pontos). Tempo de drenagem = primeiro instante após o fim do pico em que a métrica fica ≤ linha de base, menos o fim do pico. Consulta via `query_range` com `step=5s`. Sem nenhum ponto da métrica → `n/d` e o SLO **reprova** (não há como comprovar).

`run.sh` sai com código ≠ 0 se qualquer SLO de smoke ou spike falhar em qualquer variante executada.

## 7. Orquestração (`run.sh`)

```
loadtest/run.sh <smoke|stress|spike> [sync|queue|both]    # padrão: both
```

Para cada variante, em sequência:

1. `docker compose --profile <v> down` e `docker compose --profile <v> up -d --build --wait`. Postgres e Redis não têm volume: cada execução parte de estado zerado.
2. Espera `GET :8080/actuator/health` responder `UP` (até 180s), já que as apps Java não têm healthcheck no compose.
3. **Aquecimento:** o mesmo plano com `rate(0.2/s)` por `WARMUP_S` (60s) + `pause(30 s)`; resultados em `warmup/`, ignorados no resumo.
4. `docker compose --profile <v> --profile loadtest run --rm --user <uid:gid> jmeter -n -t /loadtest/plan.jmx -q /loadtest/user.properties -Jhost=<chat-sync|chat-api> -Jschedule=… -l …/results.jtl -e -o …/report -j …/jmeter.log`.
5. Espera 10s (um scrape a mais), consulta o Prometheus (`lib/prom.sh`) na janela [t0 − 60s, agora], acrescenta reinícios/OOM (`docker inspect`) e grava `metrics.json`.
6. `docker compose --profile <v> down`.

Depois das variantes: `summarize.sh <dir-da-execução>` gera `summary.md` e define o código de saída.

Saída em `loadtest/results/<AAAAMMDD-HHMMSS>-<teste>/`:

```
summary.md
<variante>/results.jtl
<variante>/report/          # dashboard HTML nativo do JMeter
<variante>/metrics.json
<variante>/jmeter.log
<variante>/warmup/
```

Falha operacional (stack não fica saudável, JMeter sai com erro, Prometheus inacessível) interrompe com mensagem clara e código ≠ 0, sempre tentando o `down` da variante.

## 8. Resumo (`summary.md`)

Colunas = variantes executadas. Seções:

1. **Veredito:** cada SLO do teste com valor medido, limite e ✅/❌ (stress: capacidade sustentável por variante).
2. **Cliente (jtl):** `turno` p50/p95/p99/máx, taxa de erro com quebra por tipo (`FAILED`/`TIMEOUT`/`HTTP`), turnos concluídos/s; no stress, uma tabela por degrau (taxa alvo, turnos/s concluídos, p95, erros %).
3. **Servidor (Prometheus, diagnóstico — fora dos SLOs exceto drenagem):**
   - pico de backlog `rabbitmq_queue_messages_ready` (`queue`) e de `chat_turns_inflight`;
   - p95 da espera na fila (`chat_turn_queue_wait_seconds{mode="worker"}` — a tag `mode` é o `APP_MODE` de quem processa, `sync` ou `worker`);
   - % de períodos de CPU com *throttling* por container (cAdvisor);
   - pico de `hikaricp_connections_pending`;
   - heap máximo usado vs máximo;
   - reinícios de container / OOM (via `docker inspect` ao fim do teste).
   Métrica sem dados (ex.: cAdvisor no Docker Desktop com containerd) aparece como `n/d`, sem falhar o resumo.

## 9. Imagem JMeter

`loadtest/Dockerfile`: `eclipse-temurin:21-jre`, baixa e verifica (SHA-512) o tarball oficial do Apache JMeter 5.6.3, `ENTRYPOINT ["jmeter"]`, `JVM_ARGS` com heap de 1 GB. Sem limite de recursos no compose, como o resto da infraestrutura. O mesmo `plan.jmx` abre na GUI do JMeter local para edição.

## 10. Testes do ferramental

- **Unitários (sem Docker), `loadtest/test/test.sh`** (roda `test/*_test.sh`):
  - `lib/schedule.sh`: strings de schedule e janelas de fase para os três perfis, com e sem `TIME_SCALE`.
  - `lib/jtl.sh`: percentis, filtro do `turno`, atribuição a degraus com exclusão de rampas, classificação de erros, janelas de recuperação — sobre `.jtl` sintéticos com resultado conhecido.
  - `summarize.sh`: com `.jtl` + `metrics.json` fixos, verifica o conteúdo do veredito e o código de saída (aprovado e reprovado).
- **Aceitação:** `loadtest/run.sh smoke both` contra a stack real passa nas duas variantes.
- **Fumaça dos perfis longos:** `TIME_SCALE=10 loadtest/run.sh spike both` completa e gera `summary.md` com todas as seções (SLOs podem falhar nessa escala; o que se verifica é o ferramental). Stress e spike em escala real não fazem parte da verificação da implementação.

## 11. Limitações conhecidas

- No Mac, JMeter e aplicação dividem a mesma VM do Docker Desktop; o JMeter é leve (centenas de threads no pico), mas compete por CPU com a infraestrutura.
- Polling de 500ms adiciona até 0,5s ao `turno` da `queue`.
- Cada execução termina com a pausa de drenagem inteira (180s), mesmo quando as conversas acabam antes.
- Turnos órfãos em `PROCESSING` (sem reaper, ver spec anterior) aparecem como `TIMEOUT`.

## 12. Fora de escopo

- Backend Listener do JMeter / InfluxDB (avaliado: descartado em favor de jtl + Prometheus).
- Execução distribuída do JMeter, CI, testes de *soak*.
- Ajuste de gargalos do backend — este ciclo entrega a ferramenta de medição.
