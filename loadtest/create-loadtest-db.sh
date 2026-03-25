#!/usr/bin/env bash
set -euo pipefail

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_USER="${DB_USER:-postgres}"
DB_PASSWORD="${DB_PASSWORD:-4321}"
DB_NAME="${APP_LOAD_TEST_DB_NAME:-effectivedisco_loadtest}"

export PGPASSWORD="$DB_PASSWORD"

if ! command -v psql >/dev/null 2>&1; then
  printf 'psql is not installed.\n' >&2
  exit 1
fi

# 문제 해결:
# loadtest 전용 DB를 사람이 수동으로 먼저 만들어 두지 않으면
# 프로필 분리를 적용한 뒤 첫 실행에서 기동이 바로 실패한다.
# 존재하지 않으면 전용 DB를 생성해 실험용 데이터와 개발 데이터를 물리적으로 나눈다.
if psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d postgres -Atqc "SELECT 1 FROM pg_database WHERE datname = '$DB_NAME';" | grep -q 1; then
  printf 'database already exists: %s\n' "$DB_NAME"
  exit 0
fi

psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d postgres -c "CREATE DATABASE \"$DB_NAME\";"
printf 'created database: %s\n' "$DB_NAME"
