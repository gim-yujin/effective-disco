#!/usr/bin/env bash
set -euo pipefail

SERVER_PORT="${SERVER_PORT:-18082}"
BASE_URL="${BASE_URL:-http://127.0.0.1:${SERVER_PORT}}"
RESULT_DIR="${RESULT_DIR:-loadtest/results}"
APP_LOG_FILE="${APP_LOG_FILE:-$RESULT_DIR/loadtest-app-$(date +%Y%m%d-%H%M%S).log}"
APP_START_TIMEOUT_SECONDS="${APP_START_TIMEOUT_SECONDS:-180}"
APP_START_SLEEP_SECONDS="${APP_START_SLEEP_SECONDS:-1}"
APP_SHUTDOWN_GRACE_SECONDS="${APP_SHUTDOWN_GRACE_SECONDS:-20}"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-/tmp/gradle-home}"
APP_LOAD_TEST_DB_POOL_MAX_SIZE="${APP_LOAD_TEST_DB_POOL_MAX_SIZE:-28}"

mkdir -p "$RESULT_DIR"

normalize_loopback_base_url() {
  local url="$1"
  url="${url/http:\/\/localhost:/http://127.0.0.1:}"
  url="${url/http:\/\/localhost\//http://127.0.0.1/}"
  url="${url/https:\/\/localhost:/https://127.0.0.1:}"
  url="${url/https:\/\/localhost\//https://127.0.0.1/}"
  if [[ "$url" == "http://localhost" ]]; then
    url="http://127.0.0.1"
  elif [[ "$url" == "https://localhost" ]]; then
    url="https://127.0.0.1"
  fi
  printf '%s' "$url"
}

wait_for_metrics_endpoint() {
  local url="$1"
  local attempts="$2"
  local sleep_seconds="$3"
  local try
  for ((try = 1; try <= attempts; try++)); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep "$sleep_seconds"
  done
  return 1
}

wait_for_managed_app() {
  local url="$1"
  local attempts="$2"
  local sleep_seconds="$3"
  local try
  for ((try = 1; try <= attempts; try++)); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      return 0
    fi
    if ! kill -0 "$app_pid" >/dev/null 2>&1; then
      return 2
    fi
    sleep "$sleep_seconds"
  done
  return 1
}

stop_app() {
  if [[ -z "${app_pid:-}" ]]; then
    return
  fi

  if ! kill -0 "$app_pid" >/dev/null 2>&1; then
    return
  fi

  kill -TERM "$app_pid" >/dev/null 2>&1 || true
  local try
  for ((try = 1; try <= APP_SHUTDOWN_GRACE_SECONDS; try++)); do
    if ! kill -0 "$app_pid" >/dev/null 2>&1; then
      return
    fi
    sleep 1
  done

  kill -KILL "$app_pid" >/dev/null 2>&1 || true
}

cleanup() {
  local exit_code=$?
  trap - EXIT INT TERM
  stop_app
  exit "$exit_code"
}

trap cleanup EXIT INT TERM

REQUEST_BASE_URL="$BASE_URL"
BASE_URL="$(normalize_loopback_base_url "$BASE_URL")"

# 문제 해결:
# 수동 터미널에서 앱을 먼저 내리면 k6의 gracefulStop/drain 구간에 남은 write 요청이
# `connection refused`로 바뀌어 unexpected response로 집계된다. soak wrapper가 앱의
# 시작/종료를 직접 관리해 runner가 완전히 끝난 뒤에만 앱을 내리도록 보장한다.
SPRING_PROFILES_ACTIVE=loadtest \
SERVER_PORT="$SERVER_PORT" \
APP_LOAD_TEST_DB_POOL_MAX_SIZE="$APP_LOAD_TEST_DB_POOL_MAX_SIZE" \
GRADLE_USER_HOME="$GRADLE_USER_HOME" \
./gradlew bootRun --no-daemon >"$APP_LOG_FILE" 2>&1 &
app_pid=$!

start_attempts=$((APP_START_TIMEOUT_SECONDS / APP_START_SLEEP_SECONDS))
wait_for_managed_app "$BASE_URL/internal/load-test/metrics" "$start_attempts" "$APP_START_SLEEP_SECONDS"
readiness_exit_code=$?
if (( readiness_exit_code != 0 )); then
  if (( readiness_exit_code == 2 )); then
    printf 'managed loadtest app exited before readiness; see log: %s\n' "$APP_LOG_FILE" >&2
  else
    printf 'managed loadtest app did not become ready: %s\n' "$BASE_URL/internal/load-test/metrics" >&2
  fi
  exit 1
fi

BASE_URL="$REQUEST_BASE_URL" RESULT_DIR="$RESULT_DIR" ./loadtest/run-bbs-soak.sh
