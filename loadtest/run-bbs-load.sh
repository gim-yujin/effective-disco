#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
RESULT_DIR="${RESULT_DIR:-loadtest/results}"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
K6_SUMMARY_FILE="${K6_SUMMARY_FILE:-$RESULT_DIR/k6-summary-$TIMESTAMP.json}"
SERVER_METRICS_FILE="${SERVER_METRICS_FILE:-$RESULT_DIR/server-metrics-$TIMESTAMP.json}"

mkdir -p "$RESULT_DIR"

if ! command -v k6 >/dev/null 2>&1; then
  printf 'k6 is not installed.\n' >&2
  exit 1
fi

# 문제 해결:
# 이전 실행의 duplicate-key/db-pool 카운터가 남아 있으면 이번 부하 테스트 결과를 해석할 수 없다.
# k6 시작 전에 서버측 누적치를 초기화하고, 종료 직후 같은 실행의 스냅샷을 파일로 고정한다.
curl -fsS -X POST "$BASE_URL/internal/load-test/reset" >/dev/null

BASE_URL="$BASE_URL" K6_SUMMARY_FILE="$K6_SUMMARY_FILE" k6 run loadtest/k6/bbs-load.js

curl -fsS "$BASE_URL/internal/load-test/metrics" >"$SERVER_METRICS_FILE"

printf 'k6 summary: %s\n' "$K6_SUMMARY_FILE"
printf 'server metrics: %s\n' "$SERVER_METRICS_FILE"
