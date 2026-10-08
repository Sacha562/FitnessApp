#!/usr/bin/env bash
# Runs the fitness SQL test suite on a local Supabase Docker stack (DATA_MODEL 17.6, 19.1-19.2).
# The stack lives in .localstack/ (git-ignored) with its own project id and ports (5532x), so it never
# touches another local stack or the shared project. Migrations: every applied fitness migration except
# 003 (replaced by the local fixture users), then the files in supabase/migrations/pending/ in name order.
# Usage: supabase/tests/run_local.sh [--reset-only]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
STACK="$ROOT/.localstack"
mkdir -p "$STACK/supabase/migrations"
cp "$ROOT/supabase/tests/local/config.toml" "$STACK/supabase/config.toml"
rm -f "$STACK/supabase/migrations/"*.sql
for f in "$ROOT"/supabase/migrations/*_fitness_*.sql; do
  case "$f" in *fitness_003_allowlist_owner*) continue ;; esac
  cp "$f" "$STACK/supabase/migrations/"
done
cp "$ROOT/supabase/tests/local/"*_local_fixture_users.sql "$STACK/supabase/migrations/"
n=0
for f in "$ROOT"/supabase/migrations/pending/fitness_*.sql; do
  [ -e "$f" ] || continue
  n=$((n + 1))
  cp "$f" "$STACK/supabase/migrations/2099123100$(printf '%04d' "$n")_$(basename "$f")"
done
cd "$STACK"
if ! docker ps --format '{{.Names}}' | grep -q '^supabase_db_fitnessapp_test$'; then
  supabase start >/dev/null
fi
supabase db reset --local >/dev/null
[ "${1:-}" = "--reset-only" ] && exit 0
{
  echo "set fitness_tests.env = 'local';"
  cat "$ROOT/supabase/tests/fitness_db_tests.sql"
} | docker exec -i supabase_db_fitnessapp_test psql -U postgres -d postgres -v ON_ERROR_STOP=0 -q 2>&1 \
  | grep -E 'FITNESS_DB_TESTS_PASSED|FAILED|ERROR' || true
