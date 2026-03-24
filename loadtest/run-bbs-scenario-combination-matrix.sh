#!/usr/bin/env bash
set -euo pipefail

BASE_PROFILES="${BASE_PROFILES:-browse_search,write,relation_mixed,notification}"
COMBINATION_SIZES="${COMBINATION_SIZES:-2,3}"
BASE_URL="${BASE_URL:-http://localhost:18080}"
RESULT_ROOT="${RESULT_ROOT:-loadtest/results}"
RUNS="${RUNS:-5}"
STAGE_FACTORS="${STAGE_FACTORS:-0.5,0.55,0.6}"
STOP_ON_HTTP_P99_MS="${STOP_ON_HTTP_P99_MS:-800}"
STOP_ON_K6_THRESHOLD="${STOP_ON_K6_THRESHOLD:-0}"
STOP_AFTER_FIRST_UNSTABLE_SIZE="${STOP_AFTER_FIRST_UNSTABLE_SIZE:-1}"
SUITE_TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
SUMMARY_REPORT_FILE="$RESULT_ROOT/scenario-combination-matrix-$SUITE_TIMESTAMP.md"
SUMMARY_TSV_FILE="$RESULT_ROOT/scenario-combination-matrix-$SUITE_TIMESTAMP.tsv"

mkdir -p "$RESULT_ROOT"

IFS=',' read -r -a profile_list <<<"$BASE_PROFILES"
IFS=',' read -r -a combination_sizes <<<"$COMBINATION_SIZES"
MAX_STAGE_FACTOR="${STAGE_FACTORS##*,}"

