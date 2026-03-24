#!/usr/bin/env bash
set -euo pipefail

SCENARIO_PROFILE="${SCENARIO_PROFILE:-browse_search}"
BASE_URL="${BASE_URL:-http://localhost:18080}"
RESULT_ROOT="${RESULT_ROOT:-loadtest/results}"
RUNS="${RUNS:-5}"
STAGE_FACTORS="${STAGE_FACTORS:-0.5,0.55,0.6}"
STOP_ON_HTTP_P99_MS="${STOP_ON_HTTP_P99_MS:-800}"
STOP_ON_K6_THRESHOLD="${STOP_ON_K6_THRESHOLD:-0}"
SUITE_TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
RESULT_DIR="${RESULT_DIR:-$RESULT_ROOT/scenario-${SCENARIO_PROFILE}-${SUITE_TIMESTAMP}}"

mkdir -p "$RESULT_DIR"

BROWSE_RATE=0
BROWSE_PRE_ALLOCATED_VUS=0
BROWSE_MAX_VUS=0
HOT_POST_RATE=0
HOT_POST_PRE_ALLOCATED_VUS=0
HOT_POST_MAX_VUS=0
SEARCH_RATE=0
SEARCH_PRE_ALLOCATED_VUS=0
SEARCH_MAX_VUS=0
WRITE_START_RATE=0
WRITE_STAGE_ONE_RATE=0
WRITE_STAGE_TWO_RATE=0
WRITE_PRE_ALLOCATED_VUS=0
WRITE_MAX_VUS=0
LIKE_ADD_VUS=0
LIKE_REMOVE_VUS=0
BOOKMARK_MIXED_VUS=0
FOLLOW_MIXED_VUS=0
BLOCK_MIXED_VUS=0
NOTIFICATION_MIXED_VUS=0

enable_profile_component() {
  local component="$1"
  case "$component" in
    browse_search)
      BROWSE_RATE="${BROWSE_RATE_OVERRIDE:-80}"
      BROWSE_PRE_ALLOCATED_VUS="${BROWSE_PRE_ALLOCATED_VUS_OVERRIDE:-40}"
      BROWSE_MAX_VUS="${BROWSE_MAX_VUS_OVERRIDE:-120}"
      HOT_POST_RATE="${HOT_POST_RATE_OVERRIDE:-120}"
      HOT_POST_PRE_ALLOCATED_VUS="${HOT_POST_PRE_ALLOCATED_VUS_OVERRIDE:-50}"
      HOT_POST_MAX_VUS="${HOT_POST_MAX_VUS_OVERRIDE:-150}"
      SEARCH_RATE="${SEARCH_RATE_OVERRIDE:-40}"
      SEARCH_PRE_ALLOCATED_VUS="${SEARCH_PRE_ALLOCATED_VUS_OVERRIDE:-20}"
      SEARCH_MAX_VUS="${SEARCH_MAX_VUS_OVERRIDE:-80}"
      ;;
    browse_board_feed)
      BROWSE_RATE="${BROWSE_RATE_OVERRIDE:-80}"
      BROWSE_PRE_ALLOCATED_VUS="${BROWSE_PRE_ALLOCATED_VUS_OVERRIDE:-40}"
      BROWSE_MAX_VUS="${BROWSE_MAX_VUS_OVERRIDE:-120}"
      ;;
    hot_post_details)
      HOT_POST_RATE="${HOT_POST_RATE_OVERRIDE:-120}"
      HOT_POST_PRE_ALLOCATED_VUS="${HOT_POST_PRE_ALLOCATED_VUS_OVERRIDE:-50}"
      HOT_POST_MAX_VUS="${HOT_POST_MAX_VUS_OVERRIDE:-150}"
      ;;
    search_catalog)
      SEARCH_RATE="${SEARCH_RATE_OVERRIDE:-40}"
      SEARCH_PRE_ALLOCATED_VUS="${SEARCH_PRE_ALLOCATED_VUS_OVERRIDE:-20}"
      SEARCH_MAX_VUS="${SEARCH_MAX_VUS_OVERRIDE:-80}"
      ;;
    write)
      WRITE_START_RATE="${WRITE_START_RATE_OVERRIDE:-8}"
      WRITE_STAGE_ONE_RATE="${WRITE_STAGE_ONE_RATE_OVERRIDE:-20}"
      WRITE_STAGE_TWO_RATE="${WRITE_STAGE_TWO_RATE_OVERRIDE:-35}"
      WRITE_PRE_ALLOCATED_VUS="${WRITE_PRE_ALLOCATED_VUS_OVERRIDE:-20}"
      WRITE_MAX_VUS="${WRITE_MAX_VUS_OVERRIDE:-80}"
      ;;
    relation_mixed)
      LIKE_ADD_VUS="${LIKE_ADD_VUS_OVERRIDE:-80}"
      LIKE_REMOVE_VUS="${LIKE_REMOVE_VUS_OVERRIDE:-80}"
      BOOKMARK_MIXED_VUS="${BOOKMARK_MIXED_VUS_OVERRIDE:-60}"
      FOLLOW_MIXED_VUS="${FOLLOW_MIXED_VUS_OVERRIDE:-60}"
      BLOCK_MIXED_VUS="${BLOCK_MIXED_VUS_OVERRIDE:-60}"
      ;;
    like_mixed)
      LIKE_ADD_VUS="${LIKE_ADD_VUS_OVERRIDE:-80}"
      LIKE_REMOVE_VUS="${LIKE_REMOVE_VUS_OVERRIDE:-80}"
      ;;
    bookmark_mixed)
      BOOKMARK_MIXED_VUS="${BOOKMARK_MIXED_VUS_OVERRIDE:-60}"
      ;;
    follow_mixed)
      FOLLOW_MIXED_VUS="${FOLLOW_MIXED_VUS_OVERRIDE:-60}"
      ;;
    block_mixed)
      BLOCK_MIXED_VUS="${BLOCK_MIXED_VUS_OVERRIDE:-60}"
      ;;
    notification)
      NOTIFICATION_MIXED_VUS="${NOTIFICATION_MIXED_VUS_OVERRIDE:-40}"
      ;;
    *)
      printf 'unsupported SCENARIO_PROFILE component: %s\n' "$component" >&2
      exit 1
      ;;
  esac
}

