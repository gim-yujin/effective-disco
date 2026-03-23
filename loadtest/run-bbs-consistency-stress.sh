#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:18080}"
RESULT_DIR="${RESULT_DIR:-loadtest/results}"
RUNS="${RUNS:-5}"
SUITE_TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
SUITE_ID="$(date +%m%d%H%M%S)"
SQL_CHECK_FILE="loadtest/sql/consistency-checks.sql"
SUMMARY_REPORT_FILE="$RESULT_DIR/consistency-stress-$SUITE_TIMESTAMP.md"
SUMMARY_TSV_FILE="$RESULT_DIR/consistency-stress-$SUITE_TIMESTAMP.tsv"

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

printf '# BBS Consistency Stress Suite\n\n' >"$SUMMARY_REPORT_FILE"
printf -- '- executed_at: %s\n' "$SUITE_TIMESTAMP" >>"$SUMMARY_REPORT_FILE"
printf -- '- base_url: `%s`\n' "$BASE_URL" >>"$SUMMARY_REPORT_FILE"
printf -- '- runs: `%s`\n' "$RUNS" >>"$SUMMARY_REPORT_FILE"
printf -- '- database: `%s:%s/%s`\n\n' "$PGHOST" "$PGPORT" "$PGDATABASE" >>"$SUMMARY_REPORT_FILE"
printf '| run | prefix | status | http p95 | http p99 | unexpected | dup-key | pool timeout | max active | max waiting | relation dup | like mismatch | comment mismatch | unread mismatch |\n' >>"$SUMMARY_REPORT_FILE"
printf '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n' >>"$SUMMARY_REPORT_FILE"

printf 'run\tprefix\tstatus\thttp_p95_ms\thttp_p99_ms\tunexpected_response_rate\tduplicate_key_conflicts\tdb_pool_timeouts\tmax_active_connections\tmax_threads_awaiting\trelation_duplicate_rows\tduplicate_post_likes\tduplicate_bookmarks\tduplicate_follows\tduplicate_blocks\tpost_like_mismatch_posts\tnegative_like_count_posts\tpost_comment_mismatch_posts\tnegative_comment_count_posts\tunread_notification_mismatch_users\tnegative_unread_notification_users\tscope_user_count\tscope_post_count\tscope_notification_count\n' >"$SUMMARY_TSV_FILE"

