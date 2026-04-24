#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:18080}"
RESULT_DIR="${RESULT_DIR:-loadtest/results}"
SOAK_FACTOR="${SOAK_FACTOR:-1.25}"
SOAK_DURATION="${SOAK_DURATION:-30m}"
WARMUP_DURATION="${WARMUP_DURATION:-2m}"
SAMPLE_INTERVAL_SECONDS="${SAMPLE_INTERVAL_SECONDS:-60}"
PROGRESS_INTERVAL_SECONDS="${PROGRESS_INTERVAL_SECONDS:-600}"
CLEANUP_CURL_MAX_TIME="${CLEANUP_CURL_MAX_TIME:-120}"
SUITE_TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
SQL_CHECK_FILE="loadtest/sql/consistency-checks.sql"
LOADTEST_PREFIX="${LOADTEST_PREFIX:-soak$(date +%m%d%H%M%S)}"
LOG_FILE="$RESULT_DIR/soak-$SUITE_TIMESTAMP.log"
K6_SUMMARY_FILE="$RESULT_DIR/soak-$SUITE_TIMESTAMP-k6.json"
SERVER_METRICS_FILE="$RESULT_DIR/soak-$SUITE_TIMESTAMP-server.json"
SQL_SNAPSHOT_FILE="$RESULT_DIR/soak-$SUITE_TIMESTAMP-sql.tsv"
TIMELINE_FILE="$RESULT_DIR/soak-$SUITE_TIMESTAMP-metrics.jsonl"
SUMMARY_REPORT_FILE="$RESULT_DIR/soak-$SUITE_TIMESTAMP.md"

export PGHOST="${PGHOST:-localhost}"
export PGPORT="${PGPORT:-5432}"
export PGDATABASE="${PGDATABASE:-${APP_LOAD_TEST_DB_NAME:-effectivedisco_loadtest}}"
export PGUSER="${PGUSER:-postgres}"
export PGPASSWORD="${PGPASSWORD:-4321}"

mkdir -p "$RESULT_DIR"

normalize_loopback_base_url() {
  local url="$1"
  # 문제 해결:
  # 이 환경에서는 `localhost`가 IPv6(::1)로 먼저 해석되는데 loadtest 앱은 IPv4 loopback에서만
  # 열려 있을 수 있다. runner 내부 curl만 실패하고 direct 127.0.0.1 호출은 성공하는
  # 비대칭을 막기 위해, local soak에서는 `localhost`를 `127.0.0.1`로 정규화해
  # readiness/reset/metrics/cleanup 과 k6가 같은 endpoint를 보게 한다.
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

REQUEST_BASE_URL="$BASE_URL"
BASE_URL="$(normalize_loopback_base_url "$BASE_URL")"

if ! command -v k6 >/dev/null 2>&1; then
  printf 'k6 is not installed.\n' >&2
  exit 1
fi

if ! command -v psql >/dev/null 2>&1; then
  printf 'psql is not installed.\n' >&2
  exit 1
fi

wait_for_metrics_endpoint() {
  local url="$1"
  local attempts="${2:-30}"
  local sleep_seconds="${3:-1}"
  local try
  for ((try = 1; try <= attempts; try++)); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep "$sleep_seconds"
  done
  return 1
}

capture_metrics_snapshot() {
  local url="$1"
  local output_file="$2"
  local attempts="${3:-3}"
  local tmp_file="${output_file}.tmp"
  local try

  for ((try = 1; try <= attempts; try++)); do
    if curl -fsS --max-time 15 "$url" >"$tmp_file"; then
      mv "$tmp_file" "$output_file"
      return 0
    fi
    sleep 1
  done

  rm -f "$tmp_file"
  return 1
}

run_sql_snapshot() {
  local output_file="$1"
  # 문제 해결:
  # 후처리 단계에서 SQL snapshot 조회가 오래 걸리면 soak runner가 끝난 것처럼 보여도
  # final artifact가 한참 늦게 생긴다. statement timeout을 명시해 hang을 막고,
  # 이 단계가 실패해도 원인을 바로 드러내도록 한다.
  PGOPTIONS="${PGOPTIONS:-} -c statement_timeout=30000" \
    psql -v ON_ERROR_STOP=1 -v loadtest_prefix="$LOADTEST_PREFIX" -F $'\t' -At -f "$SQL_CHECK_FILE" >"$output_file"
}

cleanup_loadtest_scope() {
  local prefix="$1"
  # 문제 해결:
  # cleanup endpoint도 후처리 마지막 단계라 여기서 오래 멈추면 전체 suite가 끝난 것처럼 보여도
  # summary가 생성되지 않는다. cleanup은 측정 결과 자체보다 후속 환경 위생을 위한 단계이므로,
  # timeout이 나더라도 summary를 먼저 남길 수 있게 best-effort로 다룬다.
  curl -fsS --max-time "$CLEANUP_CURL_MAX_TIME" --retry 2 --retry-delay 1 -X POST "$BASE_URL/internal/load-test/cleanup" \
    -H 'Content-Type: application/json' \
    -d "{\"prefix\":\"$prefix\"}" >/dev/null
}

if ! wait_for_metrics_endpoint "$BASE_URL/internal/load-test/metrics" 30 1; then
  printf 'load-test metrics endpoint is not reachable: %s\n' "$BASE_URL/internal/load-test/metrics" >&2
  exit 1
fi

trim_ms() {
  printf '%s' "${1%ms}"
}

scale_int() {
  local base="$1"
  local factor="$2"
  awk -v base="$base" -v factor="$factor" 'BEGIN {
    value = int((base * factor) + 0.5)
    if (value < 1) value = 1
    printf "%d", value
  }'
}

