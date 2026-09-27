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
