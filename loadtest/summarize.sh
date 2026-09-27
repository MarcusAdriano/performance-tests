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

# window_stats <jtl> <from_ms> <to_ms> -> "n erros p50 p95 p99 max erros_pct turnos_ok_por_s"
window_stats() {
	local s n err
	s=$(jtl_turns "$1" "$2" "$3" | turn_stats)
	read -r n err _ <<<"$s"
	echo "$s $(pct "$err" "$n") $(awk -v n="$n" -v e="$err" -v d="$(($3 - $2))" 'BEGIN { printf "%.2f", (n - e) * 1000 / d }')"
}

# drain_s <metrics.json> <t0_s> <peak_start_s> <peak_end_s> -> segundos, -1 (não drenou) ou "-" (sem dados).
# A janela da linha de base (60s antes do início do pico) é limitada a partir de t0 (M5): sem o
# clamp, em TIME_SCALE grande o início do pico fica a menos de 60s de t0 e a janela vaza para
# dados de aquecimento/pré-teste, inflando a linha de base e mascarando a drenagem real.
drain_s() {
	jq -r --argjson t0 "$2" --argjson ps "$3" --argjson pe "$4" '
		.pending as $p
		| (if ($ps - 60) > $t0 then $ps - 60 else $t0 end) as $base_from
		| if ($p | length) == 0 then "-" else
			([$p[] | select(.[0] >= $base_from and .[0] < $ps) | .[1]] | max // 0) as $base
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
			echo "DRAIN_S=$(drain_s "$DIR/$v/metrics.json" "$((t0 / 1000))" "$((ps_ms / 1000))" "$((pe_ms / 1000))")"
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
check() { # check <variante (ignorado, mantido pela assinatura comum das *_row)> <valor exibido> <condição...>: imprime "valor ✅|❌" e acumula falhas
	local shown=$2
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
