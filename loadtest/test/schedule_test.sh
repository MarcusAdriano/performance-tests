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
