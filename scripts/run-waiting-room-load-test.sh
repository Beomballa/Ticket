#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

base_url="${BASE_URL:-http://host.docker.internal:8080}"
load_users="${LOAD_USERS:-20}"

docker compose exec -T postgres \
  psql -v ON_ERROR_STOP=1 -v "load_users=$load_users" -U fan_event -d fan_event -f /dev/stdin \
  < scripts/load-test-data.sql

docker run --rm \
  --add-host host.docker.internal:host-gateway \
  -e "BASE_URL=$base_url" \
  -e "LOAD_USERS=$load_users" \
  -v "$project_dir/load-tests:/scripts:ro" \
  grafana/k6:2.0.0 run /scripts/waiting-room.js
