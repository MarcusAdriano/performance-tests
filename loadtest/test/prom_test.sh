source "$LT/lib/prom.sh"

# --- prom ---------------------------------------------------------------------
assert_eq "prom range com NaN" "[[1700000000,3],[1700000010,0]]" "$(echo \
	'{"data":{"result":[{"values":[[1700000000.1,"3"],[1700000005,"NaN"],[1700000010,"0"]]}]}}' | prom_values)"
assert_eq "prom instant" "[[1700000000,12.5]]" "$(echo '{"data":{"result":[{"value":[1700000000,"12.5"]}]}}' | prom_values)"
assert_eq "prom sem série" "[]" "$(echo '{"data":{"result":[]}}' | prom_values)"
