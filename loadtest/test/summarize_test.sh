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

# M5: com TIME_SCALE=30 a janela de linha de base (60s antes do início do pico) fica antes de t0
# (pico em t0+6s). Sem o clamp, a linha de base pegaria um pico de "backlog" pré-teste (t=960,
# aquecimento) e o SLO passaria de forma otimista/errada; com o clamp em t0, a linha de base real
# (2) é usada e a drenagem é computada corretamente (52s).
mk_run "$TMP/spike-clamp" spike 30 queue
awk 'BEGIN { for (t = 0; t < 20000; t += 500) print 1000000 + t, 300, "DONE", "s" }' | mk_jtl "$TMP/spike-clamp/queue/results.jtl"
jq -n "$METRICS"' | .pending = [[960,999],[1000,2],[1003,2],[1005,2],[1006,50],[1007,50],[1010,50],[1020,50],[1060,2]]' \
	>"$TMP/spike-clamp/queue/metrics.json"
"$LT/summarize.sh" "$TMP/spike-clamp" >/dev/null
assert_contains "M5: drenagem usa linha de base pós-t0 (52s), não o pico pré-teste" \
	"| Drenagem em ≤ 180 s | 52 s ✅ |" "$TMP/spike-clamp/summary.md"
