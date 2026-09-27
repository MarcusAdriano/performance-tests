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
