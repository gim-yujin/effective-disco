#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:18080}"
RESULT_DIR="${RESULT_DIR:-loadtest/results}"
RUNS="${RUNS:-5}"
STAGE_FACTORS="${STAGE_FACTORS:-0.75,0.85,0.9,0.95,1.0}"
STOP_ON_HTTP_P99_MS="${STOP_ON_HTTP_P99_MS:-800}"
STOP_ON_K6_THRESHOLD="${STOP_ON_K6_THRESHOLD:-0}"
SUITE_TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
SUMMARY_REPORT_FILE="$RESULT_DIR/sub-stability-$SUITE_TIMESTAMP.md"
SUMMARY_TSV_FILE="$RESULT_DIR/sub-stability-$SUITE_TIMESTAMP.tsv"

mkdir -p "$RESULT_DIR"

if ! command -v awk >/dev/null 2>&1; then
  printf 'awk is not installed.\n' >&2
  exit 1
fi

trim_ms() {
  printf '%s' "${1%ms}"
}

printf '# BBS Sub-1.0 Stability Suite\n\n' >"$SUMMARY_REPORT_FILE"
printf -- '- executed_at: %s\n' "$SUITE_TIMESTAMP" >>"$SUMMARY_REPORT_FILE"
printf -- '- base_url: `%s`\n' "$BASE_URL" >>"$SUMMARY_REPORT_FILE"
printf -- '- runs: `%s`\n' "$RUNS" >>"$SUMMARY_REPORT_FILE"
printf -- '- stage_factors: `%s`\n' "$STAGE_FACTORS" >>"$SUMMARY_REPORT_FILE"
printf -- '- stop_on_http_p99_ms: `%s`\n' "$STOP_ON_HTTP_P99_MS" >>"$SUMMARY_REPORT_FILE"
printf -- '- stop_on_k6_threshold: `%s`\n\n' "$STOP_ON_K6_THRESHOLD" >>"$SUMMARY_REPORT_FILE"
printf '| run | factor | status | stop reason | http p95 | http p99 | unexpected | pool timeout | max waiting |\n' >>"$SUMMARY_REPORT_FILE"
printf '| --- | --- | --- | --- | --- | --- | --- | --- | --- |\n' >>"$SUMMARY_REPORT_FILE"

printf 'run\tstage\tfactor\tstatus\tstop_reason\thttp_p95_ms\thttp_p99_ms\tunexpected_response_rate\tduplicate_key_conflicts\tdb_pool_timeouts\tmax_active_connections\tmax_threads_awaiting\trelation_duplicate_rows\tduplicate_post_likes\tduplicate_bookmarks\tduplicate_follows\tduplicate_blocks\tpost_like_mismatch_posts\tnegative_like_count_posts\tpost_comment_mismatch_posts\tnegative_comment_count_posts\tunread_notification_mismatch_users\tnegative_unread_notification_users\tscope_user_count\tscope_post_count\tscope_notification_count\n' >"$SUMMARY_TSV_FILE"

suite_failures=0

for run in $(seq 1 "$RUNS"); do
  run_id="$(printf '%02d' "$run")"
  run_dir="$RESULT_DIR/sub-stability-$SUITE_TIMESTAMP-run${run_id}"
  run_log="$run_dir/runner.log"
  mkdir -p "$run_dir"

  # 문제 해결:
  # sub-1.0 안정 구간은 "같은 factor를 여러 번 돌렸을 때 항상 PASS 인가"가 핵심이다.
  # run별 raw ramp-up 결과를 그대로 남겨야 특정 factor가 운 좋게 통과했는지 재현성 있게 비교할 수 있다.
  set +e
  RESULT_DIR="$run_dir" \
  BASE_URL="$BASE_URL" \
  STAGE_FACTORS="$STAGE_FACTORS" \
  STOP_ON_HTTP_P99_MS="$STOP_ON_HTTP_P99_MS" \
  STOP_ON_K6_THRESHOLD="$STOP_ON_K6_THRESHOLD" \
  ./loadtest/run-bbs-ramp-up.sh >"$run_log" 2>&1
  run_exit_code=$?
  set -e

  run_tsv="$(find "$run_dir" -maxdepth 1 -name 'ramp-up-*.tsv' | sort | tail -n1)"
  if [[ -z "$run_tsv" ]]; then
    printf 'run=%s failed: no ramp-up tsv found\n' "$run_id" >&2
    exit 1
  fi

  awk -F '\t' -v run_id="$run_id" 'NR > 1 {printf "%s\t%s\n", run_id, $0}' "$run_tsv" >>"$SUMMARY_TSV_FILE"
  awk -F '\t' -v report_file="$SUMMARY_REPORT_FILE" -v run_id="$run_id" '
    NR > 1 {
      printf "| %s | `%s` | %s | `%s` | %sms | %sms | %s | %s | %s |\n",
        run_id, $2, $3, $4, $5, $6, $7, $9, $11 >> report_file
    }
  ' "$run_tsv"

  if (( run_exit_code != 0 )); then
    suite_failures=1
  fi
done

aggregate_file="$RESULT_DIR/sub-stability-$SUITE_TIMESTAMP-aggregate.tsv"
awk -F '\t' '
  NR == 1 { next }
  {
    factor = $3
    total[factor]++
    if ($4 == "PASS") pass[factor]++
    if ($4 == "LIMIT") limit[factor]++
    if ($4 == "FAIL") fail[factor]++
    if (!(factor in max_p99) || $7 > max_p99[factor]) max_p99[factor] = $7
    if (!(factor in max_pool_timeout) || $10 > max_pool_timeout[factor]) max_pool_timeout[factor] = $10
    if (!(factor in max_waiting) || $12 > max_waiting[factor]) max_waiting[factor] = $12
  }
  END {
    for (factor in total) {
      printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\n",
        factor,
        pass[factor] + 0,
        limit[factor] + 0,
        fail[factor] + 0,
        max_p99[factor] + 0,
        max_pool_timeout[factor] + 0,
        max_waiting[factor] + 0
    }
  }
' "$SUMMARY_TSV_FILE" | sort -g >"$aggregate_file"

highest_stable_factor="$(
  awk -F '\t' '
    ($2 > 0) && ($3 == 0) && ($4 == 0) { stable = $1 }
    END {
      if (stable == "") print "n/a"
      else print stable
    }
  ' "$aggregate_file"
)"

printf '\n## Aggregate By Factor\n\n' >>"$SUMMARY_REPORT_FILE"
printf '| factor | pass | limit | fail | max http p99 | max pool timeout | max waiting |\n' >>"$SUMMARY_REPORT_FILE"
printf '| --- | --- | --- | --- | --- | --- | --- |\n' >>"$SUMMARY_REPORT_FILE"
awk -F '\t' '
  {
    printf "| `%s` | %s | %s | %s | %sms | %s | %s |\n", $1, $2, $3, $4, $5, $6, $7
  }
' "$aggregate_file" >>"$SUMMARY_REPORT_FILE"
printf '\n- highest stable factor: `%s`\n' "$highest_stable_factor" >>"$SUMMARY_REPORT_FILE"

printf 'suite report: %s\n' "$SUMMARY_REPORT_FILE"
printf 'suite tsv: %s\n' "$SUMMARY_TSV_FILE"
printf 'aggregate tsv: %s\n' "$aggregate_file"

if (( suite_failures > 0 )); then
  exit 1
fi
