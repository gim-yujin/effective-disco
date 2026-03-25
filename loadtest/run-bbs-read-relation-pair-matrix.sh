#!/usr/bin/env bash
set -euo pipefail

READ_PROFILES="${READ_PROFILES:-browse_board_feed,hot_post_details,search_catalog,tag_search,sort_catalog}"
RELATION_PROFILES="${RELATION_PROFILES:-like_mixed,bookmark_mixed,follow_mixed,block_mixed}"
BASE_URL="${BASE_URL:-http://localhost:18080}"
RESULT_ROOT="${RESULT_ROOT:-loadtest/results}"
RUNS="${RUNS:-3}"
STAGE_FACTORS="${STAGE_FACTORS:-0.6}"
STOP_ON_HTTP_P99_MS="${STOP_ON_HTTP_P99_MS:-800}"
STOP_ON_K6_THRESHOLD="${STOP_ON_K6_THRESHOLD:-0}"
SUITE_TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
SUMMARY_REPORT_FILE="$RESULT_ROOT/read-relation-pair-matrix-$SUITE_TIMESTAMP.md"
SUMMARY_TSV_FILE="$RESULT_ROOT/read-relation-pair-matrix-$SUITE_TIMESTAMP.tsv"

mkdir -p "$RESULT_ROOT"

IFS=',' read -r -a read_profile_list <<<"$READ_PROFILES"
IFS=',' read -r -a relation_profile_list <<<"$RELATION_PROFILES"

printf '# BBS Read × Relation Pair Matrix\n\n' >"$SUMMARY_REPORT_FILE"
printf -- '- executed_at: %s\n' "$SUITE_TIMESTAMP" >>"$SUMMARY_REPORT_FILE"
printf -- '- read_profiles: `%s`\n' "$READ_PROFILES" >>"$SUMMARY_REPORT_FILE"
printf -- '- relation_profiles: `%s`\n' "$RELATION_PROFILES" >>"$SUMMARY_REPORT_FILE"
printf -- '- base_url: `%s`\n' "$BASE_URL" >>"$SUMMARY_REPORT_FILE"
printf -- '- runs: `%s`\n' "$RUNS" >>"$SUMMARY_REPORT_FILE"
printf -- '- stage_factors: `%s`\n' "$STAGE_FACTORS" >>"$SUMMARY_REPORT_FILE"
printf -- '- stop_on_http_p99_ms: `%s`\n' "$STOP_ON_HTTP_P99_MS" >>"$SUMMARY_REPORT_FILE"
printf -- '- stop_on_k6_threshold: `%s`\n\n' "$STOP_ON_K6_THRESHOLD" >>"$SUMMARY_REPORT_FILE"
printf '| read profile | relation profile | suite exit | highest stable factor | unstable | pass summary | aggregate |\n' >>"$SUMMARY_REPORT_FILE"
printf '| --- | --- | --- | --- | --- | --- | --- |\n' >>"$SUMMARY_REPORT_FILE"

printf 'read_profile\trelation_profile\tsuite_exit_code\thighest_stable_factor\tunstable\tpass_summary\taggregate_file\treport_file\n' >"$SUMMARY_TSV_FILE"

suite_exit_code=0

for read_profile in "${read_profile_list[@]}"; do
  for relation_profile in "${relation_profile_list[@]}"; do
    combination="${read_profile}+${relation_profile}"
    combination_result_root="$RESULT_ROOT/read-relation-${combination}-$SUITE_TIMESTAMP"

    # 문제 해결:
    # broad mixed 최소 재현 조합이 `browse_search + relation_mixed` 수준까지는 좁혀졌지만,
    # 실제로는 "어느 read path" 와 "어느 relation write" 가 맞물릴 때 깨지는지 더 세밀하게 봐야 한다.
    # read path 와 relation write 의 카테시안 곱을 같은 factor에서 반복 측정해
    # 현재 코드 기준 최소 재현 pair를 자동으로 다시 찾는다.
    set +e
    SCENARIO_PROFILE="$combination" \
    RESULT_ROOT="$combination_result_root" \
    BASE_URL="$BASE_URL" \
    RUNS="$RUNS" \
    STAGE_FACTORS="$STAGE_FACTORS" \
    STOP_ON_HTTP_P99_MS="$STOP_ON_HTTP_P99_MS" \
    STOP_ON_K6_THRESHOLD="$STOP_ON_K6_THRESHOLD" \
    ./loadtest/run-bbs-scenario-sub-stability.sh
    combination_exit_code=$?
    set -e

    report_file="$(find "$combination_result_root" -name 'sub-stability-*.md' | sort | tail -n1)"
    aggregate_file="$(find "$combination_result_root" -name 'sub-stability-*-aggregate.tsv' | sort | tail -n1)"

    if [[ -z "$report_file" || -z "$aggregate_file" ]]; then
      printf 'pair=%s failed: result files not found\n' "$combination" >&2
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

    unstable="0"
    if (( combination_exit_code != 0 )); then
      unstable="1"
      suite_exit_code=1
    fi

    printf '| `%s` | `%s` | `%s` | `%s` | `%s` | %s | [%s](%s) |\n' \
      "$read_profile" \
      "$relation_profile" \
      "$combination_exit_code" \
      "$highest_stable_factor" \
      "$unstable" \
      "$pass_summary" \
      "$(basename "$aggregate_file")" \
      "/home/admin0/effective-disco/$aggregate_file" >>"$SUMMARY_REPORT_FILE"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
      "$read_profile" \
      "$relation_profile" \
      "$combination_exit_code" \
      "$highest_stable_factor" \
      "$unstable" \
      "$pass_summary" \
      "$aggregate_file" \
      "$report_file" >>"$SUMMARY_TSV_FILE"
  done
done

printf '\n## Unstable Pairs\n\n' >>"$SUMMARY_REPORT_FILE"
if awk -F '\t' 'NR > 1 && $5 == "1" { found = 1 } END { exit found ? 0 : 1 }' "$SUMMARY_TSV_FILE"; then
  printf '| read profile | relation profile | highest stable factor | pass summary |\n' >>"$SUMMARY_REPORT_FILE"
  printf '| --- | --- | --- | --- |\n' >>"$SUMMARY_REPORT_FILE"
  awk -F '\t' '
    NR > 1 && $5 == "1" {
      printf "| `%s` | `%s` | `%s` | %s |\n", $1, $2, $4, $6
    }
  ' "$SUMMARY_TSV_FILE" >>"$SUMMARY_REPORT_FILE"
else
  printf -- '- none\n' >>"$SUMMARY_REPORT_FILE"
fi

printf '\nsummary report: %s\n' "$SUMMARY_REPORT_FILE"
printf 'summary tsv: %s\n' "$SUMMARY_TSV_FILE"

exit "$suite_exit_code"