extract_summary_value() {
  local file="$1"
  local label="$2"
  local field="$3"
  local line
  line="$(sed -n "s/^${label} p95=\\([^ ]*\\) p99=\\([^ ]*\\)$/\\1\\t\\2/p" "$file" | tail -n1)"
  if [[ -z "$line" ]]; then
    printf 'n/a'
    return
  fi

  if [[ "$field" == "p95" ]]; then
    printf '%s' "${line%%$'\t'*}"
    return
  fi

  printf '%s' "${line#*$'\t'}"
}

extract_rate_value() {
  local file="$1"
  local label="$2"
  sed -n "s/^${label}=\\(.*\\)$/\\1/p" "$file" | tail -n1
}

extract_json_number() {
  local file="$1"
  local key="$2"
  sed -n "s/.*\"${key}\":\\([0-9][0-9]*\\).*/\\1/p" "$file" | head -n1
}

extract_snapshot_number() {
  local snapshot="$1"
  local key="$2"
  printf '%s' "$snapshot" | sed -n "s/.*\"${key}\":\\([0-9][0-9]*\\).*/\\1/p" | head -n1
}

sample_metrics_loop() {
  local monitored_pid="$1"
  local start_ts next_progress_ts
  start_ts="$(date +%s)"
  next_progress_ts=$((start_ts + PROGRESS_INTERVAL_SECONDS))
  while kill -0 "$monitored_pid" >/dev/null 2>&1; do
    local now timestamp snapshot
    now="$(date +%s)"
    timestamp="$(date -Iseconds)"
    if snapshot="$(curl -fsS "$BASE_URL/internal/load-test/metrics" 2>/dev/null)"; then
      printf '{"timestamp":"%s","metrics":%s}\n' "$timestamp" "$snapshot" >>"$TIMELINE_FILE"
      if (( now >= next_progress_ts )); then
        local elapsed_min pool_timeouts dup_keys max_awaiting max_active longest_tx
        elapsed_min=$(( (now - start_ts) / 60 ))
        pool_timeouts="$(extract_snapshot_number "$snapshot" "dbPoolTimeouts")"
        dup_keys="$(extract_snapshot_number "$snapshot" "duplicateKeyConflicts")"
        max_awaiting="$(extract_snapshot_number "$snapshot" "maxThreadsAwaitingConnection")"
        max_active="$(extract_snapshot_number "$snapshot" "maxActiveConnections")"
        longest_tx="$(extract_snapshot_number "$snapshot" "longestTransactionMs")"
        printf '[soak progress] elapsed=%dm dbPoolTimeouts=%s duplicateKeyConflicts=%s maxThreadsAwaitingConnection=%s maxActiveConnections=%s longestTransactionMs=%s\n' \
          "$elapsed_min" "${pool_timeouts:-n/a}" "${dup_keys:-n/a}" "${max_awaiting:-n/a}" "${max_active:-n/a}" "${longest_tx:-n/a}"
        while (( now >= next_progress_ts )); do
          next_progress_ts=$((next_progress_ts + PROGRESS_INTERVAL_SECONDS))
        done
      fi
    fi
    sleep "$SAMPLE_INTERVAL_SECONDS"
  done
}

