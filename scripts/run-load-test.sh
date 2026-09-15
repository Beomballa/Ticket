#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

base_url="${BASE_URL:-http://host.docker.internal:8080}"
metrics_url="${METRICS_URL:-http://localhost:8080/actuator/prometheus}"
load_users="${LOAD_USERS:-20}"
load_iterations="${LOAD_ITERATIONS:-200}"

hikari_timeout_count() {
  curl -fsS "$metrics_url" \
    | awk '/^hikaricp_connections_timeout_total(\{| )/ { total += $2 } END { print total + 0 }'
}

hikari_pending_count() {
  curl -fsS "$metrics_url" \
    | awk '/^hikaricp_connections_pending(\{| )/ { total += $2 } END { print total + 0 }'
}

docker compose exec -T postgres \
  psql -v ON_ERROR_STOP=1 -v "load_users=$load_users" -U fan_event -d fan_event -f /dev/stdin \
  < scripts/load-test-data.sql

docker run --rm \
  --add-host host.docker.internal:host-gateway \
  -e "BASE_URL=$base_url" \
  -v "$project_dir/load-tests:/scripts:ro" \
  grafana/k6:2.0.0 run /scripts/catalog-read.js

hikari_timeouts_before="$(hikari_timeout_count)"

docker run --rm \
  --add-host host.docker.internal:host-gateway \
  -e "BASE_URL=$base_url" \
  -e "LOAD_USERS=$load_users" \
  -e "LOAD_ITERATIONS=$load_iterations" \
  -v "$project_dir/load-tests:/scripts:ro" \
  grafana/k6:2.0.0 run /scripts/reservation-contention.js

hikari_timeouts_after="$(hikari_timeout_count)"
if ! awk -v before="$hikari_timeouts_before" -v after="$hikari_timeouts_after" \
  'BEGIN { exit !(after - before == 0) }'; then
  echo "Hikari connection timeout detected during reservation load: before=$hikari_timeouts_before after=$hikari_timeouts_after" >&2
  exit 1
fi
if ! awk -v pending="$(hikari_pending_count)" 'BEGIN { exit !(pending == 0) }'; then
  echo "Hikari connections are still pending after reservation load" >&2
  exit 1
fi

docker compose exec -T postgres \
  psql -v ON_ERROR_STOP=1 -U fan_event -d fan_event -f /dev/stdin \
  < scripts/verify-load-test.sql
