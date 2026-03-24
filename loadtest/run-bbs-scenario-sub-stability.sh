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

case "$SCENARIO_PROFILE" in
  browse_search)
    profile_description="browse/search only"
    export BROWSE_RATE="${BROWSE_RATE:-80}"
    export BROWSE_PRE_ALLOCATED_VUS="${BROWSE_PRE_ALLOCATED_VUS:-40}"
    export BROWSE_MAX_VUS="${BROWSE_MAX_VUS:-120}"
    export HOT_POST_RATE="${HOT_POST_RATE:-120}"
    export HOT_POST_PRE_ALLOCATED_VUS="${HOT_POST_PRE_ALLOCATED_VUS:-50}"
    export HOT_POST_MAX_VUS="${HOT_POST_MAX_VUS:-150}"
    export SEARCH_RATE="${SEARCH_RATE:-40}"
    export SEARCH_PRE_ALLOCATED_VUS="${SEARCH_PRE_ALLOCATED_VUS:-20}"
    export SEARCH_MAX_VUS="${SEARCH_MAX_VUS:-80}"
    export WRITE_START_RATE=0
    export WRITE_STAGE_ONE_RATE=0
    export WRITE_STAGE_TWO_RATE=0
    export LIKE_ADD_VUS=0
    export LIKE_REMOVE_VUS=0
    export BOOKMARK_MIXED_VUS=0
    export FOLLOW_MIXED_VUS=0
    export BLOCK_MIXED_VUS=0
    export NOTIFICATION_MIXED_VUS=0
    ;;
  write)
    profile_description="create_post/create_comment only"
    export BROWSE_RATE=0
    export HOT_POST_RATE=0
    export SEARCH_RATE=0
    export WRITE_START_RATE="${WRITE_START_RATE:-8}"
    export WRITE_STAGE_ONE_RATE="${WRITE_STAGE_ONE_RATE:-20}"
    export WRITE_STAGE_TWO_RATE="${WRITE_STAGE_TWO_RATE:-35}"
    export WRITE_PRE_ALLOCATED_VUS="${WRITE_PRE_ALLOCATED_VUS:-20}"
    export WRITE_MAX_VUS="${WRITE_MAX_VUS:-80}"
    export LIKE_ADD_VUS=0
    export LIKE_REMOVE_VUS=0
    export BOOKMARK_MIXED_VUS=0
    export FOLLOW_MIXED_VUS=0
    export BLOCK_MIXED_VUS=0
    export NOTIFICATION_MIXED_VUS=0
    ;;
  relation_mixed)
    profile_description="like/follow/bookmark/block only"
    export BROWSE_RATE=0
    export HOT_POST_RATE=0
    export SEARCH_RATE=0
    export WRITE_START_RATE=0
    export WRITE_STAGE_ONE_RATE=0
    export WRITE_STAGE_TWO_RATE=0
    export LIKE_ADD_VUS="${LIKE_ADD_VUS:-80}"
    export LIKE_REMOVE_VUS="${LIKE_REMOVE_VUS:-80}"
    export BOOKMARK_MIXED_VUS="${BOOKMARK_MIXED_VUS:-60}"
    export FOLLOW_MIXED_VUS="${FOLLOW_MIXED_VUS:-60}"
    export BLOCK_MIXED_VUS="${BLOCK_MIXED_VUS:-60}"
    export NOTIFICATION_MIXED_VUS=0
    ;;
  notification)
    profile_description="notification read/write only"
    export BROWSE_RATE=0
    export HOT_POST_RATE=0
    export SEARCH_RATE=0
    export WRITE_START_RATE=0
    export WRITE_STAGE_ONE_RATE=0
    export WRITE_STAGE_TWO_RATE=0
    export LIKE_ADD_VUS=0
    export LIKE_REMOVE_VUS=0
    export BOOKMARK_MIXED_VUS=0
    export FOLLOW_MIXED_VUS=0
    export BLOCK_MIXED_VUS=0
    export NOTIFICATION_MIXED_VUS="${NOTIFICATION_MIXED_VUS:-40}"
    ;;
  *)
    printf 'unsupported SCENARIO_PROFILE: %s\n' "$SCENARIO_PROFILE" >&2
    exit 1
    ;;
esac

# 문제 해결:
# broad mixed 부하에서는 어느 시나리오가 먼저 Hikari timeout을 만드는지 분리하기 어렵다.
# profile별로 관련 없는 scenario를 0으로 꺼서 원인 경로를 독립적으로 반복 측정한다.
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
