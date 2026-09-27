# Testes de carga JMeter — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rodar smoke, stress e spike com JMeter contra as variantes `sync` e `queue` sob a mesma carga, avaliar SLOs e gerar um resumo comparativo lado a lado.

**Architecture:** Um único `plan.jmx` (Open Model Thread Group, 1 chegada = 1 conversa de 5 turnos) executado num container JMeter na rede do compose. `run.sh` orquestra cada variante do zero (subir → aquecer → testar → coletar Prometheus → derrubar); `summarize.sh` cruza o `.jtl` com o `metrics.json` e escreve `summary.md` com ✅/❌. Toda a lógica de cálculo fica em `lib/*.sh` (Bash 3.2 + awk BWK + jq), testada sem Docker.

**Tech Stack:** Apache JMeter 5.6.3 (imagem própria sobre `eclipse-temurin:21-jre`), Bash 3.2 (o `bash` do macOS), awk (BWK/macOS e gawk/mawk), jq ≥ 1.6, curl, Docker Compose, Prometheus HTTP API.

**Spec:** `docs/superpowers/specs/2026-09-26-jmeter-load-tests-design.md`

## Global Constraints

- JMeter **5.6.3**; imagem com SHA-512 verificado do tarball oficial.
- Scripts em `#!/usr/bin/env bash` compatíveis com **Bash 3.2**: sem arrays associativos, sem `mapfile`, sem `${var,,}`.
- awk compatível com o **awk do macOS (BWK)**: sem `asort`, `gensub`, `strftime`; ternário dentro de `print` sempre entre parênteses.
- `.jtl` em CSV **separado por TAB**, com cabeçalho; colunas localizadas pelo nome do cabeçalho, nunca pela posição.
- Toda string de schedule termina com `pause(DRAIN_S)`, `DRAIN_S = TURN_TIMEOUT_S + 60`; estatísticas usam só turnos com início em `[t0, t0 + fim principal)`.
- Os perfis `tests/*.env` são a única fonte das fases: `run.sh` e `summarize.sh` derivam tudo de `lib/schedule.sh`.
- Limites de SLO só em `slo.env`: `SMOKE_P95_S=12`, `SMOKE_MIN_TURNS=30`, `STRESS_P95_S=15`, `STRESS_ERR_PCT=1`, `SPIKE_ERR_PCT=1`, `SPIKE_P95_S=15`, `SPIKE_RECOVERY_S=120`, `SPIKE_DRAIN_S=180`.
- Métrica ausente no Prometheus → `null` no `metrics.json` → `n/d` no resumo, sem derrubar o script; única exceção: drenagem sem dados reprova o SLO.
- Tag `mode` das métricas `chat_*` = `APP_MODE` de quem processa: `sync` ou `worker` (nunca `queue`).
- Textos voltados ao usuário (README, resumo, mensagens) em português; identificadores de código em inglês ou português conforme já estão neste plano.

## Review Focus

1. **Conversa que não foi criada** (`POST /conversations` falha): os 5 turnos devem aparecer como erro `HTTP` (404), não sumir. → Task 4, Step 4 (o `curl` confirma que o UUID padrão do extractor recebe `404`) e Task 3 (`erros por tipo` classifica `HTTP 404` como `HTTP`).
2. **Turno preso em `PROCESSING`/`PENDING`** (worker morto, sem reaper): deve virar `TIMEOUT` após `turn_timeout_s`, e o JMeter não pode ficar preso. → Task 4, Step 4 (`-Jturn_timeout_s=1` força TIMEOUT na `queue`); classificação testada no Task 3.
3. **Janela sem turnos** (degrau do stress vazio, smoke sem nenhuma resposta): o resumo mostra `n/d`/`0` e reprova, sem divisão por zero nem erro de aritmética do Bash. → Task 5 (`smoke sem turnos`, `stress: degrau vazio`).
4. **Prometheus sem a série** (cAdvisor sem containers no Docker Desktop com containerd, variante `sync` sem RabbitMQ): `null` → `n/d`, resumo gerado. → Task 3 (`prom sem série`, NaN) e Task 5 (`smoke: n/d sem cAdvisor`, `spike: drenagem n/d`).
5. **Sistema que nunca se recupera nem drena** depois do spike: o SLO reprova com `> <cauda> s`, e o script sai com 1. → Task 5 (`spike sem recuperar sai com 1`, `spike: não drenou`).

---

## Estrutura de arquivos

| Arquivo | Responsabilidade |
|---|---|
| `loadtest/Dockerfile`, `loadtest/.dockerignore` | Imagem do JMeter 5.6.3 (só executa) |
| `loadtest/user.properties` | `.jtl` com TAB + cabeçalho; saída forçada da JVM |
| `loadtest/plan.jmx`, `loadtest/data/cities.csv` | O plano: conversa de N turnos, polling, classificação do turno |
| `loadtest/tests/{smoke,stress,spike}.env` | Perfis de carga |
| `loadtest/slo.env` | Limites dos SLOs |
| `loadtest/lib/schedule.sh` | Perfil → fases, fim principal, string de schedule |
| `loadtest/lib/jtl.sh` | `.jtl` → t0, turnos por janela, erros por tipo, percentis, recuperação |
| `loadtest/lib/prom.sh` | Consultas ao Prometheus → `metrics.json` |
| `loadtest/summarize.sh` | Resultados → `summary.md` + código de saída |
| `loadtest/run.sh` | Orquestração ponta a ponta |
| `loadtest/test/test.sh` + `test/*_test.sh` | Testes sem Docker |
| `loadtest/README.md` | Uso, perfis, SLOs, leitura do resumo |
| `docker-compose.yml`, `.gitignore`, `README.md`, spec anterior | Integração e documentação |

---

### Task 1: Imagem do JMeter e serviço no compose

**Files:**
- Create: `loadtest/Dockerfile`, `loadtest/.dockerignore`, `loadtest/user.properties`
- Modify: `docker-compose.yml` (novo serviço `jmeter` no fim), `.gitignore` (criar se não existir)

**Interfaces:**
- Produces: serviço compose `jmeter` (profile `loadtest`, `./loadtest` montado em `/loadtest`, entrypoint `jmeter`); `/loadtest/user.properties` para `-q`.

- [ ] **Step 1: Verificar que o serviço ainda não existe**

Run: `docker compose --profile loadtest config --services | grep -x jmeter`
Expected: nenhuma saída, código 1.

- [ ] **Step 2: Criar `loadtest/Dockerfile`**

```dockerfile
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
```

- [ ] **Step 3: Criar `loadtest/.dockerignore`** (o contexto de build não deve carregar `results/`)

```
*
!Dockerfile
```

- [ ] **Step 4: Criar `loadtest/user.properties`**

```properties
# Lido com -q por run.sh; vale também para o relatório HTML (-e -o).
# Tab como separador: mensagens do Transaction Controller contêm vírgulas.
jmeter.save.saveservice.output_format=csv
jmeter.save.saveservice.default_delimiter=\t
jmeter.save.saveservice.print_field_names=true
jmeter.save.saveservice.response_message=true
jmeter.save.saveservice.thread_counts=true
jmeterengine.force.system.exit=true
```

- [ ] **Step 5: Adicionar o serviço ao fim de `docker-compose.yml`** (depois do serviço `grafana`, mesma indentação dos outros serviços)

```yaml

  # --- Load generator (see loadtest/run.sh) -----------------------------------
  # docker compose --profile loadtest run --rm jmeter -n -t /loadtest/plan.jmx ...
  jmeter:
    build: ./loadtest
    image: backendllm/jmeter:5.6.3
    profiles: [loadtest]
    volumes:
      - ./loadtest:/loadtest
```

- [ ] **Step 6: Ignorar resultados no git** — acrescentar ao `.gitignore` da raiz (criar se não existir):

```
loadtest/results/
```

- [ ] **Step 7: Build e verificação**

Run: `docker compose --profile loadtest build jmeter && docker compose --profile loadtest run --rm jmeter --version 2>&1 | grep -o '5\.6\.3' | head -1`
Expected: `5.6.3` (o build falha se o SHA-512 não conferir).

- [ ] **Step 8: Commit**

```bash
git add loadtest/Dockerfile loadtest/.dockerignore loadtest/user.properties docker-compose.yml .gitignore
git commit -m "feat(loadtest): JMeter 5.6.3 image and compose service"
```

---

### Task 2: Perfis de carga e `lib/schedule.sh`

**Files:**
- Create: `loadtest/test/test.sh`, `loadtest/test/schedule_test.sh`, `loadtest/tests/smoke.env`, `loadtest/tests/stress.env`, `loadtest/tests/spike.env`, `loadtest/lib/schedule.sh`

**Interfaces:**
- Produces (em `lib/schedule.sh`, para Tasks 5 e 6):
  - `sched_phases <profile.env> [time_scale]` → linhas `nome\tinício_s\tfim_s\ttaxa_ini\ttaxa_fim`; nomes: `hold` (constant); `ramp1 hold1 … rampN holdN` (steps); `base jump_up peak jump_down tail` (spike). Perfil desconhecido → stderr + código 1.
  - `sched_main_end <profile.env> [time_scale]` → fim da última fase (s).
  - `sched_phase <profile.env> <time_scale> <nome>` → `"início_s fim_s"`; nome inexistente → código 1.
  - `sched_string <profile.env> <time_scale> <drain_s>` → string do Open Model Thread Group terminando em `pause(<drain_s> s)`.
- Produces (em `test/test.sh`, para Tasks 3 e 5): `assert_eq <nome> <esperado> <obtido>`, `assert_contains <nome> <trecho> <arquivo>`, `mk_jtl <arquivo>` (stdin: `início_ms elapsed_ms mensagem thread`), `$LT`, `$TMP`.

- [ ] **Step 1: Criar o runner `loadtest/test/test.sh`** (e `chmod +x`)

