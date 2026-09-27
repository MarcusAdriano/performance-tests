# Consultas ao Prometheus na janela do teste. Uso: source lib/prom.sh
# PROM_URL (padrão http://localhost:9090). Sem dados -> null (vira "n/d" no resumo).

PROM_URL=${PROM_URL:-http://localhost:9090}

# prom_values: resposta JSON da API (stdin) -> [[ts, valor], ...] da primeira série
# (as consultas agregam com sum/max, então há no máximo uma). Instant query -> um único par.
prom_values() {
	jq -c '[.data.result[0] // {} | (.values // [.value // empty])[]
		| [(.[0] | floor), (.[1] | if . == "NaN" or . == "+Inf" or . == "-Inf" then null else tonumber end)]
		| select(.[1] != null)]'
}

# prom_range <query> <start_s> <end_s> -> [[ts, valor], ...] com passo de 5s.
prom_range() {
	curl -fsS --get "$PROM_URL/api/v1/query_range" --data-urlencode "query=$1" \
		--data-urlencode "start=$2" --data-urlencode "end=$3" --data-urlencode "step=5" | prom_values
}

# prom_instant <query> <time_s> -> número ou null.
prom_instant() {
	curl -fsS --get "$PROM_URL/api/v1/query" --data-urlencode "query=$1" \
		--data-urlencode "time=$2" | prom_values | jq '.[0][1] // null'
}

# prom_collect <sync|queue> <start_s> <end_s> -> objeto JSON gravado em metrics.json.
# "pending" é a métrica de trabalho pendente usada no SLO de drenagem do spike.
prom_collect() {
	local variant=$1 start=$2 end=$3 range=$(($3 - $2)) pending backlog='[]' wait_p95=null
	local chat='name=~"chat-.*"'
	if [[ $variant == queue ]]; then
		pending='sum(rabbitmq_queue_messages_ready{queue="chat.turns.process"})'
		backlog=$(prom_range "$pending" "$start" "$end")
		wait_p95=$(prom_instant "histogram_quantile(0.95, sum by (le) (increase(chat_turn_queue_wait_seconds_bucket{mode=\"worker\"}[${range}s])))" "$end")
	else
		pending='sum(chat_turns_inflight{mode="sync"})'
	fi
	jq -n \
		--argjson pending "$(prom_range "$pending" "$start" "$end")" \
		--argjson backlog "$backlog" \
		--argjson inflight "$(prom_range 'sum(chat_turns_inflight)' "$start" "$end")" \
		--argjson hikari "$(prom_range 'sum(hikaricp_connections_pending)' "$start" "$end")" \
		--argjson heap "$(prom_range 'max(sum by (application) (jvm_memory_used_bytes{area="heap"}))' "$start" "$end")" \
		--argjson heap_max "$(prom_instant 'max(sum by (application) (jvm_memory_max_bytes{area="heap"}))' "$end")" \
		--argjson throttled "$(prom_instant "100 * sum(increase(container_cpu_cfs_throttled_periods_total{$chat}[${range}s])) / sum(increase(container_cpu_cfs_periods_total{$chat}[${range}s]))" "$end")" \
		--argjson wait_p95 "$wait_p95" '
		def peak: if length == 0 then null else (map(.[1]) | max) end;
		{
			pending: $pending,
			backlog_max: ($backlog | peak),
			inflight_max: ($inflight | peak),
			hikari_pending_max: ($hikari | peak),
			heap_used_max_mb: ($heap | peak | if . == null then null else (. / 1048576 | floor) end),
			heap_max_mb: (if $heap_max == null then null else ($heap_max / 1048576 | floor) end),
			throttled_pct: $throttled,
			queue_wait_p95_s: $wait_p95
		}'
}