trim_ms() {
  printf '%s' "${1%ms}"
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

suite_failures=0

for run in $(seq 1 "$RUNS"); do
  run_id="$(printf '%02d' "$run")"
  prefix="lts${SUITE_ID}r${run_id}"
  log_file="$RESULT_DIR/consistency-stress-$SUITE_TIMESTAMP-run${run_id}.log"
  k6_summary_file="$RESULT_DIR/consistency-stress-$SUITE_TIMESTAMP-run${run_id}-k6.json"
  server_metrics_file="$RESULT_DIR/consistency-stress-$SUITE_TIMESTAMP-run${run_id}-server.json"
  sql_snapshot_file="$RESULT_DIR/consistency-stress-$SUITE_TIMESTAMP-run${run_id}-sql.tsv"

  curl -fsS -X POST "$BASE_URL/internal/load-test/reset" >/dev/null

  # 문제 해결:
  # 반복 실행 결과를 "이번 런" 단위로 재현하려면 부하 시나리오와 SQL 검증 대상이
  # 같은 prefix를 바라봐야 한다. 고정 prefix를 k6와 psql에 같이 주입해 범위를 맞춘다.
  # threshold를 넘는 런도 서버/SQL 스냅샷은 남겨야 이후 원인을 볼 수 있다.
  # k6 종료 코드 99를 흡수하고 PASS/FAIL 판정은 스크립트에서 다시 계산한다.
  set +e
  LOADTEST_PREFIX="$prefix" \
  BASE_URL="$BASE_URL" \
  K6_SUMMARY_FILE="$k6_summary_file" \
  BROWSE_RATE="${BROWSE_RATE:-80}" BROWSE_DURATION="${BROWSE_DURATION:-45s}" BROWSE_PRE_ALLOCATED_VUS="${BROWSE_PRE_ALLOCATED_VUS:-40}" BROWSE_MAX_VUS="${BROWSE_MAX_VUS:-120}" \
  HOT_POST_RATE="${HOT_POST_RATE:-120}" HOT_POST_DURATION="${HOT_POST_DURATION:-45s}" HOT_POST_PRE_ALLOCATED_VUS="${HOT_POST_PRE_ALLOCATED_VUS:-50}" HOT_POST_MAX_VUS="${HOT_POST_MAX_VUS:-150}" \
  SEARCH_RATE="${SEARCH_RATE:-40}" SEARCH_DURATION="${SEARCH_DURATION:-45s}" SEARCH_PRE_ALLOCATED_VUS="${SEARCH_PRE_ALLOCATED_VUS:-20}" SEARCH_MAX_VUS="${SEARCH_MAX_VUS:-80}" \
  WRITE_START_RATE="${WRITE_START_RATE:-8}" WRITE_STAGE_ONE_RATE="${WRITE_STAGE_ONE_RATE:-20}" WRITE_STAGE_ONE_DURATION="${WRITE_STAGE_ONE_DURATION:-30s}" WRITE_STAGE_TWO_RATE="${WRITE_STAGE_TWO_RATE:-35}" WRITE_STAGE_TWO_DURATION="${WRITE_STAGE_TWO_DURATION:-30s}" WRITE_PRE_ALLOCATED_VUS="${WRITE_PRE_ALLOCATED_VUS:-20}" WRITE_MAX_VUS="${WRITE_MAX_VUS:-80}" \
  LIKE_ADD_VUS="${LIKE_ADD_VUS:-80}" LIKE_ADD_DURATION="${LIKE_ADD_DURATION:-45s}" \
  LIKE_REMOVE_VUS="${LIKE_REMOVE_VUS:-80}" LIKE_REMOVE_DURATION="${LIKE_REMOVE_DURATION:-45s}" \
  BOOKMARK_MIXED_VUS="${BOOKMARK_MIXED_VUS:-60}" BOOKMARK_MIXED_DURATION="${BOOKMARK_MIXED_DURATION:-45s}" \
  FOLLOW_MIXED_VUS="${FOLLOW_MIXED_VUS:-60}" FOLLOW_MIXED_DURATION="${FOLLOW_MIXED_DURATION:-45s}" \
  BLOCK_MIXED_VUS="${BLOCK_MIXED_VUS:-60}" BLOCK_MIXED_DURATION="${BLOCK_MIXED_DURATION:-45s}" \
  NOTIFICATION_MIXED_VUS="${NOTIFICATION_MIXED_VUS:-40}" NOTIFICATION_MIXED_DURATION="${NOTIFICATION_MIXED_DURATION:-45s}" \
  k6 run --summary-trend-stats "avg,min,med,max,p(90),p(95),p(99)" loadtest/k6/bbs-load.js | tee "$log_file"
  k6_exit_code=$?
  set -e

  curl -fsS "$BASE_URL/internal/load-test/metrics" >"$server_metrics_file"
  psql -v ON_ERROR_STOP=1 -v loadtest_prefix="$prefix" -F $'\t' -At -f "$SQL_CHECK_FILE" >"$sql_snapshot_file"

  IFS=$'\t' read -r scope_user_count scope_post_count scope_notification_count duplicate_post_likes duplicate_bookmarks duplicate_follows duplicate_blocks post_like_mismatch_posts negative_like_count_posts post_comment_mismatch_posts negative_comment_count_posts unread_notification_mismatch_users negative_unread_notification_users <"$sql_snapshot_file"

  http_p95="$(extract_summary_value "$log_file" "http_req_duration" "p95")"
  http_p99="$(extract_summary_value "$log_file" "http_req_duration" "p99")"
  unexpected_response_rate="$(extract_rate_value "$log_file" "unexpected_response_rate")"

  duplicate_key_conflicts="$(extract_json_number "$server_metrics_file" "duplicateKeyConflicts")"
  db_pool_timeouts="$(extract_json_number "$server_metrics_file" "dbPoolTimeouts")"
  max_active_connections="$(extract_json_number "$server_metrics_file" "maxActiveConnections")"
  max_threads_awaiting="$(extract_json_number "$server_metrics_file" "maxThreadsAwaitingConnection")"

  # 문제 해결:
  # 좋아요만 보면 실제 mixed scenario에서 follow/bookmark/block 중복 row 누락을 놓친다.
  # 관계 테이블 중복을 하나의 합계로 묶어 PASS/FAIL 판정에 반영한다.
  relation_duplicate_rows=$((duplicate_post_likes + duplicate_bookmarks + duplicate_follows + duplicate_blocks))
  invariant_failures=$((relation_duplicate_rows + post_like_mismatch_posts + negative_like_count_posts + post_comment_mismatch_posts + negative_comment_count_posts + unread_notification_mismatch_users + negative_unread_notification_users))
  status="PASS"

  if (( k6_exit_code != 0 )) && (( k6_exit_code != 99 )); then
    status="FAIL"
    suite_failures=1
  fi

  if (( k6_exit_code == 99 )) || [[ "$unexpected_response_rate" != "0.0000" ]] || (( duplicate_key_conflicts > 0 )) || (( invariant_failures > 0 )); then
    status="FAIL"
    suite_failures=1
  fi

  printf '| %s | `%s` | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |\n' \
    "$run_id" "$prefix" "$status" "$http_p95" "$http_p99" "$unexpected_response_rate" \
    "$duplicate_key_conflicts" "$db_pool_timeouts" "$max_active_connections" "$max_threads_awaiting" \
    "$relation_duplicate_rows" "$post_like_mismatch_posts" "$post_comment_mismatch_posts" "$unread_notification_mismatch_users" >>"$SUMMARY_REPORT_FILE"

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$run_id" "$prefix" "$status" "$(trim_ms "$http_p95")" "$(trim_ms "$http_p99")" "$unexpected_response_rate" \
    "$duplicate_key_conflicts" "$db_pool_timeouts" "$max_active_connections" "$max_threads_awaiting" \
    "$relation_duplicate_rows" "$duplicate_post_likes" "$duplicate_bookmarks" "$duplicate_follows" "$duplicate_blocks" "$post_like_mismatch_posts" "$negative_like_count_posts" "$post_comment_mismatch_posts" "$negative_comment_count_posts" \
    "$unread_notification_mismatch_users" "$negative_unread_notification_users" "$scope_user_count" "$scope_post_count" "$scope_notification_count" >>"$SUMMARY_TSV_FILE"

  printf 'run=%s prefix=%s status=%s http_p95=%s http_p99=%s unexpected=%s dupKey=%s poolTimeout=%s maxActive=%s maxWaiting=%s relationDup=%s likeMismatch=%s commentMismatch=%s unreadMismatch=%s\n' \
    "$run_id" "$prefix" "$status" "$http_p95" "$http_p99" "$unexpected_response_rate" \
    "$duplicate_key_conflicts" "$db_pool_timeouts" "$max_active_connections" "$max_threads_awaiting" \
    "$relation_duplicate_rows" "$post_like_mismatch_posts" "$post_comment_mismatch_posts" "$unread_notification_mismatch_users"
done

# 문제 해결:
# 1회 측정은 운에 크게 좌우될 수 있다. 반복 실행의 최댓값과 실패 수를 같이 남겨야
# "이번에 우연히 괜찮았다"와 "대체로 안정적이다"를 구분할 수 있다.
aggregate_line="$(
  awk -F '\t' '
    NR == 2 {
      fail_count = 0
      max_p95 = $4
      max_p99 = $5
      max_dup = $7
      max_pool_timeout = $8
      max_active = $9
      max_waiting = $10
      max_relation_dup = $11
    }
    NR > 1 {
      if ($3 != "PASS") fail_count++
      if ($4 > max_p95) max_p95 = $4
      if ($5 > max_p99) max_p99 = $5
      if ($7 > max_dup) max_dup = $7
      if ($8 > max_pool_timeout) max_pool_timeout = $8
      if ($9 > max_active) max_active = $9
      if ($10 > max_waiting) max_waiting = $10
      if ($11 > max_relation_dup) max_relation_dup = $11
    }
    END {
      printf "%s\t%s\t%s\t%s\t%s\t%s\t%s", fail_count, max_p95, max_p99, max_dup, max_pool_timeout, max_waiting, max_relation_dup
    }
  ' "$SUMMARY_TSV_FILE"
)"