```bash
#!/usr/bin/env bash
# Testes do ferramental, sem Docker: bash loadtest/test/test.sh
# Roda todos os test/*_test.sh no mesmo shell (compartilham assert_* e mk_jtl).
set -uo pipefail
LT=$(cd "$(dirname "$0")/.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
PASS=0 FAILS=0

assert_eq() { # assert_eq <nome> <esperado> <obtido>
	if [[ $2 == "$3" ]]; then PASS=$((PASS + 1)); else
		FAILS=$((FAILS + 1)); printf 'FAIL %s\n  esperado: %s\n  obtido:   %s\n' "$1" "$2" "$3"
	fi
}
assert_contains() { # assert_contains <nome> <trecho> <arquivo>
	if grep -qF -- "$2" "$3"; then PASS=$((PASS + 1)); else
		FAILS=$((FAILS + 1)); printf 'FAIL %s\n  trecho ausente: %s\n' "$1" "$2"; fi
}

# mk_jtl <arquivo> ; stdin: "início_ms elapsed_ms mensagem thread" por turno (mensagem DONE = sucesso).
# Grava como o plan.jmx: um "POST conversa" em t0=1000000, depois turno-resultado e turno de cada turno.
mk_jtl() {
	awk 'BEGIN { OFS = "\t"; print "timeStamp", "elapsed", "label", "responseCode", "responseMessage", "threadName", "success"
		print 1000000, 5, "POST conversa", 201, "Created", "c 1-0", "true" }
		{ ok = ($3 == "DONE") ? "true" : "false"
		  print $1 + $2, 0, "turno-resultado", (ok == "true" ? 200 : 500), $3, $4, ok
		  print $1, $2, "turno", "", "Number of samples in transaction : 2, number of failing samples : 0", $4, ok }' >"$1"
}

for f in "$LT"/test/*_test.sh; do
	source "$f"
done

echo "$PASS ok, $FAILS falha(s)"
((FAILS == 0))
```

- [ ] **Step 2: Escrever o teste `loadtest/test/schedule_test.sh`**

```bash
source "$LT/lib/schedule.sh"

# --- schedule -----------------------------------------------------------------
assert_eq "smoke schedule" "rate(0.1/s) random_arrivals(120 s) rate(0.1/s) pause(180 s)" \
	"$(sched_string "$LT/tests/smoke.env" 1 180)"
assert_eq "stress: 12 fases" 12 "$(sched_phases "$LT/tests/stress.env" 1 | wc -l | tr -d ' ')"
assert_eq "stress: fim" 1260 "$(sched_main_end "$LT/tests/stress.env" 1)"
assert_eq "stress: 1º patamar" "$(printf 'hold1\t30\t210\t0.5\t0.5')" "$(sched_phases "$LT/tests/stress.env" 1 | sed -n 2p)"
assert_eq "stress: começa em 0 com rampa" "rate(0/s) random_arrivals(30 s) rate(0.5/s) random_arrivals(180 s) rate(0.5/s)" \
	"$(sched_string "$LT/tests/stress.env" 1 0 | cut -d' ' -f1-7)"
assert_eq "spike TIME_SCALE=10" \
	"rate(0.5/s) random_arrivals(18 s) rate(0.5/s) random_arrivals(0.5 s) rate(4/s) random_arrivals(6 s) rate(4/s) random_arrivals(0.5 s) rate(0.5/s) random_arrivals(36 s) rate(0.5/s) pause(180 s)" \
	"$(sched_string "$LT/tests/spike.env" 10 180)"
assert_eq "spike: fase peak" "185 245" "$(sched_phase "$LT/tests/spike.env" 1 peak)"
printf 'PROFILE=zigzag\n' >"$TMP/bad.env"
sched_phases "$TMP/bad.env" 1 >/dev/null 2>&1
assert_eq "perfil desconhecido falha" 1 "$?"
sched_phase "$LT/tests/spike.env" 1 nope >/dev/null 2>&1
assert_eq "fase inexistente falha" 1 "$?"

```

- [ ] **Step 3: Rodar e ver falhar**

Run: `bash loadtest/test/test.sh`
Expected: FAIL — `lib/schedule.sh: No such file or directory` e falhas `smoke schedule`, `stress: 12 fases` etc.

- [ ] **Step 4: Criar os perfis**

`loadtest/tests/smoke.env`:
```bash
# Taxas em conversas/s; durações em segundos (divididas por TIME_SCALE).
PROFILE=constant
RATE=0.1
DURATION_S=120
```

`loadtest/tests/stress.env`:
```bash
# Degraus: rampa de RAMP_S do degrau anterior (0 no primeiro) até o novo, depois patamar de HOLD_S.
PROFILE=steps
STEPS="0.5 1 1.5 2 3 4"
RAMP_S=30
HOLD_S=180
```

`loadtest/tests/spike.env`:
```bash
# base -> rampa JUMP_S -> pico -> rampa JUMP_S -> cauda na taxa base.
PROFILE=spike
BASE_RATE=0.5
BASE_S=180
PEAK_RATE=4
PEAK_S=60
JUMP_S=5
TAIL_S=360
```

- [ ] **Step 5: Implementar `loadtest/lib/schedule.sh`**

```bash
# Perfis de carga (tests/*.env) -> fases e string de schedule do Open Model Thread Group.
# Uso: source lib/schedule.sh

# sched_phases <profile.env> [time_scale]
# Uma linha por fase, em ordem: nome<TAB>início_s<TAB>fim_s<TAB>taxa_inicial<TAB>taxa_final
# (segundos relativos ao início do teste; taxas em conversas/s).
sched_phases() {
	local env_file=$1 scale=${2:-1}
	(
		# shellcheck disable=SC1090
		source "$env_file"
		case $PROFILE in
		constant) echo "hold $RATE $RATE $DURATION_S" ;;
		steps)
			local prev=0 i=1 r
			for r in $STEPS; do
				echo "ramp$i $prev $r $RAMP_S"
				echo "hold$i $r $r $HOLD_S"
				prev=$r i=$((i + 1))
			done
			;;
		spike)
			echo "base $BASE_RATE $BASE_RATE $BASE_S"
			echo "jump_up $BASE_RATE $PEAK_RATE $JUMP_S"
			echo "peak $PEAK_RATE $PEAK_RATE $PEAK_S"
			echo "jump_down $PEAK_RATE $BASE_RATE $JUMP_S"
			echo "tail $BASE_RATE $BASE_RATE $TAIL_S"
			;;
		*) echo "PROFILE desconhecido em $env_file: '$PROFILE'" >&2; exit 1 ;;
		esac
	) | awk -v scale="$scale" 'BEGIN { OFS = "\t"; t = 0 }
		{ d = $4 / scale; printf "%s\t%g\t%g\t%g\t%g\n", $1, t, t + d, $2, $3; t += d }'
}

# sched_main_end <profile.env> [time_scale] -> fim da parte com chegadas, em segundos.
sched_main_end() {
	sched_phases "$@" | awk -F'\t' 'END { print $3 }'
}

# sched_phase <profile.env> <time_scale> <nome> -> "início_s fim_s" da fase.
sched_phase() {
	sched_phases "$1" "$2" | awk -F'\t' -v n="$3" '$1 == n { print $2, $3; found = 1 }
		END { if (!found) { print "fase inexistente: " n > "/dev/stderr"; exit 1 } }'
}

# sched_string <profile.env> <time_scale> <drain_s>
# O pause final mantém o JMeter vivo até as conversas em andamento terminarem: ao fim do
# schedule o Open Model Thread Group interrompe as threads que ainda estiverem rodando.
sched_string() {
	sched_phases "$1" "$2" | awk -F'\t' -v drain="$3" '
		NR == 1 { s = sprintf("rate(%s/s)", $4) }
		{ s = s sprintf(" random_arrivals(%s s) rate(%s/s)", $3 - $2, $5) }
		END { printf "%s pause(%s s)\n", s, drain }'
}
```

- [ ] **Step 6: Rodar e ver passar**

Run: `bash loadtest/test/test.sh`
Expected: `9 ok, 0 falha(s)`.

- [ ] **Step 7: Conferir a sintaxe com o parser do próprio JMeter** (a string gerada precisa ser aceita pelo 5.6.3)

Run:
```bash
source loadtest/lib/schedule.sh
docker compose --profile loadtest run --rm --entrypoint sh jmeter -c \
  "echo 'println new org.apache.jmeter.threads.openmodel.ScheduleParser(\"$(sched_string loadtest/tests/stress.env 1 180)\").parse().steps.size()' > /tmp/p.groovy && java -cp '/opt/jmeter/lib/*:/opt/jmeter/lib/ext/*' groovy.ui.GroovyMain /tmp/p.groovy"
```
Expected: `30` (1 rate inicial + 12 × (arrivals + rate) = 25; o parser expande o `pause` em mais 5 etapas internas). Uma string inválida faz o parser lançar `ParserException`.

- [ ] **Step 8: Commit**

```bash
git add loadtest/test/test.sh loadtest/test/schedule_test.sh loadtest/tests loadtest/lib/schedule.sh
git commit -m "feat(loadtest): load profiles and schedule generation"
```

---

### Task 3: `lib/jtl.sh` e `lib/prom.sh`

**Files:**
- Create: `loadtest/test/jtl_test.sh`, `loadtest/test/prom_test.sh`, `loadtest/lib/jtl.sh`, `loadtest/lib/prom.sh`

**Interfaces:**
- Consumes: `assert_eq`, `mk_jtl`, `$TMP` (Task 2).
- Produces (`lib/jtl.sh`, para Tasks 5 e 6):
  - `jtl_t0 <jtl>` → menor `timeStamp` (ms).
  - `jtl_turns <jtl> <from_ms> <to_ms>` → `início\telapsed\tsucesso(1|0)` dos samples `turno` com início em `[from, to)`.
  - `jtl_errors <jtl> <from_ms> <to_ms>` → exatamente três linhas `FAILED n`, `TIMEOUT n`, `HTTP n` (mensagens `HTTP …` agrupadas).
  - `turn_stats` (stdin = saída de `jtl_turns`) → `n erros p50 p95 p99 max` em ms, ou `0 0 - - - -`.
  - `jtl_recovery_s <jtl> <peak_end_ms> <main_end_ms> <p95_limit_ms>` → segundos ou `-1`.
- Produces (`lib/prom.sh`, para Task 6): `PROM_URL` (padrão `http://localhost:9090`), `prom_values` (stdin JSON da API → `[[ts,valor],…]`), `prom_range <q> <start_s> <end_s>`, `prom_instant <q> <time_s>` (número ou `null`), `prom_collect <sync|queue> <start_s> <end_s>` → objeto com as chaves `pending backlog_max inflight_max hikari_pending_max heap_used_max_mb heap_max_mb throttled_pct queue_wait_p95_s`.

