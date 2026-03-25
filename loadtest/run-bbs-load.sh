#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
RESULT_DIR="${RESULT_DIR:-loadtest/results}"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
K6_SUMMARY_FILE="${K6_SUMMARY_FILE:-$RESULT_DIR/k6-summary-$TIMESTAMP.json}"
SERVER_METRICS_FILE="${SERVER_METRICS_FILE:-$RESULT_DIR/server-metrics-$TIMESTAMP.json}"
LOADTEST_PREFIX="${LOADTEST_PREFIX:-ltb$(date +%m%d%H%M%S)}"

mkdir -p "$RESULT_DIR"

if ! command -v k6 >/dev/null 2>&1; then
  printf 'k6 is not installed.\n' >&2
  exit 1
fi

# 문제 해결:
# 이전 실행의 duplicate-key/db-pool 카운터가 남아 있으면 이번 부하 테스트 결과를 해석할 수 없다.
# k6 시작 전에 서버측 누적치를 초기화하고, 종료 직후 같은 실행의 스냅샷을 파일로 고정한다.
curl -fsS -X POST "$BASE_URL/internal/load-test/reset" >/dev/null

LOADTEST_PREFIX="$LOADTEST_PREFIX" BASE_URL="$BASE_URL" K6_SUMMARY_FILE="$K6_SUMMARY_FILE" k6 run loadtest/k6/bbs-load.js

curl -fsS "$BASE_URL/internal/load-test/metrics" >"$SERVER_METRICS_FILE"

# 문제 해결:
# 공식 loadtest 러너는 결과 파일을 저장한 뒤 이번 실행 prefix 데이터를 바로 회수해야
# 다음 부하 테스트와 수동 브라우징이 같은 개발 DB를 공유해도 row가 계속 누적되지 않는다.
curl -fsS -X POST "$BASE_URL/internal/load-test/cleanup" \
  -H 'Content-Type: application/json' \
  -d "{\"prefix\":\"$LOADTEST_PREFIX\"}" >/dev/null

printf 'k6 summary: %s\n' "$K6_SUMMARY_FILE"
printf 'server metrics: %s\n' "$SERVER_METRICS_FILE"
