# Testes de carga (JMeter)

Smoke, stress e spike contra as duas variantes, sob a **mesma carga** (modelo aberto: chegadas
por segundo, independentes da resposta), com SLOs e um resumo lado a lado.
Design: `docs/superpowers/specs/2026-09-26-jmeter-load-tests-design.md`.

## Rodar

```bash
loadtest/run.sh smoke            # as duas variantes, ~15 min
loadtest/run.sh stress queue     # só uma variante
TIME_SCALE=10 loadtest/run.sh spike   # perfil 10x mais curto (validar o ferramental)
```

Pré-requisitos: Docker, `jq`, `curl`. `run.sh` primeiro derruba qualquer stack `sync`/`queue` já
em execução (M8) — cada variante precisa subir do zero (Postgres e Redis sem volume). Cada
variante é aquecida por 60s (descartados), testada e derrubada. Se `run.sh` sair com erro, ele
imprime o caminho do diretório de resultados parciais dessa execução. Saída em
`loadtest/results/<data>-<teste>/`:

| Arquivo | Conteúdo |
|---|---|
| `summary.md` | Veredito dos SLOs, cliente e servidor, variantes lado a lado |
| `<variante>/report/index.html` | Dashboard HTML nativo do JMeter |
| `<variante>/results.jtl` | Todos os samples (CSV separado por TAB) |
| `<variante>/metrics.json` | Métricas do Prometheus na janela do teste |

O código de saída é 1 se algum SLO de smoke ou spike reprovar.

## O que é uma "chegada"

Uma conversa: `POST /conversations` e 5 turnos, com 1s de pausa entre eles. Cada turno é
`POST` da mensagem + polling em `GET /messages/{id}` a cada 500ms até `DONE`/`FAILED` (no `sync`
o POST já volta pronto). O sample **`turno`** mede do POST ao resultado final — é a métrica
comparável entre as variantes. Erros ficam no sample `turno-resultado`: `FAILED`, `TIMEOUT`
(excedeu `turn_timeout_s`, 120s por padrão, **ou** um timeout de leitura do cliente no `POST`/`GET`)
ou `HTTP <código>`.

## Perfis (`tests/*.env`, taxas em conversas/s; turnos/s = 5 ×)

| Teste | Carga | Duração |
|---|---|---|
| smoke | 0,1/s | 2 min |
| stress | degraus 0,5 → 1 → 1,5 → 2 → 3 → 4, cada um com 30s de rampa + 3 min de patamar | ~21 min |
| spike | 0,5/s por 3 min → 4/s por 1 min → 0,5/s por 6 min | ~10 min |

Toda execução termina com 180s de pausa sem chegadas: sem ela, o JMeter interromperia as
conversas em andamento. Só entram nas estatísticas turnos iniciados antes dessa pausa.

## SLOs (`slo.env`)

| Teste | Critério |
|---|---|
| smoke | 0 erros; p95 do turno ≤ 12s; ≥ 30 turnos medidos |
| stress | Sem reprovação: reporta a **capacidade sustentável** — último degrau com p95 ≤ 15s e erros < 1% antes do primeiro que falha |
| spike | Erros < 1%; p95 (janela de 30s) volta a ≤ 15s em até 120s após o pico; trabalho pendente (fila na `queue`, turnos em andamento no `sync`) volta ao nível pré-pico em até 180s |

## Editar o plano

`plan.jmx` abre na GUI de um JMeter 5.6.3 local (`jmeter -t loadtest/plan.jmx`). Parâmetros vêm
de propriedades `-J` (`host`, `port`, `schedule`, `turns`, `think_ms`, `poll_ms`,
`turn_timeout_s`, `post_timeout_ms`, `poll_timeout_ms`); para rodar pela GUI, defina
`-Jhost=localhost`. `post_timeout_ms` (timeout do `POST` mensagem) tem por padrão o mesmo
orçamento do turno inteiro (`turn_timeout_s * 1000`); `poll_timeout_ms` (timeout do `GET status`,
padrão 10000ms) é independente. Um timeout de leitura do cliente conta como `TIMEOUT`, não `HTTP`.

## Testes do ferramental

```bash
bash loadtest/test/test.sh   # sem Docker
```

## Limitações

- No Mac, JMeter e aplicação dividem a VM do Docker Desktop.
- O polling de 500ms soma até 0,5s ao turno da `queue`.
- Métricas de CPU/throttling dependem do cAdvisor (ver "Limitações conhecidas" no README raiz); sem elas o resumo mostra `n/d`.
- Turnos órfãos em `PROCESSING` (sem reaper) aparecem como `TIMEOUT`.