- [ ] **Step 1: Escrever `loadtest/test/jtl_test.sh`**

```bash
source "$LT/lib/jtl.sh"

# --- turn_stats ---------------------------------------------------------------
assert_eq "percentis 1..100" "100 0 50 95 99 100" \
	"$(for i in $(seq 100 -1 1); do printf '0\t%d\t1\n' "$i"; done | turn_stats)"
assert_eq "percentis com 1 amostra" "1 1 7 7 7 7" "$(printf '0\t7\t0\n' | turn_stats)"
assert_eq "sem turnos" "0 0 - - - -" "$(printf '' | turn_stats)"

# --- jtl ----------------------------------------------------------------------
mk_jtl "$TMP/a.jtl" <<'T'
1000100 4000 DONE c1-1
1000200 9000 FAILED c1-2
1000300 120000 TIMEOUT c1-3
1000400 50 HTTP 404 c1-4
1000500 60000 HTTP Non HTTP response code: java.net.SocketTimeoutException c1-5
1005000 5000 DONE c1-1
T
# mensagens com espaço: mk_jtl usa só $3, então reescreve as linhas HTTP com a mensagem inteira
awk -F'\t' 'BEGIN { OFS = "\t" } $6 == "c1-4" && $3 == "turno-resultado" { $5 = "HTTP 404" }
	$6 == "c1-5" && $3 == "turno-resultado" { $5 = "HTTP Non HTTP response code: java.net.SocketTimeoutException" } { print }' \
	"$TMP/a.jtl" >"$TMP/a2.jtl"
assert_eq "t0" 1000000 "$(jtl_t0 "$TMP/a2.jtl")"
assert_eq "turnos na janela [from,to)" "2" "$(jtl_turns "$TMP/a2.jtl" 1000200 1000400 | wc -l | tr -d ' ')"
assert_eq "erros por tipo" "$(printf 'FAILED 1\nTIMEOUT 1\nHTTP 2')" "$(jtl_errors "$TMP/a2.jtl" 0 2000000)"
assert_eq "erros respeitam a janela" "$(printf 'FAILED 0\nTIMEOUT 1\nHTTP 2')" "$(jtl_errors "$TMP/a2.jtl" 1000300 2000000)"

# Recuperação: fim do pico em 100 s; turnos lentos (20 s) terminam até 130 s, depois só rápidos.
{
	for t in $(seq 80000 1000 109000); do echo "$t 20000 DONE r"; done
	for t in $(seq 129000 1000 299000); do echo "$t 1000 DONE r"; done
} | mk_jtl "$TMP/rec.jtl"
assert_eq "recupera 30 s após o pico" 30 "$(jtl_recovery_s "$TMP/rec.jtl" 100000 300000 15000)"
seq 80000 1000 299000 | awk '{ print $1, 20000, "DONE", "r" }' | mk_jtl "$TMP/norec.jtl"
assert_eq "não recupera" -1 "$(jtl_recovery_s "$TMP/norec.jtl" 100000 300000 15000)"

```

- [ ] **Step 2: Escrever `loadtest/test/prom_test.sh`**

```bash
source "$LT/lib/prom.sh"

# --- prom ---------------------------------------------------------------------
assert_eq "prom range com NaN" "[[1700000000,3],[1700000010,0]]" "$(echo \
	'{"data":{"result":[{"values":[[1700000000.1,"3"],[1700000005,"NaN"],[1700000010,"0"]]}]}}' | prom_values)"
assert_eq "prom instant" "[[1700000000,12.5]]" "$(echo '{"data":{"result":[{"value":[1700000000,"12.5"]}]}}' | prom_values)"
assert_eq "prom sem série" "[]" "$(echo '{"data":{"result":[]}}' | prom_values)"

```

- [ ] **Step 3: Rodar e ver falhar**

Run: `bash loadtest/test/test.sh`
Expected: FAIL — `turn_stats: command not found`, `prom_values: command not found`; os 9 testes da Task 2 seguem passando.

- [ ] **Step 4: Implementar `loadtest/lib/jtl.sh`**

Notas para quem implementa: o Transaction Controller grava os filhos **antes** do sample `turno`; por isso `jtl_errors` guarda a mensagem do `turno-resultado` por thread e a conta quando o `turno` da mesma thread chega (é o `turno` que tem o início do turno). Percentil = posto mais próximo.

```bash
# Leitura do .jtl (CSV separado por TAB, com cabeçalho; ver user.properties).
# Tempos em ms epoch. Uso: source lib/jtl.sh

# _jtl_rows <jtl> <label> -> "timeStamp elapsed success(1|0) responseMessage" das linhas com o label.
_jtl_rows() {
	awk -F'\t' -v label="$2" '
		NR == 1 { for (i = 1; i <= NF; i++) col[$i] = i; next }
		$col["label"] == label {
			print $col["timeStamp"] "\t" $col["elapsed"] "\t" ($col["success"] == "true" ? 1 : 0) "\t" $col["responseMessage"]
		}' "$1"
}

# jtl_t0 <jtl> -> menor timeStamp (início do teste).
jtl_t0() {
	awk -F'\t' 'NR == 1 { for (i = 1; i <= NF; i++) if ($i == "timeStamp") c = i; next }
		min == "" || $c < min { min = $c } END { print min }' "$1"
}

# jtl_turns <jtl> <from_ms> <to_ms> -> "início elapsed sucesso" dos turnos iniciados em [from, to).
jtl_turns() {
	_jtl_rows "$1" turno | awk -F'\t' -v from="$2" -v to="$3" \
		'$1 >= from && $1 < to { print $1 "\t" $2 "\t" $3 }'
}

# jtl_errors <jtl> <from_ms> <to_ms> -> "FAILED n", "TIMEOUT n", "HTTP n" (sempre as três linhas),
# a partir do sample turno-resultado dos turnos iniciados em [from, to).
# O turno-resultado é gravado no fim do turno; o início do turno = timeStamp + elapsed - duração do turno,
# então o filtro usa o sample "turno" correspondente: mesma thread, gravado logo em seguida.
jtl_errors() {
	awk -F'\t' -v from="$2" -v to="$3" '
		NR == 1 { for (i = 1; i <= NF; i++) col[$i] = i; next }
		{ th = $col["threadName"] }
		$col["label"] == "turno-resultado" && $col["success"] != "true" { pending[th] = $col["responseMessage"] }
		$col["label"] == "turno" {
			if ((th in pending) && $col["timeStamp"] >= from && $col["timeStamp"] < to) {
				m = pending[th]; t = (m ~ /^HTTP/) ? "HTTP" : m
				n[t]++
			}
			delete pending[th]
		}
		END { printf "FAILED %d\nTIMEOUT %d\nHTTP %d\n", n["FAILED"], n["TIMEOUT"], n["HTTP"] }' "$1"
}

# turn_stats (stdin: saída de jtl_turns) -> "n erros p50 p95 p99 max" (ms; "-" sem turnos).
# Percentil por posto mais próximo: o menor valor com pelo menos p% das amostras <= ele.
turn_stats() {
	sort -t$'\t' -k2,2n | awk -F'\t' '
		{ v[++n] = $2; if ($3 == 0) err++ }
		function pct(p,   i) { i = int(p * n / 100); if (i < p * n / 100) i++; return v[i < 1 ? 1 : i] }
		END {
			if (n == 0) { print "0 0 - - - -"; exit }
			print n, err + 0, pct(50), pct(95), pct(99), v[n]
		}'
}

# jtl_recovery_s <jtl> <peak_end_ms> <main_end_ms> <p95_limit_ms>
# Janelas de 30s (turnos agrupados pelo instante de término), avançando 5s a partir do fim do pico.
# Imprime os segundos até o início da primeira janela a partir da qual todas têm p95 <= limite,
# ou -1 se a última janela ainda estiver acima. Janela sem turnos não reprova.
jtl_recovery_s() {
	local jtl=$1 peak_end=$2 main_end=$3 limit=$4 w recovered=-1 p95
	local ends
	ends=$(_jtl_rows "$jtl" turno | awk -F'\t' '{ print $1 + $2 "\t" $2 }')
	for ((w = peak_end; w + 30000 <= main_end; w += 5000)); do
		p95=$(printf '%s\n' "$ends" | awk -F'\t' -v a="$w" -v b="$((w + 30000))" \
			'$1 >= a && $1 < b { print "0\t" $2 "\t1" }' | turn_stats | awk '{ print $4 }')
		if [[ $p95 == - || $p95 -le $limit ]]; then
			((recovered < 0)) && recovered=$(((w - peak_end) / 1000))
		else
			recovered=-1
		fi
	done
	echo "$recovered"
}
```

- [ ] **Step 5: Implementar `loadtest/lib/prom.sh`**

