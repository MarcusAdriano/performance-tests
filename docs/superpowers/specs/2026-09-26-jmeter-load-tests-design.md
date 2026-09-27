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
├── test/                         # fixtures + test.sh
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
├── HTTP Header Manager    Content-Type: application/json
├── CSV Data Set           data/cities.csv → ${city}
└── Open Model Thread Group  schedule=${__P(schedule)}
    ├── POST /conversations                          → JSON Extractor conversationId
    └── Loop ${__P(turns,5)}
        ├── Transaction Controller "turno" (generate parent sample)
        │   ├── POST /conversations/${conversationId}/messages {"content":"${city}"}
        │   │     → extrai messageId, status; response timeout ${__P(post_timeout_ms,60000)}
        │   └── While status ∉ {DONE, FAILED} e decorrido < ${__P(turn_timeout_s,120)}s
        │       ├── pausa ${__P(poll_ms,500)}
        │       └── GET /messages/${messageId}      → extrai status
        │   (verificação final: status == DONE; senão falha com mensagem FAILED | TIMEOUT)
        └── pausa ${__P(think_ms,1000)}
```

- Cada chegada do Open Model Thread Group executa uma iteração = uma conversa.
- **Métrica principal: o sample pai `turno`** — do `POST` até o status final. Comparável entre variantes: no `sync` é praticamente o `POST`; na `queue` soma `POST` + espera na fila + processamento + polling.
- Samples filhos (`POST mensagem`, `GET status`) ficam no `.jtl` para análise fina.
- **Classificação de erro do `turno`** (em `responseMessage`): `FAILED` (turno terminou em FAILED), `TIMEOUT` (excedeu `turn_timeout_s`), `HTTP <código>` (qualquer resposta não 2xx ou erro de conexão). Em erro HTTP o turno é abortado e a conversa segue para o próximo turno.
- Polling de 500ms introduz até 0,5s de erro de medição no `turno` da `queue` — aceito frente a turnos de 4–10s.
- `.jtl` em CSV com cabeçalho e os campos padrão (`timeStamp` = início do sample, em ms).

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
- `TIME_SCALE` (env, padrão `1`) divide todas as durações. Serve para executar versões encurtadas durante a verificação da implementação.
- A sintaxe exata do `schedule` (rampas, degraus) é validada contra o JMeter 5.6.3 na implementação; o contrato é o comportamento acima.

## 6. SLOs (`slo.env`)

| Teste | Critério | Fonte |
|---|---|---|
| smoke | 0 erros de `turno`; p95 do `turno` ≤ `SMOKE_P95_S=12`; nº de turnos concluídos = conversas × 5 | jtl |
| stress | Sem aprovação/reprovação. Reporta a **capacidade sustentável**: maior degrau com p95 do `turno` ≤ `STRESS_P95_S=15` **e** erros < `STRESS_ERR_PCT=1` | jtl |
| spike | Erros < `SPIKE_ERR_PCT=1` no teste todo | jtl |
| spike | **Recuperação** ≤ `SPIKE_RECOVERY_S=120` | jtl |
| spike | **Drenagem** ≤ `SPIKE_DRAIN_S=180` | Prometheus |

Definições precisas:

- **t0** = menor `timeStamp` do `.jtl` do teste. Fases e degraus são janelas relativas a t0, derivadas de `tests/<teste>.env` (com `TIME_SCALE`).
- **Atribuição a degrau (stress):** um `turno` pertence ao degrau em cujo **patamar** o seu `timeStamp` (início) cai. Turnos iniciados em rampas não entram nas linhas por degrau (entram nos totais).
- **Recuperação (spike):** janelas de 30s sobre os `turno` agrupados por instante de término (`timeStamp + elapsed`), avançando de 5s em 5s a partir do fim do pico. Tempo de recuperação = início da primeira janela a partir da qual **todas** as janelas seguintes até o fim do teste têm p95 ≤ `SPIKE_P95_S=15`, menos o instante de fim do pico. Se não recuperar, o SLO falha e o valor é reportado como `> TAIL_S`.
- **Drenagem (spike):** métrica de trabalho pendente — `queue`: `sum(rabbitmq_queue_messages_ready{queue="chat.turns.process"})`; `sync`: `sum(chat_turns_inflight{mode="sync"})`. Linha de base = máximo da métrica nos 60s antes do início do pico. Tempo de drenagem = primeiro instante após o fim do pico em que a métrica fica ≤ linha de base, menos o fim do pico. Consulta via `query_range` com `step=5s`.

`run.sh` sai com código ≠ 0 se qualquer SLO de smoke ou spike falhar em qualquer variante executada.

## 7. Orquestração (`run.sh`)

```
loadtest/run.sh <smoke|stress|spike> [sync|queue|both]    # padrão: both
```

Para cada variante, em sequência:

1. `docker compose --profile <v> down` e `docker compose --profile <v> up -d --build --wait`. Postgres e Redis não têm volume: cada execução parte de estado zerado.
2. **Aquecimento:** o mesmo plano com `rate(0.2/s)` por 60s; resultados em `warmup/`, ignorados no resumo.
3. `docker compose --profile loadtest run --rm jmeter -n -t /loadtest/plan.jmx -Jhost=<chat-sync|chat-api> -Jschedule=… -l …/results.jtl -e -o …/report -j …/jmeter.log`.
4. Consulta o Prometheus (`lib/prom.sh`) na janela [t0 − 60s, fim + 60s] e grava `metrics.json`.
5. `docker compose --profile <v> down`.

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
   - p95 da espera na fila (`chat_turn_queue_wait_seconds`, `queue`);
   - % de períodos de CPU com *throttling* por container (cAdvisor);
   - pico de `hikaricp_connections_pending`;
   - heap máximo usado vs máximo;
   - reinícios de container / OOM (via `docker inspect` ao fim do teste).
   Métrica sem dados (ex.: cAdvisor no Docker Desktop com containerd) aparece como `n/d`, sem falhar o resumo.

## 9. Imagem JMeter

`loadtest/Dockerfile`: `eclipse-temurin:21-jre`, baixa e verifica (SHA-512) o tarball oficial do Apache JMeter 5.6.3, `ENTRYPOINT ["jmeter"]`, `JVM_ARGS` com heap de 1 GB. Sem limite de recursos no compose, como o resto da infraestrutura. O mesmo `plan.jmx` abre na GUI do JMeter local para edição.

## 10. Testes do ferramental

- **Unitários (sem Docker), `loadtest/test/test.sh`:**
  - `lib/schedule.sh`: strings de schedule e janelas de fase para os três perfis, com e sem `TIME_SCALE`.
  - `lib/jtl.sh`: percentis, filtro do `turno`, atribuição a degraus com exclusão de rampas, classificação de erros, janelas de recuperação — sobre `.jtl` sintéticos com resultado conhecido.
  - `summarize.sh`: com `.jtl` + `metrics.json` fixos, verifica o conteúdo do veredito e o código de saída (aprovado e reprovado).
- **Aceitação:** `loadtest/run.sh smoke both` contra a stack real passa nas duas variantes.
- **Fumaça dos perfis longos:** `TIME_SCALE=10 loadtest/run.sh spike both` completa e gera `summary.md` com todas as seções (SLOs podem falhar nessa escala; o que se verifica é o ferramental). Stress e spike em escala real não fazem parte da verificação da implementação.

## 11. Limitações conhecidas

- No Mac, JMeter e aplicação dividem a mesma VM do Docker Desktop; o JMeter é leve (centenas de threads no pico), mas compete por CPU com a infraestrutura.
- Polling de 500ms adiciona até 0,5s ao `turno` da `queue`.
- Turnos órfãos em `PROCESSING` (sem reaper, ver spec anterior) aparecem como `TIMEOUT`.

## 12. Fora de escopo

- Backend Listener do JMeter / InfluxDB (avaliado: descartado em favor de jtl + Prometheus).
- Execução distribuída do JMeter, CI, testes de *soak*.
- Ajuste de gargalos do backend — este ciclo entrega a ferramenta de medição.
