#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:18080}"
RESULT_DIR="${RESULT_DIR:-loadtest/results}"
STAGE_FACTORS="${STAGE_FACTORS:-1,1.25,1.5,1.75,2,2.25,2.5,3}"
STOP_ON_HTTP_P99_MS="${STOP_ON_HTTP_P99_MS:-300}"
STOP_ON_K6_THRESHOLD="${STOP_ON_K6_THRESHOLD:-1}"
EXIT_ON_LIMIT_REACHED="${EXIT_ON_LIMIT_REACHED:-0}"
SUITE_TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
SUITE_ID="$(date +%m%d%H%M%S)"
SQL_CHECK_FILE="loadtest/sql/consistency-checks.sql"
SUMMARY_REPORT_FILE="$RESULT_DIR/ramp-up-$SUITE_TIMESTAMP.md"
SUMMARY_TSV_FILE="$RESULT_DIR/ramp-up-$SUITE_TIMESTAMP.tsv"

export PGHOST="${PGHOST:-localhost}"
export PGPORT="${PGPORT:-5432}"
export PGDATABASE="${PGDATABASE:-effectivedisco}"
export PGUSER="${PGUSER:-postgres}"
export PGPASSWORD="${PGPASSWORD:-4321}"

mkdir -p "$RESULT_DIR"

if ! command -v k6 >/dev/null 2>&1; then
  printf 'k6 is not installed.\n' >&2
  exit 1
fi

if ! command -v psql >/dev/null 2>&1; then
  printf 'psql is not installed.\n' >&2
  exit 1
fi

if ! curl -fsS "$BASE_URL/internal/load-test/metrics" >/dev/null; then
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

is_greater_than() {
  local left="$1"
  local right="$2"
  awk -v left="$left" -v right="$right" 'BEGIN { exit !(left > right) }'
}