```bash
# Consultas ao Prometheus na janela do teste. Uso: source lib/prom.sh
# PROM_URL (padrão http://localhost:9090). Sem dados -> null (vira "n/d" no resumo).

PROM_URL=${PROM_URL:-http://localhost:9090}

# prom_values: resposta JSON da API (stdin) -> [[ts, valor], ...] da primeira série
# (as consultas agregam com sum/max, então há no máximo uma). Instant query -> um único par.
prom_values() {
	jq -c '[.data.result[0] // {} | (.values // [.value // empty])[]
		| [(.[0] | floor), (.[1] | if . == "NaN" or . == "+Inf" or . == "-Inf" then null else tonumber end)]
		| select(.[1] != null)]'
}

# prom_range <query> <start_s> <end_s> -> [[ts, valor], ...] com passo de 5s.
prom_range() {
	curl -fsS --get "$PROM_URL/api/v1/query_range" --data-urlencode "query=$1" \
		--data-urlencode "start=$2" --data-urlencode "end=$3" --data-urlencode "step=5" | prom_values
}

# prom_instant <query> <time_s> -> número ou null.
prom_instant() {
	curl -fsS --get "$PROM_URL/api/v1/query" --data-urlencode "query=$1" \
		--data-urlencode "time=$2" | prom_values | jq '.[0][1] // null'
}

# prom_collect <sync|queue> <start_s> <end_s> -> objeto JSON gravado em metrics.json.
# "pending" é a métrica de trabalho pendente usada no SLO de drenagem do spike.
prom_collect() {
	local variant=$1 start=$2 end=$3 range=$(($3 - $2)) pending backlog='[]' wait_p95=null
	local chat='name=~"chat-.*"'
	if [[ $variant == queue ]]; then
		pending='sum(rabbitmq_queue_messages_ready{queue="chat.turns.process"})'
		backlog=$(prom_range "$pending" "$start" "$end")
		wait_p95=$(prom_instant "histogram_quantile(0.95, sum by (le) (increase(chat_turn_queue_wait_seconds_bucket{mode=\"worker\"}[${range}s])))" "$end")
	else
		pending='sum(chat_turns_inflight{mode="sync"})'
	fi
	jq -n \
		--argjson pending "$(prom_range "$pending" "$start" "$end")" \
		--argjson backlog "$backlog" \
		--argjson inflight "$(prom_range 'sum(chat_turns_inflight)' "$start" "$end")" \
		--argjson hikari "$(prom_range 'sum(hikaricp_connections_pending)' "$start" "$end")" \
		--argjson heap "$(prom_range 'max(sum by (application) (jvm_memory_used_bytes{area="heap"}))' "$start" "$end")" \
		--argjson heap_max "$(prom_instant 'max(sum by (application) (jvm_memory_max_bytes{area="heap"}))' "$end")" \
		--argjson throttled "$(prom_instant "100 * sum(increase(container_cpu_cfs_throttled_periods_total{$chat}[${range}s])) / sum(increase(container_cpu_cfs_periods_total{$chat}[${range}s]))" "$end")" \
		--argjson wait_p95 "$wait_p95" '
		def peak: if length == 0 then null else (map(.[1]) | max) end;
		{
			pending: $pending,
			backlog_max: ($backlog | peak),
			inflight_max: ($inflight | peak),
			hikari_pending_max: ($hikari | peak),
			heap_used_max_mb: ($heap | peak | if . == null then null else (. / 1048576 | floor) end),
			heap_max_mb: (if $heap_max == null then null else ($heap_max / 1048576 | floor) end),
			throttled_pct: $throttled,
			queue_wait_p95_s: $wait_p95
		}'
}
```

- [ ] **Step 6: Rodar e ver passar**

Run: `bash loadtest/test/test.sh`
Expected: `21 ok, 0 falha(s)`.

- [ ] **Step 7: Commit**

```bash
git add loadtest/test/jtl_test.sh loadtest/test/prom_test.sh loadtest/lib/jtl.sh loadtest/lib/prom.sh
git commit -m "feat(loadtest): jtl statistics and Prometheus queries"
```

---

### Task 4: O plano JMeter

**Files:**
- Create: `loadtest/plan.jmx`, `loadtest/data/cities.csv`

**Interfaces:**
- Consumes: serviço `jmeter` e `user.properties` (Task 1).
- Produces (para Task 6): propriedades `-J` `host`, `port` (8080), `schedule`, `turns` (5), `think_ms` (1000), `poll_ms` (500), `turn_timeout_s` (120), `post_timeout_ms` (60000); labels no `.jtl`: `POST conversa`, `POST mensagem`, `GET status`, `turno-resultado`, `turno`.

- [ ] **Step 1: Criar `loadtest/data/cities.csv`**

```
city
Recife
Natal
São Paulo
Porto Alegre
Manaus
Belém
Curitiba
Salvador
```

- [ ] **Step 2: Criar `loadtest/plan.jmx`**

Pontos que não podem mudar: Transaction Controller com `parent=false` e `includeTimers=true`; os dois `JSR223PostProcessor` "erro HTTP" ficam **depois** do JSON Extractor de cada sampler (sobrescrevem o `status`); o `defaultValues` do `conversationId` é um UUID inexistente.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<jmeterTestPlan version="1.2" properties="5.0" jmeter="5.6.3">
  <hashTree>
    <TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="chat-llm">
      <stringProp name="TestPlan.comments">Uma chegada = uma conversa de ${turns} turnos. Parâmetros via -J (ver loadtest/README.md).</stringProp>
      <boolProp name="TestPlan.functional_mode">false</boolProp>
      <boolProp name="TestPlan.serialize_threadgroups">false</boolProp>
      <elementProp name="TestPlan.user_defined_variables" elementType="Arguments" guiclass="ArgumentsPanel" testclass="Arguments" testname="User Defined Variables">
        <collectionProp name="Arguments.arguments"/>
      </elementProp>
    </TestPlan>
    <hashTree>
      <ConfigTestElement guiclass="HttpDefaultsGui" testclass="ConfigTestElement" testname="HTTP defaults">
        <elementProp name="HTTPsampler.Arguments" elementType="Arguments" guiclass="HTTPArgumentsPanel" testclass="Arguments" testname="User Defined Variables">
          <collectionProp name="Arguments.arguments"/>
        </elementProp>
        <stringProp name="HTTPSampler.domain">${__P(host,localhost)}</stringProp>
        <stringProp name="HTTPSampler.port">${__P(port,8080)}</stringProp>
        <stringProp name="HTTPSampler.protocol">http</stringProp>
        <stringProp name="HTTPSampler.implementation">HttpClient4</stringProp>
        <stringProp name="HTTPSampler.connect_timeout">5000</stringProp>
        <stringProp name="HTTPSampler.response_timeout">${__P(post_timeout_ms,60000)}</stringProp>
      </ConfigTestElement>
      <hashTree/>
      <HeaderManager guiclass="HeaderPanel" testclass="HeaderManager" testname="JSON headers">
        <collectionProp name="HeaderManager.headers">
          <elementProp name="" elementType="Header">
            <stringProp name="Header.name">Content-Type</stringProp>
            <stringProp name="Header.value">application/json</stringProp>
          </elementProp>
        </collectionProp>
      </HeaderManager>
      <hashTree/>
      <CSVDataSet guiclass="TestBeanGUI" testclass="CSVDataSet" testname="cidades">
        <stringProp name="filename">data/cities.csv</stringProp>
        <stringProp name="fileEncoding">UTF-8</stringProp>
        <stringProp name="variableNames"></stringProp>
        <boolProp name="ignoreFirstLine">false</boolProp>
        <stringProp name="delimiter">,</stringProp>
        <boolProp name="quotedData">false</boolProp>
        <boolProp name="recycle">true</boolProp>
        <boolProp name="stopThread">false</boolProp>
        <stringProp name="shareMode">shareMode.all</stringProp>
      </CSVDataSet>
      <hashTree/>
      <OpenModelThreadGroup guiclass="OpenModelThreadGroupGui" testclass="OpenModelThreadGroup" testname="conversas">
        <elementProp name="ThreadGroup.main_controller" elementType="OpenModelThreadGroupController"/>
        <stringProp name="ThreadGroup.on_sample_error">continue</stringProp>
        <stringProp name="OpenModelThreadGroup.schedule">${__P(schedule,rate(0.1/s) random_arrivals(10 s) rate(0.1/s))}</stringProp>
        <stringProp name="OpenModelThreadGroup.random_seed"></stringProp>
      </OpenModelThreadGroup>
      <hashTree>
        <HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="POST conversa">
          <stringProp name="HTTPSampler.path">/conversations</stringProp>
          <stringProp name="HTTPSampler.method">POST</stringProp>
          <boolProp name="HTTPSampler.use_keepalive">true</boolProp>
          <elementProp name="HTTPsampler.Arguments" elementType="Arguments" guiclass="HTTPArgumentsPanel" testclass="Arguments" testname="User Defined Variables">
            <collectionProp name="Arguments.arguments"/>
          </elementProp>
        </HTTPSamplerProxy>
        <hashTree>
          <JSONPostProcessor guiclass="JSONPostProcessorGui" testclass="JSONPostProcessor" testname="conversationId">
            <stringProp name="JSONPostProcessor.referenceNames">conversationId</stringProp>
            <stringProp name="JSONPostProcessor.jsonPathExprs">$.conversationId</stringProp>
            <stringProp name="JSONPostProcessor.match_numbers">1</stringProp>
            <!-- Conversa não criada: os turnos vão para um id inexistente, recebem 404 e contam como erro HTTP. -->
            <stringProp name="JSONPostProcessor.defaultValues">00000000-0000-0000-0000-000000000000</stringProp>
          </JSONPostProcessor>
          <hashTree/>
        </hashTree>
        <LoopController guiclass="LoopControlPanel" testclass="LoopController" testname="turnos">
          <stringProp name="LoopController.loops">${__P(turns,5)}</stringProp>
          <boolProp name="LoopController.continue_forever">true</boolProp>
        </LoopController>
        <hashTree>
          <TransactionController guiclass="TransactionControllerGui" testclass="TransactionController" testname="turno">
            <boolProp name="TransactionController.parent">false</boolProp>
            <!-- true: o sample "turno" mede o tempo de parede, incluindo as pausas do polling. -->
            <boolProp name="TransactionController.includeTimers">true</boolProp>
          </TransactionController>
          <hashTree>
            <HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="POST mensagem">
              <stringProp name="HTTPSampler.path">/conversations/${conversationId}/messages</stringProp>
              <stringProp name="HTTPSampler.method">POST</stringProp>
              <boolProp name="HTTPSampler.use_keepalive">true</boolProp>
              <boolProp name="HTTPSampler.postBodyRaw">true</boolProp>
              <elementProp name="HTTPsampler.Arguments" elementType="Arguments">
                <collectionProp name="Arguments.arguments">
                  <elementProp name="" elementType="HTTPArgument">
                    <boolProp name="HTTPArgument.always_encode">false</boolProp>
                    <stringProp name="Argument.value">{&quot;content&quot;:&quot;${city}&quot;}</stringProp>
                    <stringProp name="Argument.metadata">=</stringProp>
                  </elementProp>
                </collectionProp>
              </elementProp>
            </HTTPSamplerProxy>
            <hashTree>
              <JSR223PreProcessor guiclass="TestBeanGUI" testclass="JSR223PreProcessor" testname="inicia turno">
                <stringProp name="scriptLanguage">groovy</stringProp>
                <stringProp name="cacheKey">true</stringProp>
                <stringProp name="script">vars.put(&apos;status&apos;, &apos;NONE&apos;)