base_browse_rate="${BROWSE_RATE:-80}"
base_browse_pre_allocated_vus="${BROWSE_PRE_ALLOCATED_VUS:-40}"
base_browse_max_vus="${BROWSE_MAX_VUS:-120}"
base_hot_post_rate="${HOT_POST_RATE:-120}"
base_hot_post_pre_allocated_vus="${HOT_POST_PRE_ALLOCATED_VUS:-50}"
base_hot_post_max_vus="${HOT_POST_MAX_VUS:-150}"
base_search_rate="${SEARCH_RATE:-40}"
base_search_pre_allocated_vus="${SEARCH_PRE_ALLOCATED_VUS:-20}"
base_search_max_vus="${SEARCH_MAX_VUS:-80}"
base_write_rate="${WRITE_RATE:-35}"
base_write_pre_allocated_vus="${WRITE_PRE_ALLOCATED_VUS:-20}"
base_write_max_vus="${WRITE_MAX_VUS:-80}"
base_like_add_vus="${LIKE_ADD_VUS:-80}"
base_like_remove_vus="${LIKE_REMOVE_VUS:-80}"
base_bookmark_mixed_vus="${BOOKMARK_MIXED_VUS:-60}"
base_follow_mixed_vus="${FOLLOW_MIXED_VUS:-60}"
base_block_mixed_vus="${BLOCK_MIXED_VUS:-60}"
base_notification_mixed_vus="${NOTIFICATION_MIXED_VUS:-40}"

browse_rate="$(scale_int "$base_browse_rate" "$SOAK_FACTOR")"
browse_pre_allocated_vus="$(scale_int "$base_browse_pre_allocated_vus" "$SOAK_FACTOR")"
browse_max_vus="$(scale_int "$base_browse_max_vus" "$SOAK_FACTOR")"
hot_post_rate="$(scale_int "$base_hot_post_rate" "$SOAK_FACTOR")"
hot_post_pre_allocated_vus="$(scale_int "$base_hot_post_pre_allocated_vus" "$SOAK_FACTOR")"
hot_post_max_vus="$(scale_int "$base_hot_post_max_vus" "$SOAK_FACTOR")"
search_rate="$(scale_int "$base_search_rate" "$SOAK_FACTOR")"
search_pre_allocated_vus="$(scale_int "$base_search_pre_allocated_vus" "$SOAK_FACTOR")"
search_max_vus="$(scale_int "$base_search_max_vus" "$SOAK_FACTOR")"
write_rate="$(scale_int "$base_write_rate" "$SOAK_FACTOR")"
write_pre_allocated_vus="$(scale_int "$base_write_pre_allocated_vus" "$SOAK_FACTOR")"
write_max_vus="$(scale_int "$base_write_max_vus" "$SOAK_FACTOR")"
like_add_vus="$(scale_int "$base_like_add_vus" "$SOAK_FACTOR")"
like_remove_vus="$(scale_int "$base_like_remove_vus" "$SOAK_FACTOR")"
bookmark_mixed_vus="$(scale_int "$base_bookmark_mixed_vus" "$SOAK_FACTOR")"
follow_mixed_vus="$(scale_int "$base_follow_mixed_vus" "$SOAK_FACTOR")"
block_mixed_vus="$(scale_int "$base_block_mixed_vus" "$SOAK_FACTOR")"
notification_mixed_vus="$(scale_int "$base_notification_mixed_vus" "$SOAK_FACTOR")"