IFS='+' read -r -a profile_components <<<"$SCENARIO_PROFILE"
profile_description=""
for component in "${profile_components[@]}"; do
  enable_profile_component "$component"
  if [[ -n "$profile_description" ]]; then
    profile_description="$profile_description + "
  fi
  profile_description="$profile_description$component"
done

export BROWSE_RATE BROWSE_PRE_ALLOCATED_VUS BROWSE_MAX_VUS
export HOT_POST_RATE HOT_POST_PRE_ALLOCATED_VUS HOT_POST_MAX_VUS
export SEARCH_RATE SEARCH_PRE_ALLOCATED_VUS SEARCH_MAX_VUS
export WRITE_START_RATE WRITE_STAGE_ONE_RATE WRITE_STAGE_TWO_RATE WRITE_PRE_ALLOCATED_VUS WRITE_MAX_VUS
export LIKE_ADD_VUS LIKE_REMOVE_VUS BOOKMARK_MIXED_VUS FOLLOW_MIXED_VUS BLOCK_MIXED_VUS NOTIFICATION_MIXED_VUS

# 문제 해결:
# broad mixed 부하에서는 어느 시나리오가 먼저 Hikari timeout을 만드는지 분리하기 어렵다.
# profile별로 관련 없는 scenario를 0으로 꺼서 원인 경로를 독립적으로 반복 측정한다.
# 또한 `browse_search+relation_mixed` 같은 2-profile 조합도 같은 방식으로 켤 수 있어야
# broad mixed 와 최소 재현 조합의 차이를 직접 비교할 수 있다.
# 여기서 더 작은 component를 허용해 `browse_board_feed+like_mixed` 같은 최소 재현 후보도
# 같은 러너로 바로 반복 측정할 수 있게 한다.
printf 'scenario_profile=%s (%s)\n' "$SCENARIO_PROFILE" "$profile_description"
printf 'result_dir=%s\n' "$RESULT_DIR"

SCENARIO_PROFILE="$SCENARIO_PROFILE" \
RESULT_DIR="$RESULT_DIR" \
BASE_URL="$BASE_URL" \
RUNS="$RUNS" \
STAGE_FACTORS="$STAGE_FACTORS" \
STOP_ON_HTTP_P99_MS="$STOP_ON_HTTP_P99_MS" \
STOP_ON_K6_THRESHOLD="$STOP_ON_K6_THRESHOLD" \
./loadtest/run-bbs-sub-stability.sh