vars.put(&apos;http_code&apos;, &apos;&apos;)
long timeoutMs = (props.getProperty(&apos;turn_timeout_s&apos;, &apos;120&apos;) as long) * 1000
vars.put(&apos;turn_deadline&apos;, String.valueOf(System.currentTimeMillis() + timeoutMs))</stringProp>
              </JSR223PreProcessor>
              <hashTree/>
              <JSONPostProcessor guiclass="JSONPostProcessorGui" testclass="JSONPostProcessor" testname="messageId, status">
                <stringProp name="JSONPostProcessor.referenceNames">messageId;status</stringProp>
                <stringProp name="JSONPostProcessor.jsonPathExprs">$.messageId;$.status</stringProp>
                <stringProp name="JSONPostProcessor.match_numbers">1;1</stringProp>
                <stringProp name="JSONPostProcessor.defaultValues">NONE;NONE</stringProp>
              </JSONPostProcessor>
              <hashTree/>
              <JSR223PostProcessor guiclass="TestBeanGUI" testclass="JSR223PostProcessor" testname="erro HTTP">
                <stringProp name="scriptLanguage">groovy</stringProp>
                <stringProp name="cacheKey">true</stringProp>
                <stringProp name="script">if (!prev.isSuccessful()) {
    vars.put(&apos;status&apos;, &apos;HTTP_ERROR&apos;)
    vars.put(&apos;http_code&apos;, prev.getResponseCode())
}</stringProp>
              </JSR223PostProcessor>
              <hashTree/>
            </hashTree>
            <WhileController guiclass="WhileControllerGui" testclass="WhileController" testname="polling">
              <stringProp name="WhileController.condition">${__groovy(![&apos;DONE&apos;\,&apos;FAILED&apos;\,&apos;HTTP_ERROR&apos;].contains(vars.get(&apos;status&apos;)) &amp;&amp; System.currentTimeMillis() &lt; (vars.get(&apos;turn_deadline&apos;) as long))}</stringProp>
            </WhileController>
            <hashTree>
              <TestAction guiclass="TestActionGui" testclass="TestAction" testname="pausa polling">
                <intProp name="ActionProcessor.action">1</intProp>
                <intProp name="ActionProcessor.target">0</intProp>
                <stringProp name="ActionProcessor.duration">${__P(poll_ms,500)}</stringProp>
              </TestAction>
              <hashTree/>
              <HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="GET status">
                <stringProp name="HTTPSampler.path">/messages/${messageId}</stringProp>
                <stringProp name="HTTPSampler.method">GET</stringProp>
                <boolProp name="HTTPSampler.use_keepalive">true</boolProp>
                <elementProp name="HTTPsampler.Arguments" elementType="Arguments" guiclass="HTTPArgumentsPanel" testclass="Arguments" testname="User Defined Variables">
                  <collectionProp name="Arguments.arguments"/>
                </elementProp>
              </HTTPSamplerProxy>
              <hashTree>
                <JSONPostProcessor guiclass="JSONPostProcessorGui" testclass="JSONPostProcessor" testname="status">
                  <stringProp name="JSONPostProcessor.referenceNames">status</stringProp>
                  <stringProp name="JSONPostProcessor.jsonPathExprs">$.status</stringProp>
                  <stringProp name="JSONPostProcessor.match_numbers">1</stringProp>
                  <stringProp name="JSONPostProcessor.defaultValues">NONE</stringProp>
                </JSONPostProcessor>
                <hashTree/>
                <JSR223PostProcessor guiclass="TestBeanGUI" testclass="JSR223PostProcessor" testname="erro HTTP">
                  <stringProp name="scriptLanguage">groovy</stringProp>
                  <stringProp name="cacheKey">true</stringProp>
                  <stringProp name="script">if (!prev.isSuccessful()) {
    vars.put(&apos;status&apos;, &apos;HTTP_ERROR&apos;)
    vars.put(&apos;http_code&apos;, prev.getResponseCode())
}</stringProp>
                </JSR223PostProcessor>
                <hashTree/>
              </hashTree>
            </hashTree>
            <JSR223Sampler guiclass="TestBeanGUI" testclass="JSR223Sampler" testname="turno-resultado">
              <stringProp name="scriptLanguage">groovy</stringProp>
              <stringProp name="cacheKey">true</stringProp>
              <stringProp name="script">// Classifica o turno; summarize.sh agrupa os erros pelo prefixo desta mensagem.
def s = vars.get(&apos;status&apos;)
if (s == &apos;DONE&apos;) {
    SampleResult.setResponseMessage(&apos;DONE&apos;)
} else {
    SampleResult.setSuccessful(false)
    SampleResult.setResponseCode(&apos;500&apos;)
    SampleResult.setResponseMessage(s == &apos;FAILED&apos; ? &apos;FAILED&apos;
        : s == &apos;HTTP_ERROR&apos; ? &apos;HTTP &apos; + vars.get(&apos;http_code&apos;)
        : &apos;TIMEOUT&apos;)
}</stringProp>
            </JSR223Sampler>
            <hashTree/>
          </hashTree>
          <TestAction guiclass="TestActionGui" testclass="TestAction" testname="pausa leitura">
            <intProp name="ActionProcessor.action">1</intProp>
            <intProp name="ActionProcessor.target">0</intProp>
            <stringProp name="ActionProcessor.duration">${__P(think_ms,1000)}</stringProp>
          </TestAction>
          <hashTree/>
        </hashTree>
      </hashTree>
    </hashTree>
  </hashTree>
</jmeterTestPlan>
```

- [ ] **Step 3: Subir a variante `sync` e rodar 15s de carga**

Run:
```bash
docker compose --profile sync up -d --build --wait
until curl -fsS localhost:8080/actuator/health | grep -q '"status":"UP"'; do sleep 2; done
mkdir -p loadtest/results/manual-sync
docker compose --profile sync --profile loadtest run --rm --user "$(id -u):$(id -g)" jmeter \
  -n -t /loadtest/plan.jmx -q /loadtest/user.properties -Jhost=chat-sync \
  "-Jschedule=rate(0.2/s) random_arrivals(15 s) rate(0.2/s) pause(90 s)" \
  -l /loadtest/results/manual-sync/results.jtl -j /loadtest/results/manual-sync/jmeter.log
awk -F'\t' 'NR>1{print $3" "$8}' loadtest/results/manual-sync/results.jtl | sort | uniq -c
```
Expected: todas as linhas com `true`; contagem de `turno` = contagem de `turno-resultado` = 5 × `POST conversa`; nenhum `GET status` (no `sync` o POST já volta `DONE`). Com a JVM fria, os primeiros turnos podem levar ~30s — é esperado.

- [ ] **Step 4: Variante `queue`, incluindo polling e TIMEOUT**

Run:
```bash
docker compose --profile sync down
docker compose --profile queue up -d --build --wait
until curl -fsS localhost:8080/actuator/health | grep -q '"status":"UP"'; do sleep 2; done
mkdir -p loadtest/results/manual-queue loadtest/results/manual-timeout
docker compose --profile queue --profile loadtest run --rm --user "$(id -u):$(id -g)" jmeter \
  -n -t /loadtest/plan.jmx -q /loadtest/user.properties -Jhost=chat-api \
  "-Jschedule=rate(0.2/s) random_arrivals(15 s) rate(0.2/s) pause(90 s)" \
  -l /loadtest/results/manual-queue/results.jtl -j /loadtest/results/manual-queue/jmeter.log
awk -F'\t' 'NR>1{print $3" "$8}' loadtest/results/manual-queue/results.jtl | sort | uniq -c
# turno_timeout_s=1 < duração mínima de um turno (4s): todo turno tem de virar TIMEOUT
docker compose --profile queue --profile loadtest run --rm --user "$(id -u):$(id -g)" jmeter \
  -n -t /loadtest/plan.jmx -q /loadtest/user.properties -Jhost=chat-api -Jturn_timeout_s=1 -Jturns=1 \
  "-Jschedule=rate(0.2/s) random_arrivals(10 s) rate(0.2/s) pause(20 s)" \
  -l /loadtest/results/manual-timeout/results.jtl -j /loadtest/results/manual-timeout/jmeter.log
awk -F'\t' 'NR>1 && $3=="turno-resultado"{print $5}' loadtest/results/manual-timeout/results.jtl | sort | uniq -c
docker compose --profile queue down
```
Expected: na primeira execução, tudo `true`, com `GET status` presentes (polling); na segunda, só `TIMEOUT`.

Regra 1 da Review Focus (conversa não criada → 5 × `HTTP 404`): conferir no plano que o `defaultValues` do extractor `conversationId` é `00000000-0000-0000-0000-000000000000` e que `POST /conversations/00000000-0000-0000-0000-000000000000/messages` responde `404` na API (`curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' -d '{"content":"x"}' localhost:8080/conversations/00000000-0000-0000-0000-000000000000/messages` com qualquer variante no ar → `404`).

- [ ] **Step 5: Limpar e fazer commit**

```bash
rm -rf loadtest/results/manual-*
git add loadtest/plan.jmx loadtest/data/cities.csv
git commit -m "feat(loadtest): JMeter plan with open-model conversations and turn polling"
```

---

### Task 5: SLOs e `summarize.sh`

**Files:**
- Create: `loadtest/slo.env`, `loadtest/test/summarize_test.sh`, `loadtest/summarize.sh`

**Interfaces:**
- Consumes: `lib/schedule.sh` (Task 2), `lib/jtl.sh` (Task 3), `assert_*`/`mk_jtl` (Task 2).
- Produces (para Task 6): `summarize.sh <dir>` — lê `<dir>/meta.env` (`TEST`, `VARIANTS`, `TIME_SCALE`) e, por variante, `<dir>/<v>/results.jtl` e `<dir>/<v>/metrics.json` (chaves de `prom_collect` + `restarts` + `oom_killed`); escreve `<dir>/<v>/summary.env` e `<dir>/summary.md`; imprime o caminho do resumo; sai com 1 se algum SLO de smoke/spike falhar, senão 0.

- [ ] **Step 1: Escrever `loadtest/test/summarize_test.sh`**

