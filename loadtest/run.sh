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
# Se um Ctrl-C acontecer durante o run_jmeter, o `docker compose run --rm` do jmeter é
# interrompido sem chance de fazer seu próprio --rm (e `compose down` não derruba containers
# avulsos de `run`), então removemos qualquer um pendente explicitamente.
cleanup() {
	docker ps -q --filter label=com.docker.compose.service=jmeter --filter label=com.docker.compose.oneoff=True |
		xargs -r docker rm -f >/dev/null 2>&1 || true
	[[ -n $CURRENT ]] && compose --profile "$CURRENT" down >/dev/null 2>&1 || true
}
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
