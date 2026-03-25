#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:18080}"
PREFIXES="${PREFIXES:-${1:-}}"

if [[ -z "$PREFIXES" ]]; then
  printf 'usage: %s <prefix[,prefix2,...]>\n' "$0" >&2
  exit 1
fi

IFS=',' read -r -a prefix_list <<<"$PREFIXES"

for prefix in "${prefix_list[@]}"; do
  if [[ -z "$prefix" ]]; then
    continue
  fi

  # 문제 해결:
  # 이미 남아 있는 loadtest row는 수동 cleanup 경로가 있어야
  # 자동 cleanup을 넣은 뒤에도 과거 실행 잔여물을 안전하게 회수할 수 있다.
  response="$(
    curl -fsS -X POST "$BASE_URL/internal/load-test/cleanup" \
      -H 'Content-Type: application/json' \
      -d "{\"prefix\":\"$prefix\"}"
  )"
  printf '%s\n' "$response"
done