```bash
# --- summarize ----------------------------------------------------------------
METRICS='{"pending":[],"backlog_max":null,"inflight_max":3,"hikari_pending_max":0,"heap_used_max_mb":120,
"heap_max_mb":768,"throttled_pct":null,"queue_wait_p95_s":null,"restarts":0,"oom_killed":false}'
mk_run() { # mk_run <dir> <teste> <time_scale> <variantes...> ; jtl e metrics.json copiados depois
	local d=$1 t=$2 s=$3
	shift 3
	mkdir -p "$d"
	printf 'TEST=%s\nVARIANTS="%s"\nTIME_SCALE=%s\n' "$t" "$*" "$s" >"$d/meta.env"
	for v in "$@"; do mkdir -p "$d/$v" && echo "$METRICS" >"$d/$v/metrics.json"; done
}

mk_run "$TMP/smoke-ok" smoke 1 sync queue
seq 1001000 2000 1100000 | awk '{ print $1, 5000, "DONE", "s" }' | mk_jtl "$TMP/smoke-ok/sync/results.jtl"
cp "$TMP/smoke-ok/sync/results.jtl" "$TMP/smoke-ok/queue/results.jtl"
"$LT/summarize.sh" "$TMP/smoke-ok" >/dev/null
assert_eq "smoke aprovado sai com 0" 0 "$?"
assert_contains "smoke: tabela com as duas variantes" '| SLO | `sync` | `queue` |' "$TMP/smoke-ok/summary.md"
assert_contains "smoke: erros 0 ✅" "| Erros de turno = 0 | 0 ✅ | 0 ✅ |" "$TMP/smoke-ok/summary.md"
assert_contains "smoke: n/d sem cAdvisor" "| CPU com throttling (% períodos) | n/d | n/d |" "$TMP/smoke-ok/summary.md"

mk_run "$TMP/smoke-bad" smoke 1 sync
{ seq 1001000 2000 1100000 | awk '{ print $1, 5000, "DONE", "s" }'; echo "1101000 9000 FAILED s"; } |
	mk_jtl "$TMP/smoke-bad/sync/results.jtl"
"$LT/summarize.sh" "$TMP/smoke-bad" >/dev/null
assert_eq "smoke com FAILED sai com 1" 1 "$?"
assert_contains "smoke: FAILED contado" "| ↳ FAILED | 1 |" "$TMP/smoke-bad/summary.md"

mk_run "$TMP/smoke-empty" smoke 1 sync
printf '' | mk_jtl "$TMP/smoke-empty/sync/results.jtl"
"$LT/summarize.sh" "$TMP/smoke-empty" >/dev/null
assert_eq "smoke sem turnos sai com 1" 1 "$?"
assert_contains "smoke sem turnos: p95 n/d" "| p95 do turno ≤ 12 s | n/d s ❌ |" "$TMP/smoke-empty/summary.md"

# Stress com TIME_SCALE=60: patamares em [0.5,3.5) [4,7) [7.5,10.5) [11,14) [14.5,17.5) [18,21) s.
# Degraus 1-3 rápidos, 4 lento, 5 rápido de novo, 6 vazio: capacidade = 1.5 (para no primeiro reprovado).
mk_run "$TMP/stress" stress 60 sync
{
	for s in 0.5 4 7.5 14.5; do awk -v s="$s" 'BEGIN { for (i = 0; i < 20; i++) print 1000000 + s * 1000 + i * 100, 5000, "DONE", "s" }'; done
	awk 'BEGIN { for (i = 0; i < 20; i++) print 1011000 + i * 100, 30000, "DONE", "s" }'
} | mk_jtl "$TMP/stress/sync/results.jtl"
"$LT/summarize.sh" "$TMP/stress" >/dev/null
assert_eq "stress sai com 0 (sem aprovação)" 0 "$?"
assert_contains "stress: capacidade" "| 1.5 conversas/s |" "$TMP/stress/summary.md"
assert_contains "stress: degrau 4 reprovado" "| 2 | 20 |" "$TMP/stress/summary.md"
assert_contains "stress: degrau vazio" "| 4 | 0 | 0.00 | n/d | - | ❌ |" "$TMP/stress/summary.md"

# Spike com TIME_SCALE=1: pico em [180,245) s, fim do pico 245 s, cauda até 610 s.
spike_jtl() { # spike_jtl <lento_até_s>: turnos de 5 s, exceto os que terminam entre 180 s e <lento_até_s> (40 s)
	awk -v slow="$1" 'BEGIN { for (t = 1; t < 600; t += 2) { e = (t + 40 >= 180 && t + 40 < slow) ? 40000 : 5000; print 1000000 + t * 1000, e, "DONE", "s" } }'
}
mk_run "$TMP/spike-ok" spike 1 queue
spike_jtl 260 | mk_jtl "$TMP/spike-ok/queue/results.jtl"
# backlog: 0 antes do pico, 50 no pico, zera 100 s após o fim do pico (t0 = 1000 s epoch)
jq -n "$METRICS"' | .pending = [range(1000; 1611; 5) | [., (if . >= 1180 and . < 1345 then 50 else 0 end)]]' \
	>"$TMP/spike-ok/queue/metrics.json"
"$LT/summarize.sh" "$TMP/spike-ok" >/dev/null
assert_eq "spike aprovado sai com 0" 0 "$?"
assert_contains "spike: drenagem 100 s" "| Drenagem em ≤ 180 s | 100 s ✅ |" "$TMP/spike-ok/summary.md"

mk_run "$TMP/spike-bad" spike 1 queue
spike_jtl 610 | mk_jtl "$TMP/spike-bad/queue/results.jtl"
jq -n "$METRICS"' | .pending = [range(1000; 1611; 5) | [., (if . >= 1180 then 50 else 0 end)]]' \
	>"$TMP/spike-bad/queue/metrics.json"
"$LT/summarize.sh" "$TMP/spike-bad" >/dev/null
assert_eq "spike sem recuperar sai com 1" 1 "$?"
assert_contains "spike: não recuperou" "> 360 s ❌" "$TMP/spike-bad/summary.md"
assert_contains "spike: não drenou" "| Drenagem em ≤ 180 s | > 360 s ❌ |" "$TMP/spike-bad/summary.md"

mk_run "$TMP/spike-nodata" spike 1 sync
spike_jtl 260 | mk_jtl "$TMP/spike-nodata/sync/results.jtl"
"$LT/summarize.sh" "$TMP/spike-nodata" >/dev/null
assert_eq "spike sem métrica de drenagem sai com 1" 1 "$?"
assert_contains "spike: drenagem n/d" "| Drenagem em ≤ 180 s | n/d ❌ |" "$TMP/spike-nodata/summary.md"

```

- [ ] **Step 2: Rodar e ver falhar**

Run: `bash loadtest/test/test.sh`
Expected: FAIL — `summarize.sh: No such file or directory` nos casos de summarize; os 21 anteriores passam.

- [ ] **Step 3: Criar `loadtest/slo.env`**

```bash
# Limites dos SLOs (docs/superpowers/specs/2026-09-26-jmeter-load-tests-design.md §6). Tempos em segundos.
SMOKE_P95_S=12
SMOKE_MIN_TURNS=30
STRESS_P95_S=15
STRESS_ERR_PCT=1
SPIKE_ERR_PCT=1
SPIKE_P95_S=15
SPIKE_RECOVERY_S=120
SPIKE_DRAIN_S=180
```

- [ ] **Step 4: Implementar `loadtest/summarize.sh`** (e `chmod +x`)

Notas: as células de SLO (`slo_row`) rodam no shell atual, gravando num arquivo temporário em vez de `$(...)`, para que `FAIL=1` sobreviva; `get` lê o `summary.env` da variante num subshell.