append_reason() {
  local current="$1"
  local next_reason="$2"
  if [[ -z "$current" ]]; then
    printf '%s' "$next_reason"
    return
  fi

  printf '%s,%s' "$current" "$next_reason"
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

printf '# BBS Ramp-Up Suite\n\n' >"$SUMMARY_REPORT_FILE"
printf -- '- executed_at: %s\n' "$SUITE_TIMESTAMP" >>"$SUMMARY_REPORT_FILE"
printf -- '- base_url: `%s`\n' "$BASE_URL" >>"$SUMMARY_REPORT_FILE"
printf -- '- stage_factors: `%s`\n' "$STAGE_FACTORS" >>"$SUMMARY_REPORT_FILE"
printf -- '- stop_on_http_p99_ms: `%s`\n' "$STOP_ON_HTTP_P99_MS" >>"$SUMMARY_REPORT_FILE"
printf -- '- stop_on_k6_threshold: `%s`\n' "$STOP_ON_K6_THRESHOLD" >>"$SUMMARY_REPORT_FILE"
printf -- '- database: `%s:%s/%s`\n\n' "$PGHOST" "$PGPORT" "$PGDATABASE" >>"$SUMMARY_REPORT_FILE"
printf '| stage | factor | status | stop reason | http p95 | http p99 | unexpected | dup-key | pool timeout | max active | max waiting | relation dup | like mismatch | comment mismatch | unread mismatch |\n' >>"$SUMMARY_REPORT_FILE"
printf '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n' >>"$SUMMARY_REPORT_FILE"

printf 'stage\tfactor\tstatus\tstop_reason\thttp_p95_ms\thttp_p99_ms\tunexpected_response_rate\tduplicate_key_conflicts\tdb_pool_timeouts\tmax_active_connections\tmax_threads_awaiting\trelation_duplicate_rows\tduplicate_post_likes\tduplicate_bookmarks\tduplicate_follows\tduplicate_blocks\tpost_like_mismatch_posts\tnegative_like_count_posts\tpost_comment_mismatch_posts\tnegative_comment_count_posts\tunread_notification_mismatch_users\tnegative_unread_notification_users\tscope_user_count\tscope_post_count\tscope_notification_count\n' >"$SUMMARY_TSV_FILE"

IFS=',' read -r -a stage_factor_list <<<"$STAGE_FACTORS"

base_browse_rate="${BROWSE_RATE:-80}"
base_browse_pre_allocated_vus="${BROWSE_PRE_ALLOCATED_VUS:-40}"
base_browse_max_vus="${BROWSE_MAX_VUS:-120}"
base_hot_post_rate="${HOT_POST_RATE:-120}"
base_hot_post_pre_allocated_vus="${HOT_POST_PRE_ALLOCATED_VUS:-50}"
base_hot_post_max_vus="${HOT_POST_MAX_VUS:-150}"
base_search_rate="${SEARCH_RATE:-40}"
base_search_pre_allocated_vus="${SEARCH_PRE_ALLOCATED_VUS:-20}"
base_search_max_vus="${SEARCH_MAX_VUS:-80}"
base_write_start_rate="${WRITE_START_RATE:-8}"
base_write_stage_one_rate="${WRITE_STAGE_ONE_RATE:-20}"
base_write_stage_two_rate="${WRITE_STAGE_TWO_RATE:-35}"
base_write_pre_allocated_vus="${WRITE_PRE_ALLOCATED_VUS:-20}"
base_write_max_vus="${WRITE_MAX_VUS:-80}"
base_like_add_vus="${LIKE_ADD_VUS:-80}"
base_like_remove_vus="${LIKE_REMOVE_VUS:-80}"
base_bookmark_mixed_vus="${BOOKMARK_MIXED_VUS:-60}"
base_follow_mixed_vus="${FOLLOW_MIXED_VUS:-60}"
base_block_mixed_vus="${BLOCK_MIXED_VUS:-60}"
base_notification_mixed_vus="${NOTIFICATION_MIXED_VUS:-40}"

suite_exit_code=0
limit_reached=0
highest_stable_factor="n/a"
highest_stable_stage="n/a"
first_limit_stage="n/a"
first_limit_reason="n/a"

for index in "${!stage_factor_list[@]}"; do
  factor="${stage_factor_list[$index]}"
  stage_number="$(printf '%02d' "$((index + 1))")"
  prefix="ltr${SUITE_ID}s${stage_number}"
  log_file="$RESULT_DIR/ramp-up-$SUITE_TIMESTAMP-stage${stage_number}.log"
  k6_summary_file="$RESULT_DIR/ramp-up-$SUITE_TIMESTAMP-stage${stage_number}-k6.json"
  server_metrics_file="$RESULT_DIR/ramp-up-$SUITE_TIMESTAMP-stage${stage_number}-server.json"
  sql_snapshot_file="$RESULT_DIR/ramp-up-$SUITE_TIMESTAMP-stage${stage_number}-sql.tsv"

  browse_rate="$(scale_int "$base_browse_rate" "$factor")"
  browse_pre_allocated_vus="$(scale_int "$base_browse_pre_allocated_vus" "$factor")"
  browse_max_vus="$(scale_int "$base_browse_max_vus" "$factor")"
  hot_post_rate="$(scale_int "$base_hot_post_rate" "$factor")"
  hot_post_pre_allocated_vus="$(scale_int "$base_hot_post_pre_allocated_vus" "$factor")"
  hot_post_max_vus="$(scale_int "$base_hot_post_max_vus" "$factor")"
  search_rate="$(scale_int "$base_search_rate" "$factor")"
  search_pre_allocated_vus="$(scale_int "$base_search_pre_allocated_vus" "$factor")"
  search_max_vus="$(scale_int "$base_search_max_vus" "$factor")"
  write_start_rate="$(scale_int "$base_write_start_rate" "$factor")"
  write_stage_one_rate="$(scale_int "$base_write_stage_one_rate" "$factor")"
  write_stage_two_rate="$(scale_int "$base_write_stage_two_rate" "$factor")"
  write_pre_allocated_vus="$(scale_int "$base_write_pre_allocated_vus" "$factor")"
  write_max_vus="$(scale_int "$base_write_max_vus" "$factor")"
  like_add_vus="$(scale_int "$base_like_add_vus" "$factor")"
  like_remove_vus="$(scale_int "$base_like_remove_vus" "$factor")"
  bookmark_mixed_vus="$(scale_int "$base_bookmark_mixed_vus" "$factor")"
  follow_mixed_vus="$(scale_int "$base_follow_mixed_vus" "$factor")"
  block_mixed_vus="$(scale_int "$base_block_mixed_vus" "$factor")"
  notification_mixed_vus="$(scale_int "$base_notification_mixed_vus" "$factor")"

  curl -fsS -X POST "$BASE_URL/internal/load-test/reset" >/dev/null

  # 문제 해결:
  # 한계점 탐색은 "부하만 키우고 데이터 범위는 stage별로 분리"해야 한다.
  # 같은 factor에서도 이전 stage의 row가 섞이면 어떤 배수가 실제 한계였는지 해석이 흐려진다.
  # 또한 ramp-up은 threshold를 일부러 넘겨보는 작업이라 k6의 종료 코드 99를 그대로 실패로 처리하면
  # 정작 필요한 서버/SQL 스냅샷을 남기지 못한다. k6 종료 코드를 별도로 잡아 limit 판정에 사용한다.
  set +e
  LOADTEST_PREFIX="$prefix" \
  BASE_URL="$BASE_URL" \
  K6_SUMMARY_FILE="$k6_summary_file" \
  BROWSE_RATE="$browse_rate" BROWSE_DURATION="${BROWSE_DURATION:-45s}" BROWSE_PRE_ALLOCATED_VUS="$browse_pre_allocated_vus" BROWSE_MAX_VUS="$browse_max_vus" \
  HOT_POST_RATE="$hot_post_rate" HOT_POST_DURATION="${HOT_POST_DURATION:-45s}" HOT_POST_PRE_ALLOCATED_VUS="$hot_post_pre_allocated_vus" HOT_POST_MAX_VUS="$hot_post_max_vus" \
  SEARCH_RATE="$search_rate" SEARCH_DURATION="${SEARCH_DURATION:-45s}" SEARCH_PRE_ALLOCATED_VUS="$search_pre_allocated_vus" SEARCH_MAX_VUS="$search_max_vus" \
  WRITE_START_RATE="$write_start_rate" WRITE_STAGE_ONE_RATE="$write_stage_one_rate" WRITE_STAGE_ONE_DURATION="${WRITE_STAGE_ONE_DURATION:-30s}" WRITE_STAGE_TWO_RATE="$write_stage_two_rate" WRITE_STAGE_TWO_DURATION="${WRITE_STAGE_TWO_DURATION:-30s}" WRITE_PRE_ALLOCATED_VUS="$write_pre_allocated_vus" WRITE_MAX_VUS="$write_max_vus" \
  LIKE_ADD_VUS="$like_add_vus" LIKE_ADD_DURATION="${LIKE_ADD_DURATION:-45s}" \
  LIKE_REMOVE_VUS="$like_remove_vus" LIKE_REMOVE_DURATION="${LIKE_REMOVE_DURATION:-45s}" \
  BOOKMARK_MIXED_VUS="$bookmark_mixed_vus" BOOKMARK_MIXED_DURATION="${BOOKMARK_MIXED_DURATION:-45s}" \
  FOLLOW_MIXED_VUS="$follow_mixed_vus" FOLLOW_MIXED_DURATION="${FOLLOW_MIXED_DURATION:-45s}" \
  BLOCK_MIXED_VUS="$block_mixed_vus" BLOCK_MIXED_DURATION="${BLOCK_MIXED_DURATION:-45s}" \
  NOTIFICATION_MIXED_VUS="$notification_mixed_vus" NOTIFICATION_MIXED_DURATION="${NOTIFICATION_MIXED_DURATION:-45s}" \
  k6 run --summary-trend-stats "avg,min,med,max,p(90),p(95),p(99)" loadtest/k6/bbs-load.js | tee "$log_file"
  k6_exit_code=$?
  set -e

  curl -fsS "$BASE_URL/internal/load-test/metrics" >"$server_metrics_file"
  psql -v ON_ERROR_STOP=1 -v loadtest_prefix="$prefix" -F $'\t' -At -f "$SQL_CHECK_FILE" >"$sql_snapshot_file"

  # 문제 해결:
  # ramp-up은 stage별 prefix 데이터를 SQL로 검증한 뒤 곧바로 치워야
  # 다음 stage와 다음 suite가 이전 stage가 만든 게시물/댓글을 끌고 가지 않는다.
  curl -fsS -X POST "$BASE_URL/internal/load-test/cleanup" \
    -H 'Content-Type: application/json' \
    -d "{\"prefix\":\"$prefix\"}" >/dev/null

  IFS=$'\t' read -r scope_user_count scope_post_count scope_notification_count duplicate_post_likes duplicate_bookmarks duplicate_follows duplicate_blocks post_like_mismatch_posts negative_like_count_posts post_comment_mismatch_posts negative_comment_count_posts unread_notification_mismatch_users negative_unread_notification_users <"$sql_snapshot_file"

  http_p95="$(extract_summary_value "$log_file" "http_req_duration" "p95")"
  http_p99="$(extract_summary_value "$log_file" "http_req_duration" "p99")"
  unexpected_response_rate="$(extract_rate_value "$log_file" "unexpected_response_rate")"

  duplicate_key_conflicts="$(extract_json_number "$server_metrics_file" "duplicateKeyConflicts")"
  db_pool_timeouts="$(extract_json_number "$server_metrics_file" "dbPoolTimeouts")"
  max_active_connections="$(extract_json_number "$server_metrics_file" "maxActiveConnections")"
  max_threads_awaiting="$(extract_json_number "$server_metrics_file" "maxThreadsAwaitingConnection")"

  relation_duplicate_rows=$((duplicate_post_likes + duplicate_bookmarks + duplicate_follows + duplicate_blocks))
  invariant_failures=$((relation_duplicate_rows + post_like_mismatch_posts + negative_like_count_posts + post_comment_mismatch_posts + negative_comment_count_posts + unread_notification_mismatch_users + negative_unread_notification_users))
  stop_reason=""
  status="PASS"
  hard_failure=0

  if (( k6_exit_code != 0 )) && (( k6_exit_code != 99 )); then
    stop_reason="$(append_reason "$stop_reason" "k6-error")"
    status="FAIL"
    hard_failure=1
  fi

  if [[ "$unexpected_response_rate" != "0.0000" ]]; then
    stop_reason="$(append_reason "$stop_reason" "unexpected-response")"
    status="FAIL"
    hard_failure=1
  fi

  if (( duplicate_key_conflicts > 0 )); then
    stop_reason="$(append_reason "$stop_reason" "duplicate-key")"
    status="FAIL"
    hard_failure=1
  fi

  if (( invariant_failures > 0 )); then
    stop_reason="$(append_reason "$stop_reason" "sql-invariant")"
    status="FAIL"
    hard_failure=1
  fi

  if (( db_pool_timeouts > 0 )) && [[ "$status" == "PASS" ]]; then
    stop_reason="$(append_reason "$stop_reason" "db-pool-timeout")"
    status="LIMIT"
  fi

  if (( k6_exit_code == 99 )) && [[ "$status" == "PASS" ]] && (( STOP_ON_K6_THRESHOLD > 0 )); then
    stop_reason="$(append_reason "$stop_reason" "k6-threshold")"
    status="LIMIT"
  fi

  http_p99_ms="$(trim_ms "$http_p99")"
  if [[ "$status" == "PASS" ]] && is_greater_than "$http_p99_ms" "$STOP_ON_HTTP_P99_MS"; then
    stop_reason="$(append_reason "$stop_reason" "http-p99-threshold")"
    status="LIMIT"
  fi

  if [[ -z "$stop_reason" ]]; then
    stop_reason="stable"
  fi

  printf '| %s | `%s` | %s | `%s` | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |\n' \
    "$stage_number" "$factor" "$status" "$stop_reason" "$http_p95" "$http_p99" "$unexpected_response_rate" \
    "$duplicate_key_conflicts" "$db_pool_timeouts" "$max_active_connections" "$max_threads_awaiting" \
    "$relation_duplicate_rows" "$post_like_mismatch_posts" "$post_comment_mismatch_posts" "$unread_notification_mismatch_users" >>"$SUMMARY_REPORT_FILE"

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$stage_number" "$factor" "$status" "$stop_reason" "$(trim_ms "$http_p95")" "$http_p99_ms" "$unexpected_response_rate" \
    "$duplicate_key_conflicts" "$db_pool_timeouts" "$max_active_connections" "$max_threads_awaiting" "$relation_duplicate_rows" \
    "$duplicate_post_likes" "$duplicate_bookmarks" "$duplicate_follows" "$duplicate_blocks" "$post_like_mismatch_posts" "$negative_like_count_posts" \
    "$post_comment_mismatch_posts" "$negative_comment_count_posts" "$unread_notification_mismatch_users" "$negative_unread_notification_users" \
    "$scope_user_count" "$scope_post_count" "$scope_notification_count" >>"$SUMMARY_TSV_FILE"

  printf 'stage=%s factor=%s status=%s reason=%s http_p95=%s http_p99=%s unexpected=%s dupKey=%s poolTimeout=%s maxActive=%s maxWaiting=%s relationDup=%s likeMismatch=%s commentMismatch=%s unreadMismatch=%s\n' \
    "$stage_number" "$factor" "$status" "$stop_reason" "$http_p95" "$http_p99" "$unexpected_response_rate" \
    "$duplicate_key_conflicts" "$db_pool_timeouts" "$max_active_connections" "$max_threads_awaiting" \
    "$relation_duplicate_rows" "$post_like_mismatch_posts" "$post_comment_mismatch_posts" "$unread_notification_mismatch_users"

  if [[ "$status" == "PASS" ]]; then
    highest_stable_factor="$factor"
    highest_stable_stage="$stage_number"
    continue
  fi

  limit_reached=1
  first_limit_stage="$stage_number"
  first_limit_reason="$stop_reason"

  if (( hard_failure > 0 )); then
    suite_exit_code=1
  elif (( EXIT_ON_LIMIT_REACHED > 0 )); then
    suite_exit_code=1
  fi
  break
done

printf '\n## Aggregate\n\n' >>"$SUMMARY_REPORT_FILE"
printf -- '- highest stable stage: `%s`\n' "$highest_stable_stage" >>"$SUMMARY_REPORT_FILE"
printf -- '- highest stable factor: `%s`\n' "$highest_stable_factor" >>"$SUMMARY_REPORT_FILE"
printf -- '- first limit stage: `%s`\n' "$first_limit_stage" >>"$SUMMARY_REPORT_FILE"
printf -- '- first limit reason: `%s`\n' "$first_limit_reason" >>"$SUMMARY_REPORT_FILE"

if (( limit_reached == 0 )); then
  printf -- '- result: `all configured stages stayed within thresholds`\n' >>"$SUMMARY_REPORT_FILE"
else
  printf -- '- result: `stopped at first breached stage`\n' >>"$SUMMARY_REPORT_FILE"
fi

printf 'suite report: %s\n' "$SUMMARY_REPORT_FILE"
printf 'suite tsv: %s\n' "$SUMMARY_TSV_FILE"

exit "$suite_exit_code"
