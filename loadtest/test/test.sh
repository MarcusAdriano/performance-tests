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
