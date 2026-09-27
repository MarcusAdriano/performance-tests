source "$LT/lib/prom.sh"

# --- prom ---------------------------------------------------------------------
assert_eq "prom range com NaN" "[[1700000000,3],[1700000010,0]]" "$(echo \
	'{"data":{"result":[{"values":[[1700000000.1,"3"],[1700000005,"NaN"],[1700000010,"0"]]}]}}' | prom_values)"
assert_eq "prom instant" "[[1700000000,12.5]]" "$(echo '{"data":{"result":[{"value":[1700000000,"12.5"]}]}}' | prom_values)"
assert_eq "prom sem série" "[]" "$(echo '{"data":{"result":[]}}' | prom_values)"

# M6: arredondamento de exibição (queue_wait_p95_s, throttled_pct).
assert_eq "arredonda para 2 casas" "0.12" "$(prom_round2 0.1234)"
assert_eq "arredonda para cima" "1.23" "$(prom_round2 1.2251)"
assert_eq "null passa direto" "null" "$(prom_round2 null)"
assert_eq "inteiro sem casas espúrias" "5" "$(prom_round2 5)"