if (( ${#profile_list[@]} < 2 )); then
  printf 'BASE_PROFILES must contain at least 2 profiles.\n' >&2
  exit 1
fi

generate_combinations() {
  local target_size="$1"
  local start_index="$2"
  shift 2
  local current=("$@")

  if (( ${#current[@]} == target_size )); then
    local combination
    combination="$(IFS=+; printf '%s' "${current[*]}")"
    printf '%s\n' "$combination"
    return
  fi

  local remaining_needed=$(( target_size - ${#current[@]} ))
  local max_start=$(( ${#profile_list[@]} - remaining_needed ))
  local i
  for (( i=start_index; i<=max_start; i++ )); do
    generate_combinations "$target_size" "$((i + 1))" "${current[@]}" "${profile_list[i]}"
  done
}

printf '# BBS Scenario Combination Matrix\n\n' >"$SUMMARY_REPORT_FILE"
printf -- '- executed_at: %s\n' "$SUITE_TIMESTAMP" >>"$SUMMARY_REPORT_FILE"
printf -- '- base_profiles: `%s`\n' "$BASE_PROFILES" >>"$SUMMARY_REPORT_FILE"
printf -- '- combination_sizes: `%s`\n' "$COMBINATION_SIZES" >>"$SUMMARY_REPORT_FILE"
printf -- '- base_url: `%s`\n' "$BASE_URL" >>"$SUMMARY_REPORT_FILE"
printf -- '- runs: `%s`\n' "$RUNS" >>"$SUMMARY_REPORT_FILE"
printf -- '- stage_factors: `%s`\n' "$STAGE_FACTORS" >>"$SUMMARY_REPORT_FILE"
printf -- '- stop_on_http_p99_ms: `%s`\n' "$STOP_ON_HTTP_P99_MS" >>"$SUMMARY_REPORT_FILE"
printf -- '- stop_on_k6_threshold: `%s`\n\n' "$STOP_ON_K6_THRESHOLD" >>"$SUMMARY_REPORT_FILE"
printf -- '- stop_after_first_unstable_size: `%s`\n\n' "$STOP_AFTER_FIRST_UNSTABLE_SIZE" >>"$SUMMARY_REPORT_FILE"
printf '| size | profile | suite exit | highest stable factor | unstable | pass summary | aggregate |\n' >>"$SUMMARY_REPORT_FILE"
printf '| --- | --- | --- | --- | --- | --- | --- |\n' >>"$SUMMARY_REPORT_FILE"

printf 'size\tprofile\tsuite_exit_code\thighest_stable_factor\tunstable\tpass_summary\taggregate_file\treport_file\n' >"$SUMMARY_TSV_FILE"

suite_exit_code=0

for size in "${combination_sizes[@]}"; do
  if (( size < 2 )); then
    printf 'COMBINATION_SIZES entries must be >= 2: %s\n' "$size" >&2
    exit 1
  fi
  if (( size > ${#profile_list[@]} )); then
    continue
  fi

  unstable_in_size=0
  while IFS= read -r combination; do
    [[ -z "$combination" ]] && continue
    combination_result_root="$RESULT_ROOT/scenario-combination-${combination}-$SUITE_TIMESTAMP"

    # 문제 해결:
    # broad mixed 가 흔들릴 때는 "어느 pair/triple 조합이 현재 코드에서 다시 최소 재현 조건인가"
    # 를 자동으로 재확정할 수 있어야 한다. 각 조합을 같은 조건으로 반복 측정하고
    # stable factor 를 한 표에 모아야 수작업 비교 없이 바로 최소 불안정 조합을 좁힐 수 있다.
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
      printf 'combination=%s failed: result files not found\n' "$combination" >&2
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
    if (( combination_exit_code != 0 )) || [[ "$highest_stable_factor" != "$MAX_STAGE_FACTOR" ]]; then
      unstable="1"
      suite_exit_code=1
      unstable_in_size=1
    fi

    printf '| `%s` | `%s` | `%s` | `%s` | `%s` | %s | [%s](%s) |\n' \
      "$size" \
      "$combination" \
      "$combination_exit_code" \
      "$highest_stable_factor" \
      "$unstable" \
      "$pass_summary" \
      "$(basename "$aggregate_file")" \
      "/home/admin0/effective-disco/$aggregate_file" >>"$SUMMARY_REPORT_FILE"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
      "$size" \
      "$combination" \
      "$combination_exit_code" \
      "$highest_stable_factor" \
      "$unstable" \
      "$pass_summary" \
      "$aggregate_file" \
      "$report_file" >>"$SUMMARY_TSV_FILE"
  done < <(generate_combinations "$size" 0)

  # 문제 해결:
  # 목적이 "최소 재현 크기" 확정일 때는, 더 작은 size에서 이미 unstable 조합이 나오면
  # 그보다 큰 조합은 결론에 필요하지 않다. 같은 실행을 반복해 broad mixed 분해 시간이
  # 불필요하게 길어지는 것을 막기 위해 기본적으로 첫 unstable size에서 중단한다.
  if (( STOP_AFTER_FIRST_UNSTABLE_SIZE > 0 )) && (( unstable_in_size > 0 )); then
    break
  fi
done

smallest_unstable_size="$(
  awk -F '\t' '
    NR == 1 { next }
    $5 == "1" {
      if (smallest == "" || $1 + 0 < smallest) {
        smallest = $1 + 0
      }
    }
    END {
      if (smallest == "") print "n/a"
      else print smallest
    }
  ' "$SUMMARY_TSV_FILE"
)"

printf '\n## Smallest Unstable Combinations\n\n' >>"$SUMMARY_REPORT_FILE"

if [[ "$smallest_unstable_size" == "n/a" ]]; then
  printf -- '- none within the tested combination sizes\n' >>"$SUMMARY_REPORT_FILE"
else
  printf -- '- smallest unstable size: `%s`\n' "$smallest_unstable_size" >>"$SUMMARY_REPORT_FILE"
  printf '| profile | highest stable factor | pass summary |\n' >>"$SUMMARY_REPORT_FILE"
  printf '| --- | --- | --- |\n' >>"$SUMMARY_REPORT_FILE"
  awk -F '\t' -v target_size="$smallest_unstable_size" '
    NR == 1 { next }
    ($1 == target_size) && ($5 == "1") {
      printf "| `%s` | `%s` | %s |\n", $2, $4, $6
    }
  ' "$SUMMARY_TSV_FILE" >>"$SUMMARY_REPORT_FILE"
fi

printf '\nsummary report: %s\n' "$SUMMARY_REPORT_FILE"
printf 'summary tsv: %s\n' "$SUMMARY_TSV_FILE"

exit "$suite_exit_code"