IFS=$'\t' read -r fail_count max_http_p95_ms max_http_p99_ms max_duplicate_key_conflicts max_db_pool_timeouts max_threads_awaiting max_relation_duplicate_rows <<<"$aggregate_line"

printf '\n## Aggregate\n\n' >>"$SUMMARY_REPORT_FILE"
printf -- '- failed runs: `%s/%s`\n' "$fail_count" "$RUNS" >>"$SUMMARY_REPORT_FILE"
printf -- '- max http_req_duration p95: `%sms`\n' "$max_http_p95_ms" >>"$SUMMARY_REPORT_FILE"
printf -- '- max http_req_duration p99: `%sms`\n' "$max_http_p99_ms" >>"$SUMMARY_REPORT_FILE"
printf -- '- max duplicateKeyConflicts: `%s`\n' "$max_duplicate_key_conflicts" >>"$SUMMARY_REPORT_FILE"
printf -- '- max dbPoolTimeouts: `%s`\n' "$max_db_pool_timeouts" >>"$SUMMARY_REPORT_FILE"
printf -- '- max maxThreadsAwaitingConnection: `%s`\n' "$max_threads_awaiting" >>"$SUMMARY_REPORT_FILE"
printf -- '- max relation duplicate rows: `%s`\n' "$max_relation_duplicate_rows" >>"$SUMMARY_REPORT_FILE"

printf 'suite report: %s\n' "$SUMMARY_REPORT_FILE"
printf 'suite tsv: %s\n' "$SUMMARY_TSV_FILE"

if (( suite_failures > 0 )); then
  exit 1
fi