curl -fsS -X POST "$BASE_URL/internal/load-test/reset" >/dev/null

# 문제 해결:
# soak test는 종료 시점 숫자만으로는 slow creep와 pool 압력 변화를 판단하기 어렵다.
# k6가 도는 동안 주기적으로 내부 metrics를 JSONL로 남겨 장시간 경향을 사후 분석할 수 있게 한다.
set +e
LOADTEST_PREFIX="$LOADTEST_PREFIX" \
BASE_URL="$BASE_URL" \
K6_SUMMARY_FILE="$K6_SUMMARY_FILE" \
BROWSE_RATE="$browse_rate" BROWSE_DURATION="$SOAK_DURATION" BROWSE_PRE_ALLOCATED_VUS="$browse_pre_allocated_vus" BROWSE_MAX_VUS="$browse_max_vus" \
HOT_POST_RATE="$hot_post_rate" HOT_POST_DURATION="$SOAK_DURATION" HOT_POST_PRE_ALLOCATED_VUS="$hot_post_pre_allocated_vus" HOT_POST_MAX_VUS="$hot_post_max_vus" \
SEARCH_RATE="$search_rate" SEARCH_DURATION="$SOAK_DURATION" SEARCH_PRE_ALLOCATED_VUS="$search_pre_allocated_vus" SEARCH_MAX_VUS="$search_max_vus" \
WRITE_START_RATE="$write_rate" WRITE_STAGE_ONE_RATE="$write_rate" WRITE_STAGE_ONE_DURATION="$WARMUP_DURATION" WRITE_STAGE_TWO_RATE="$write_rate" WRITE_STAGE_TWO_DURATION="$SOAK_DURATION" WRITE_PRE_ALLOCATED_VUS="$write_pre_allocated_vus" WRITE_MAX_VUS="$write_max_vus" \
LIKE_ADD_VUS="$like_add_vus" LIKE_ADD_DURATION="$SOAK_DURATION" \
LIKE_REMOVE_VUS="$like_remove_vus" LIKE_REMOVE_DURATION="$SOAK_DURATION" \
BOOKMARK_MIXED_VUS="$bookmark_mixed_vus" BOOKMARK_MIXED_DURATION="$SOAK_DURATION" \
FOLLOW_MIXED_VUS="$follow_mixed_vus" FOLLOW_MIXED_DURATION="$SOAK_DURATION" \
BLOCK_MIXED_VUS="$block_mixed_vus" BLOCK_MIXED_DURATION="$SOAK_DURATION" \
NOTIFICATION_MIXED_VUS="$notification_mixed_vus" NOTIFICATION_MIXED_DURATION="$SOAK_DURATION" \
k6 run --summary-trend-stats "avg,min,med,max,p(90),p(95),p(99)" loadtest/k6/bbs-load.js >"$LOG_FILE" 2>&1 &
k6_pid=$!
sample_metrics_loop "$k6_pid" &
sampler_pid=$!
wait "$k6_pid"
k6_exit_code=$?
# 문제 해결:
# 기존 구현은 sampler가 sleep 중일 때 k6가 끝나도 `wait "$sampler_pid"`가 sample interval만큼
# 추가로 막히면서 final server/sql artifact 생성이 지연되거나, 사용자가 그 전에 wrapper를
# 중단해 후처리 hang처럼 보였다. k6 종료 직후 sampler를 명시적으로 정리해 finalization으로
# 즉시 넘어가게 한다.
kill "$sampler_pid" >/dev/null 2>&1 || true
wait "$sampler_pid" || true
set -e

