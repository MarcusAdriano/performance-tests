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