```bash
#!/usr/bin/env bash
# summarize.sh <dir-da-execução> -> <dir>/summary.md; sai com 1 se algum SLO de smoke/spike falhar.
# O diretório tem meta.env (TEST, VARIANTS, TIME_SCALE) e, por variante, results.jtl e metrics.json.
set -euo pipefail
LT=$(cd "$(dirname "$0")" && pwd)
source "$LT/lib/schedule.sh"
source "$LT/lib/jtl.sh"
source "$LT/slo.env"

DIR=${1:?uso: summarize.sh <dir-da-execução>}
source "$DIR/meta.env"
PROFILE_ENV="$LT/tests/$TEST.env"
MAIN_END_S=$(sched_main_end "$PROFILE_ENV" "$TIME_SCALE")

ms_to_s() { if [[ $1 == - ]]; then echo n/d; else awk -v x="$1" 'BEGIN { printf "%.1f", x / 1000 }'; fi; }
pct() { if (($2 == 0)); then echo -; else awk -v a="$1" -v b="$2" 'BEGIN { printf "%.1f", 100 * a / b }'; fi; }
# le <= limite (valores decimais); "-" nunca passa.
le() { [[ $1 != - ]] && awk -v a="$1" -v b="$2" 'BEGIN { exit !(a <= b) }'; }
lt() { [[ $1 != - ]] && awk -v a="$1" -v b="$2" 'BEGIN { exit !(a < b) }'; }
mark() { if "$@"; then echo ✅; else echo ❌; fi; }

# window_stats <jtl> <from_ms> <to_ms> -> "n erros p50 p95 p99 max erros_pct turnos_ok_por_s"
window_stats() {
	local s n err
	s=$(jtl_turns "$1" "$2" "$3" | turn_stats)
	read -r n err _ <<<"$s"
	echo "$s $(pct "$err" "$n") $(awk -v n="$n" -v e="$err" -v d="$(($3 - $2))" 'BEGIN { printf "%.2f", (n - e) * 1000 / d }')"
}

# drain_s <metrics.json> <peak_start_s> <peak_end_s> -> segundos, -1 (não drenou) ou "-" (sem dados).
drain_s() {
	jq -r --argjson ps "$2" --argjson pe "$3" '
		.pending as $p
		| if ($p | length) == 0 then "-" else
			([$p[] | select(.[0] >= $ps - 60 and .[0] < $ps) | .[1]] | max // 0) as $base
			| ([$p[] | select(.[0] >= $pe and .[1] <= $base) | .[0]] | min) as $t
			| if $t == null then -1 else ($t - $pe) end
		end' "$1"
}

# Calcula as métricas de cada variante em <dir>/<variante>/summary.env.
for v in $VARIANTS; do
	jtl="$DIR/$v/results.jtl"
	t0=$(jtl_t0 "$jtl")
	main_end=$(awk -v t="$t0" -v s="$MAIN_END_S" 'BEGIN { printf "%d", t + s * 1000 }')
	{
		read -r n err p50 p95 p99 max err_pct tps <<<"$(window_stats "$jtl" "$t0" "$main_end")"
		echo "N=$n ERR=$err P50=$p50 P95=$p95 P99=$p99 MAX=$max ERR_PCT=$err_pct TPS=$tps"
		jtl_errors "$jtl" "$t0" "$main_end" | awk '{ print "E_" $1 "=" $2 }'
		case $TEST in
		stress)
			i=0 capacity=nenhum failed=0
			while IFS=$'\t' read -r name start end rate _; do
				[[ $name == hold* ]] || continue
				i=$((i + 1))
				from=$(awk -v t="$t0" -v s="$start" 'BEGIN { printf "%d", t + s * 1000 }')
				to=$(awk -v t="$t0" -v s="$end" 'BEGIN { printf "%d", t + s * 1000 }')
				read -r sn serr _ sp95 _ _ serr_pct stps <<<"$(window_stats "$jtl" "$from" "$to")"
				ok=0
				((sn > 0)) && le "$sp95" "$((STRESS_P95_S * 1000))" && lt "$serr_pct" "$STRESS_ERR_PCT" && ok=1
				((ok == 1 && failed == 0)) && capacity=$rate
				((ok == 0)) && failed=1
				echo "S${i}_RATE=$rate S${i}_N=$sn S${i}_TPS=$stps S${i}_P95=$sp95 S${i}_ERR_PCT=$serr_pct S${i}_OK=$ok"
			done < <(sched_phases "$PROFILE_ENV" "$TIME_SCALE")
			echo "STEPS=$i CAPACITY=$capacity"
			;;
		spike)
			read -r ps _ <<<"$(sched_phase "$PROFILE_ENV" "$TIME_SCALE" jump_up)"
			read -r _ pe <<<"$(sched_phase "$PROFILE_ENV" "$TIME_SCALE" peak)"
			ps_ms=$(awk -v t="$t0" -v s="$ps" 'BEGIN { printf "%d", t + s * 1000 }')
			pe_ms=$(awk -v t="$t0" -v s="$pe" 'BEGIN { printf "%d", t + s * 1000 }')
			echo "RECOVERY_S=$(jtl_recovery_s "$jtl" "$pe_ms" "$main_end" "$((SPIKE_P95_S * 1000))")"
			echo "DRAIN_S=$(drain_s "$DIR/$v/metrics.json" "$((ps_ms / 1000))" "$((pe_ms / 1000))")"
			;;
		esac
		jq -r 'to_entries[] | select(.key != "pending")
			| "M_\(.key)=\(if .value == null then "n/d" else .value end)"' "$DIR/$v/metrics.json"
	} >"$DIR/$v/summary.env"
done

get() { (source "$DIR/$1/summary.env" && eval "echo \"\${$2:-n/d}\""); }
row() { # row <rótulo> <função que recebe a variante>
	local line="| $1 |" v
	for v in $VARIANTS; do line="$line $("$2" "$v") |"; done
	echo "$line"
}
header() {
	local line="| $1 |" sep="|---|" v
	for v in $VARIANTS; do line="$line \`$v\` |" sep="$sep---|"; done
	printf '%s\n%s\n' "$line" "$sep"
}

FAIL=0
check() { # check <variante> <valor exibido> <condição...>: imprime "valor ✅|❌" e acumula falhas
	local v=$1 shown=$2
	shift 2
	if "$@"; then echo "$shown ✅"; else echo "$shown ❌"; FAIL=1; fi
}

# As células de SLO rodam no shell atual (sem $(...)) para FAIL sobreviver.
slo_row() { # slo_row <rótulo> <função que imprime a célula da variante>
	local line="| $1 |" v cell
	for v in $VARIANTS; do
		"$2" "$v" >"$DIR/.cell"
		cell=$(cat "$DIR/.cell")
		line="$line $cell |"
	done
	rm -f "$DIR/.cell"
	echo "$line"
}

smoke_err() { check "$1" "$(get "$1" ERR)" test "$(get "$1" ERR)" -eq 0; }
smoke_p95() { check "$1" "$(ms_to_s "$(get "$1" P95)") s" le "$(get "$1" P95)" "$((SMOKE_P95_S * 1000))"; }
smoke_n() { check "$1" "$(get "$1" N)" test "$(get "$1" N)" -ge "$SMOKE_MIN_TURNS"; }
spike_err() { check "$1" "$(get "$1" ERR_PCT)%" lt "$(get "$1" ERR_PCT)" "$SPIKE_ERR_PCT"; }
secs_or() { case $1 in -1) echo "> $2 s" ;; -) echo n/d ;; *) echo "$1 s" ;; esac; }
spike_rec() {
	local r
	r=$(get "$1" RECOVERY_S)
	check "$1" "$(secs_or "$r" "$(sched_phase "$PROFILE_ENV" "$TIME_SCALE" tail | awk '{ print $2 - $1 }')")" \
		eval "((r >= 0 && r <= SPIKE_RECOVERY_S))"
}
spike_drain() {
	local d
	d=$(get "$1" DRAIN_S)
	check "$1" "$(secs_or "$d" "$(sched_phase "$PROFILE_ENV" "$TIME_SCALE" tail | awk '{ print $2 - $1 }')")" \
		eval "[[ $d != - ]] && ((d >= 0 && d <= SPIKE_DRAIN_S))"
}
capacity() { echo "$(get "$1" CAPACITY) conversas/s"; }

s_p() { ms_to_s "$(get "$1" "$2")"; }
c_p50() { s_p "$1" P50; }
c_p95() { s_p "$1" P95; }
c_p99() { s_p "$1" P99; }
c_max() { s_p "$1" MAX; }
c_err() { echo "$(get "$1" ERR) ($(get "$1" ERR_PCT)%)"; }
c_failed() { get "$1" E_FAILED; }
c_timeout() { get "$1" E_TIMEOUT; }
c_http() { get "$1" E_HTTP; }
c_tps() { get "$1" TPS; }
c_n() { get "$1" N; }
m() { get "$1" "M_$2"; }
c_backlog() { m "$1" backlog_max; }
c_inflight() { m "$1" inflight_max; }
c_wait() { m "$1" queue_wait_p95_s; }
c_thr() { m "$1" throttled_pct; }
c_hikari() { m "$1" hikari_pending_max; }
c_heap() { echo "$(m "$1" heap_used_max_mb) / $(m "$1" heap_max_mb) MB"; }
c_restarts() { echo "$(m "$1" restarts) / $(m "$1" oom_killed)"; }

{
	echo "# $TEST — $(basename "$DIR")"
	echo
	echo "Variantes: $VARIANTS · TIME_SCALE=$TIME_SCALE · turnos iniciados até ${MAIN_END_S}s após o início"
	echo
	echo "## Veredito"
	echo
	header SLO
	case $TEST in
	smoke)
		slo_row "Erros de turno = 0" smoke_err
		slo_row "p95 do turno ≤ ${SMOKE_P95_S} s" smoke_p95
		slo_row "Turnos medidos ≥ ${SMOKE_MIN_TURNS}" smoke_n
		;;
	stress)
		row "Capacidade sustentável (p95 ≤ ${STRESS_P95_S} s, erros < ${STRESS_ERR_PCT}%)" capacity
		;;
	spike)
		slo_row "Erros < ${SPIKE_ERR_PCT}%" spike_err
		slo_row "Recuperação do p95 (≤ ${SPIKE_P95_S} s) em ≤ ${SPIKE_RECOVERY_S} s" spike_rec
		slo_row "Drenagem em ≤ ${SPIKE_DRAIN_S} s" spike_drain
		;;
	esac
	echo
	echo "## Cliente (JMeter, sample \`turno\`)"
	echo
	header Métrica
	row "Turnos medidos" c_n
	row "p50 (s)" c_p50
	row "p95 (s)" c_p95
	row "p99 (s)" c_p99
	row "máx (s)" c_max
	row "Erros" c_err
	row "↳ FAILED" c_failed
	row "↳ TIMEOUT" c_timeout
	row "↳ HTTP" c_http
	row "Turnos OK/s" c_tps
	if [[ $TEST == stress ]]; then
		echo
		echo "### Degraus (só patamares; rampas excluídas)"
		for v in $VARIANTS; do
			echo
			echo "**\`$v\`**"
			echo
			echo "| Taxa (conversas/s) | Turnos | Turnos OK/s | p95 (s) | Erros % | Sustentável |"
			echo "|---|---|---|---|---|---|"
			for ((i = 1; i <= $(get "$v" STEPS); i++)); do
				echo "| $(get "$v" "S${i}_RATE") | $(get "$v" "S${i}_N") | $(get "$v" "S${i}_TPS") | $(ms_to_s "$(get "$v" "S${i}_P95")") | $(get "$v" "S${i}_ERR_PCT") | $([[ $(get "$v" "S${i}_OK") == 1 ]] && echo ✅ || echo ❌) |"
			done
		done
	fi
	echo
	echo "## Servidor (Prometheus, diagnóstico)"
	echo
	header Métrica
	row "Pico do backlog (\`chat.turns.process\`)" c_backlog
	row "Pico de turnos em andamento" c_inflight
	row "p95 da espera na fila (s)" c_wait
	row "CPU com throttling (% períodos)" c_thr
	row "Pico de espera no HikariCP" c_hikari
	row "Heap máximo usado / máximo" c_heap
	row "Reinícios / OOM" c_restarts
} >"$DIR/summary.md"

echo "$DIR/summary.md"
exit "$FAIL"
```

- [ ] **Step 5: Rodar e ver passar**

Run: `bash loadtest/test/test.sh`
Expected: `40 ok, 0 falha(s)`.

- [ ] **Step 6: Commit**

```bash
git add loadtest/slo.env loadtest/test/summarize_test.sh loadtest/summarize.sh
git commit -m "feat(loadtest): SLO evaluation and side-by-side summary"
```

---

### Task 6: Orquestração `run.sh` e aceitação

**Files:**
- Create: `loadtest/run.sh`