capture_metrics_snapshot "$BASE_URL/internal/load-test/metrics" "$SERVER_METRICS_FILE"
printf '{"timestamp":"%s","metrics":%s}\n' "$(date -Iseconds)" "$(cat "$SERVER_METRICS_FILE")" >>"$TIMELINE_FILE"
run_sql_snapshot "$SQL_SNAPSHOT_FILE"

# 문제 해결:
# soak는 실행 prefix 범위 데이터를 SQL 스냅샷으로 검증한 직후 정리해야
# 장시간 테스트를 여러 번 반복해도 개발 DB의 게시물 수가 계속 누적되지 않는다.
cleanup_status="ok"
cleanup_note="completed"
if ! cleanup_loadtest_scope "$LOADTEST_PREFIX"; then
  cleanup_status="failed"
  cleanup_note="cleanup endpoint timed out or returned non-2xx; summary generated from captured artifacts"
  printf 'warning: cleanup failed for prefix %s; keeping summary generation\n' "$LOADTEST_PREFIX" >&2
fi

IFS=$'\t' read -r scope_user_count scope_post_count scope_notification_count duplicate_post_likes duplicate_bookmarks duplicate_follows duplicate_blocks post_like_mismatch_posts negative_like_count_posts post_comment_mismatch_posts negative_comment_count_posts unread_notification_mismatch_users negative_unread_notification_users <"$SQL_SNAPSHOT_FILE"

http_p95="$(extract_summary_value "$LOG_FILE" "http_req_duration" "p95")"
http_p99="$(extract_summary_value "$LOG_FILE" "http_req_duration" "p99")"
unexpected_response_rate="$(extract_rate_value "$LOG_FILE" "unexpected_response_rate")"
duplicate_key_conflicts="$(extract_json_number "$SERVER_METRICS_FILE" "duplicateKeyConflicts")"
db_pool_timeouts="$(extract_json_number "$SERVER_METRICS_FILE" "dbPoolTimeouts")"
max_active_connections="$(extract_json_number "$SERVER_METRICS_FILE" "maxActiveConnections")"
max_threads_awaiting="$(extract_json_number "$SERVER_METRICS_FILE" "maxThreadsAwaitingConnection")"

relation_duplicate_rows=$((duplicate_post_likes + duplicate_bookmarks + duplicate_follows + duplicate_blocks))
invariant_failures=$((relation_duplicate_rows + post_like_mismatch_posts + negative_like_count_posts + post_comment_mismatch_posts + negative_comment_count_posts + unread_notification_mismatch_users + negative_unread_notification_users))
status="PASS"

if (( k6_exit_code != 0 )) || [[ "$unexpected_response_rate" != "0.0000" ]] || (( duplicate_key_conflicts > 0 )) || (( db_pool_timeouts > 0 )) || (( invariant_failures > 0 )); then
  status="FAIL"
fi

