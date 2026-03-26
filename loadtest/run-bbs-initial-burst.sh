#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:18082}"
SOAK_FACTOR="${SOAK_FACTOR:-0.9}"
SOAK_DURATION="${SOAK_DURATION:-5m}"
WARMUP_DURATION="${WARMUP_DURATION:-30s}"
SAMPLE_INTERVAL_SECONDS="${SAMPLE_INTERVAL_SECONDS:-30}"
SCENARIO_PROFILE="${SCENARIO_PROFILE:-full}"
LOADTEST_PREFIX="${LOADTEST_PREFIX:-burst$(date +%m%d%H%M%S)}"

# 문제 해결:
# 2시간 soak 전체를 다시 돌리지 않고도 "초기 5분 timeout burst"만 같은 조건으로 재현하려면
# warmup 직후 짧은 구간을 고정된 샘플링 간격으로 반복 실행하는 래퍼가 필요하다.
# 기존 run-bbs-soak.sh 의 cleanup / SQL snapshot / metrics timeline 흐름은 그대로 재사용한다.
export SCENARIO_PROFILE
export LOADTEST_PREFIX

export BASE_URL
export SOAK_FACTOR
export SOAK_DURATION
export WARMUP_DURATION
export SAMPLE_INTERVAL_SECONDS

exec ./loadtest/run-bbs-soak.sh