**Interfaces:**
- Consumes: tudo o que veio antes — `sched_string`, `jtl_t0`, `prom_collect`, `summarize.sh`, serviço `jmeter`, `plan.jmx`.
- Produces: `loadtest/run.sh <smoke|stress|spike> [sync|queue|both]`; env `TIME_SCALE`, `TURN_TIMEOUT_S`, `WARMUP_S`; saída em `loadtest/results/<AAAAMMDD-HHMMSS>-<teste>/`.

- [ ] **Step 1: Criar `loadtest/run.sh`** (e `chmod +x`)

```bash
#!/usr/bin/env bash
# run.sh <smoke|stress|spike> [sync|queue|both]
# Sobe cada variante do zero, aquece, roda o JMeter em container, coleta o Prometheus, derruba,
# e no fim gera results/<id>/summary.md. Sai com != 0 se algum SLO de smoke/spike falhar.
# Env: TIME_SCALE (divide as durações do perfil, padrão 1), TURN_TIMEOUT_S (120), WARMUP_S (60).
set -euo pipefail
LT=$(cd "$(dirname "$0")" && pwd)
ROOT=$(dirname "$LT")
source "$LT/lib/schedule.sh"
source "$LT/lib/jtl.sh"
source "$LT/lib/prom.sh"

TEST=${1:-}
case ${2:-both} in
both) VARIANTS="sync queue" ;;
sync | queue) VARIANTS=$2 ;;
*) echo "variante inválida: $2 (sync|queue|both)" >&2; exit 2 ;;
esac
if [[ -z $TEST || ! -f $LT/tests/$TEST.env ]]; then
	echo "uso: run.sh <smoke|stress|spike> [sync|queue|both]" >&2
	exit 2
fi
TIME_SCALE=${TIME_SCALE:-1}
TURN_TIMEOUT_S=${TURN_TIMEOUT_S:-120}
WARMUP_S=${WARMUP_S:-60}
# Ao fim do schedule o JMeter interrompe as conversas em andamento: a pausa final garante que
# todo turno iniciado antes do fim das chegadas termine (DONE, FAILED ou TIMEOUT).
DRAIN_S=$((TURN_TIMEOUT_S + 60))

DIR="$LT/results/$(date +%Y%m%d-%H%M%S)-$TEST"
mkdir -p "$DIR"
printf 'TEST=%s\nVARIANTS="%s"\nTIME_SCALE=%s\n' "$TEST" "$VARIANTS" "$TIME_SCALE" >"$DIR/meta.env"

compose() { docker compose -f "$ROOT/docker-compose.yml" "$@"; }
log() { printf '\n==> %s\n' "$*"; }

CURRENT=""
cleanup() { [[ -n $CURRENT ]] && compose --profile "$CURRENT" down >/dev/null 2>&1 || true; }
trap cleanup EXIT

# run_jmeter <variante> <schedule> <dir relativo a loadtest/>
run_jmeter() {
	local host=chat-sync
	[[ $1 == queue ]] && host=chat-api
	compose --profile "$1" --profile loadtest run --rm --user "$(id -u):$(id -g)" jmeter \
		-n -t /loadtest/plan.jmx -q /loadtest/user.properties \
		-Jhost="$host" -Jturn_timeout_s="$TURN_TIMEOUT_S" "-Jschedule=$2" \
		-l "/loadtest/$3/results.jtl" -j "/loadtest/$3/jmeter.log" -e -o "/loadtest/$3/report"
}

wait_api() {
	local i
	for ((i = 0; i < 90; i++)); do
		curl -fsS localhost:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"' && return 0
		sleep 2
	done
	echo "API não ficou saudável em 180 s" >&2
	return 1
}

log "Build da imagem do JMeter"
compose --profile loadtest build jmeter
compose --profile sync --profile queue down >/dev/null 2>&1 || true

for v in $VARIANTS; do
	rel="results/$(basename "$DIR")/$v"
	mkdir -p "$LT/$rel"
	CURRENT=$v
	log "[$v] subindo a stack do zero"
	compose --profile "$v" up -d --build --wait
	wait_api

	log "[$v] aquecimento (${WARMUP_S}s, descartado)"
	run_jmeter "$v" "rate(0.2/s) random_arrivals($WARMUP_S s) rate(0.2/s) pause(30 s)" "$rel/warmup" >/dev/null

	log "[$v] teste $TEST"
	run_jmeter "$v" "$(sched_string "$LT/tests/$TEST.env" "$TIME_SCALE" "$DRAIN_S")" "$rel"

	log "[$v] coletando métricas do Prometheus"
	sleep 10 # um scrape a mais depois do fim
	t0=$(($(jtl_t0 "$LT/$rel/results.jtl") / 1000))
	containers=chat-sync
	[[ $v == queue ]] && containers="chat-api chat-worker"
	# shellcheck disable=SC2086
	read -r restarts oom <<<"$(docker inspect -f '{{.RestartCount}} {{.State.OOMKilled}}' $containers |
		awk '{ r += $1; if ($2 == "true") o = "true" } END { print r + 0, (o ? o : "false") }')"
	prom_collect "$v" "$((t0 - 60))" "$(date +%s)" |
		jq --argjson r "$restarts" --argjson o "$oom" '. + { restarts: $r, oom_killed: $o }' >"$LT/$rel/metrics.json"

	log "[$v] derrubando"
	compose --profile "$v" down
	CURRENT=""
done

log "Resumo"
set +e
"$LT/summarize.sh" "$DIR"
rc=$?
cat "$DIR/summary.md"
exit "$rc"
```

- [ ] **Step 2: Validação de argumentos**

Run: `bash -n loadtest/run.sh && loadtest/run.sh; echo rc=$?; loadtest/run.sh smoke nope; echo rc=$?; loadtest/run.sh soak; echo rc=$?`
Expected: mensagem de uso e `rc=2`; `variante inválida: nope` e `rc=2`; uso e `rc=2`.

- [ ] **Step 3: Aceitação — smoke nas duas variantes** (~15 min: 2 × (subida + 90s de aquecimento + 120s de chegadas + 180s de drenagem))

Run: `loadtest/run.sh smoke both; echo rc=$?`
Expected: `rc=0`; `summary.md` com as colunas `sync` e `queue`, os três SLOs ✅ nas duas; seção de servidor com `Pico do backlog` numérico em `queue` e `n/d` em `sync`; `results/<id>/<v>/report/index.html` existe; nenhum container `chat-*` rodando ao final (`docker ps --filter name=chat- -q` vazio).

Se algum SLO reprovar, abrir o `report/index.html` e o `summary.md` antes de mexer em limites: o smoke existe para pegar erro funcional, não para ser ajustado até passar.

- [ ] **Step 4: Fumaça do spike encurtado** (~17 min)

Run: `TIME_SCALE=10 loadtest/run.sh spike both; echo rc=$?`
Expected: o script termina (rc 0 ou 1 — nessa escala os SLOs podem reprovar); `summary.md` tem as três linhas do veredito do spike com valores numéricos ou `> N s` (não `n/d`) para `queue`; `metrics.json` de `queue` tem `pending` não vazio.

- [ ] **Step 5: Interrupção limpa** — rodar `loadtest/run.sh smoke sync`, apertar Ctrl-C durante o aquecimento.
Expected: o trap derruba a stack (`docker ps --filter name=chat-sync -q` vazio).

- [ ] **Step 6: Commit**

```bash
git add loadtest/run.sh
git commit -m "feat(loadtest): run.sh orchestrating variants, warm-up, JMeter and metrics"
```

---

### Task 7: Documentação

**Files:**
- Create: `loadtest/README.md`
- Modify: `README.md` (nova seção antes de "## Testes automatizados"), `docs/superpowers/specs/2026-09-26-backend-llm-design.md` (§13)

- [ ] **Step 1: Criar `loadtest/README.md`**

````markdown
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

Pré-requisitos: Docker, `jq`, `curl`. Cada variante sobe do zero (Postgres e Redis sem volume),
é aquecida por 60s (descartados), testada e derrubada. Saída em `loadtest/results/<data>-<teste>/`:

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
(> 120s) ou `HTTP <código>`.

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
`turn_timeout_s`, `post_timeout_ms`); para rodar pela GUI, defina `-Jhost=localhost`.

## Testes do ferramental

```bash
bash loadtest/test/test.sh   # sem Docker
```

## Limitações

- No Mac, JMeter e aplicação dividem a VM do Docker Desktop.
- O polling de 500ms soma até 0,5s ao turno da `queue`.
- Métricas de CPU/throttling dependem do cAdvisor (ver "Limitações conhecidas" no README raiz); sem elas o resumo mostra `n/d`.
- Turnos órfãos em `PROCESSING` (sem reaper) aparecem como `TIMEOUT`.
````

- [ ] **Step 2: Seção no `README.md` da raiz** — inserir imediatamente antes de `## Testes automatizados`:

~~~markdown
## Testes de carga

Smoke, stress e spike com JMeter, nas duas variantes, com SLOs e resumo comparativo:

```bash
loadtest/run.sh smoke    # ou stress, spike; [sync|queue|both]
```

Detalhes, perfis e SLOs em [`loadtest/README.md`](loadtest/README.md).
~~~

E acrescentar ao bloco de `## Testes automatizados`:

```bash
bash loadtest/test/test.sh     # ferramental dos testes de carga, sem Docker
```

- [ ] **Step 3: Atualizar o fora de escopo da spec anterior** — em `docs/superpowers/specs/2026-09-26-backend-llm-design.md` §13, trocar a linha

```
- Planos JMeter (próximo spec), incluindo avaliar o Backend Listener do JMeter para o Grafana.
```

por

```
- Planos JMeter: especificados em `2026-09-26-jmeter-load-tests-design.md` (o Backend Listener foi avaliado e descartado lá).
```

- [ ] **Step 4: Verificação final**

Run: `bash loadtest/test/test.sh && grep -n "loadtest/run.sh" README.md loadtest/README.md | head`
Expected: `40 ok, 0 falha(s)` e as referências nos dois READMEs.

- [ ] **Step 5: Commit**

```bash
git add loadtest/README.md README.md docs/superpowers/specs/2026-09-26-backend-llm-design.md
git commit -m "docs(loadtest): usage, profiles and SLOs"
```