printf '# BBS Soak Suite\n\n' >"$SUMMARY_REPORT_FILE"
printf -- '- executed_at: %s\n' "$SUITE_TIMESTAMP" >>"$SUMMARY_REPORT_FILE"
printf -- '- requested_base_url: `%s`\n' "$REQUEST_BASE_URL" >>"$SUMMARY_REPORT_FILE"
printf -- '- effective_base_url: `%s`\n' "$BASE_URL" >>"$SUMMARY_REPORT_FILE"
printf -- '- soak_factor: `%s`\n' "$SOAK_FACTOR" >>"$SUMMARY_REPORT_FILE"
printf -- '- soak_duration: `%s`\n' "$SOAK_DURATION" >>"$SUMMARY_REPORT_FILE"
printf -- '- warmup_duration: `%s`\n' "$WARMUP_DURATION" >>"$SUMMARY_REPORT_FILE"
printf -- '- loadtest_prefix: `%s`\n' "$LOADTEST_PREFIX" >>"$SUMMARY_REPORT_FILE"
printf -- '- status: `%s`\n\n' "$status" >>"$SUMMARY_REPORT_FILE"
printf '| metric | value |\n' >>"$SUMMARY_REPORT_FILE"
printf '| --- | --- |\n' >>"$SUMMARY_REPORT_FILE"
printf '| http p95 | `%s` |\n' "$http_p95" >>"$SUMMARY_REPORT_FILE"
printf '| http p99 | `%s` |\n' "$http_p99" >>"$SUMMARY_REPORT_FILE"
printf '| unexpected_response_rate | `%s` |\n' "$unexpected_response_rate" >>"$SUMMARY_REPORT_FILE"
printf '| duplicateKeyConflicts | `%s` |\n' "$duplicate_key_conflicts" >>"$SUMMARY_REPORT_FILE"
printf '| dbPoolTimeouts | `%s` |\n' "$db_pool_timeouts" >>"$SUMMARY_REPORT_FILE"
printf '| maxActiveConnections | `%s` |\n' "$max_active_connections" >>"$SUMMARY_REPORT_FILE"
printf '| maxThreadsAwaitingConnection | `%s` |\n' "$max_threads_awaiting" >>"$SUMMARY_REPORT_FILE"
printf '| relationDuplicateRows | `%s` |\n' "$relation_duplicate_rows" >>"$SUMMARY_REPORT_FILE"
printf '| postLikeMismatchPosts | `%s` |\n' "$post_like_mismatch_posts" >>"$SUMMARY_REPORT_FILE"
printf '| postCommentMismatchPosts | `%s` |\n' "$post_comment_mismatch_posts" >>"$SUMMARY_REPORT_FILE"
printf '| unreadNotificationMismatchUsers | `%s` |\n' "$unread_notification_mismatch_users" >>"$SUMMARY_REPORT_FILE"
printf '| scopeUserCount | `%s` |\n' "$scope_user_count" >>"$SUMMARY_REPORT_FILE"
printf '| scopePostCount | `%s` |\n' "$scope_post_count" >>"$SUMMARY_REPORT_FILE"
printf '| scopeNotificationCount | `%s` |\n' "$scope_notification_count" >>"$SUMMARY_REPORT_FILE"
printf '| cleanupStatus | `%s` |\n' "$cleanup_status" >>"$SUMMARY_REPORT_FILE"
printf '| cleanupNote | `%s` |\n' "$cleanup_note" >>"$SUMMARY_REPORT_FILE"

printf '\n## Artifacts\n\n' >>"$SUMMARY_REPORT_FILE"
printf -- '- log: `%s`\n' "$LOG_FILE" >>"$SUMMARY_REPORT_FILE"
printf -- '- k6 summary: `%s`\n' "$K6_SUMMARY_FILE" >>"$SUMMARY_REPORT_FILE"
printf -- '- final server metrics: `%s`\n' "$SERVER_METRICS_FILE" >>"$SUMMARY_REPORT_FILE"
printf -- '- metrics timeline: `%s`\n' "$TIMELINE_FILE" >>"$SUMMARY_REPORT_FILE"
printf -- '- final SQL snapshot: `%s`\n' "$SQL_SNAPSHOT_FILE" >>"$SUMMARY_REPORT_FILE"
printf -- '- bottleneck profiles: `bottleneckProfiles` field in `%s`\n' "$SERVER_METRICS_FILE" >>"$SUMMARY_REPORT_FILE"

printf 'soak report: %s\n' "$SUMMARY_REPORT_FILE"
printf 'server metrics: %s\n' "$SERVER_METRICS_FILE"
printf 'metrics timeline: %s\n' "$TIMELINE_FILE"

if [[ "$status" != "PASS" ]]; then
  exit 1
fi
