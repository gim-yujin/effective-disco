#!/usr/bin/env bash
set -euo pipefail

SCENARIO_PROFILES="${SCENARIO_PROFILES:-browse_search,write,relation_mixed,notification}"
BASE_URL="${BASE_URL:-http://localhost:18080}"
RESULT_ROOT="${RESULT_ROOT:-loadtest/results}"
RUNS="${RUNS:-5}"
STAGE_FACTORS="${STAGE_FACTORS:-0.5,0.55,0.6}"
STOP_ON_HTTP_P99_MS="${STOP_ON_HTTP_P99_MS:-800}"
STOP_ON_K6_THRESHOLD="${STOP_ON_K6_THRESHOLD:-0}"
SUITE_TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
SUMMARY_REPORT_FILE="$RESULT_ROOT/scenario-matrix-$SUITE_TIMESTAMP.md"
SUMMARY_TSV_FILE="$RESULT_ROOT/scenario-matrix-$SUITE_TIMESTAMP.tsv"

mkdir -p "$RESULT_ROOT"

printf '# BBS Scenario Matrix\n\n' >"$SUMMARY_REPORT_FILE"
printf -- '- executed_at: %s\n' "$SUITE_TIMESTAMP" >>"$SUMMARY_REPORT_FILE"
printf -- '- base_url: `%s`\n' "$BASE_URL" >>"$SUMMARY_REPORT_FILE"
printf -- '- runs: `%s`\n' "$RUNS" >>"$SUMMARY_REPORT_FILE"
printf -- '- stage_factors: `%s`\n' "$STAGE_FACTORS" >>"$SUMMARY_REPORT_FILE"
printf -- '- profiles: `%s`\n\n' "$SCENARIO_PROFILES" >>"$SUMMARY_REPORT_FILE"
printf '| profile | suite exit | highest stable factor | pass summary | aggregate |\n' >>"$SUMMARY_REPORT_FILE"
printf '| --- | --- | --- | --- | --- |\n' >>"$SUMMARY_REPORT_FILE"

printf 'profile\tsuite_exit_code\thighest_stable_factor\tpass_summary\taggregate_file\treport_file\n' >"$SUMMARY_TSV_FILE"

IFS=',' read -r -a profile_list <<<"$SCENARIO_PROFILES"
suite_exit_code=0

for profile in "${profile_list[@]}"; do
  profile_result_root="$RESULT_ROOT/scenario-${profile}-$SUITE_TIMESTAMP"

  # 문제 해결:
  # profile 하나가 LIMIT/FAIL 로 끝나도 나머지 profile 결과까지 전부 확보해야
  # "어느 시나리오가 가장 먼저 pool timeout을 만든다"는 비교가 가능하다.
  set +e
  SCENARIO_PROFILE="$profile" \
  RESULT_ROOT="$profile_result_root" \
  BASE_URL="$BASE_URL" \
  RUNS="$RUNS" \
  STAGE_FACTORS="$STAGE_FACTORS" \
  STOP_ON_HTTP_P99_MS="$STOP_ON_HTTP_P99_MS" \
  STOP_ON_K6_THRESHOLD="$STOP_ON_K6_THRESHOLD" \
  ./loadtest/run-bbs-scenario-sub-stability.sh
  profile_exit_code=$?
  set -e

  report_file="$(find "$profile_result_root" -name 'sub-stability-*.md' | sort | tail -n1)"
  aggregate_file="$(find "$profile_result_root" -name 'sub-stability-*-aggregate.tsv' | sort | tail -n1)"

  if [[ -z "$report_file" || -z "$aggregate_file" ]]; then
    printf 'profile=%s failed: result files not found\n' "$profile" >&2
    exit 1
  fi

  highest_stable_factor="$(sed -n 's/^- highest stable factor: `\(.*\)`$/\1/p' "$report_file" | tail -n1)"
  pass_summary="$(
    awk -F '\t' '
      BEGIN { first = 1 }
      {
        if (!first) {
          printf ", "
        }
        printf "%s=%sP/%sL/%sF", $1, $2, $3, $4
        first = 0
      }
    ' "$aggregate_file"
  )"

  printf '| `%s` | `%s` | `%s` | %s | [%s](%s) |\n' \
    "$profile" \
    "$profile_exit_code" \
    "$highest_stable_factor" \
    "$pass_summary" \
    "$(basename "$aggregate_file")" \
    "/home/admin0/effective-disco/$aggregate_file" >>"$SUMMARY_REPORT_FILE"
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$profile" \
    "$profile_exit_code" \
    "$highest_stable_factor" \
    "$pass_summary" \
    "$aggregate_file" \
    "$report_file" >>"$SUMMARY_TSV_FILE"

  if (( profile_exit_code != 0 )); then
    suite_exit_code=1
  fi
done

printf '\nsummary report: %s\n' "$SUMMARY_REPORT_FILE"
printf 'summary tsv: %s\n' "$SUMMARY_TSV_FILE"

exit "$suite_exit_code"
