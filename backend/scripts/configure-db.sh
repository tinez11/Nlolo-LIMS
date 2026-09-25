#!/usr/bin/env bash
# Installs everything in db-migrations/_post-migration: the pg_partman registrations that keep
# monthly partitions coming, the check that every partition inherited its parent's row security,
# and every pg_cron job the platform depends on.
#
# Usage: scripts/configure-db.sh <local|staging|production>
#
# WHY THIS EXISTS. These scripts need superuser and pg_cron, so they sit outside the numbered
# migrations -- and until now nothing ran all of them. migrate.sh ignores the folder; the CI
# pipeline ran two of the six; in dev each existed only if somebody had typed it in. The result
# was invisible: commission could never be paid because the close job was never installed,
# loans never accrued interest, and the monthly ledgers had partitions only to September 2026,
# with no default partition -- every audit, ledger and payment write from 1 October would have
# failed.
#
# IDEMPOTENT. Every script is safe to run again: functions are CREATE OR REPLACE, cron.schedule
# updates a job of the same name, and the pg_partman registrations skip a table already managed.
# Run it after every migrate.sh, on every environment.
set -euo pipefail

cd "$(dirname "$0")/.."

ENVIRONMENT="${1:?Usage: scripts/configure-db.sh <local|staging|production>}"
case "$ENVIRONMENT" in
  local)      LOCAL_MODE=1; DB_URL="" ;;
  staging)    LOCAL_MODE=0; DB_URL="${STAGING_DB_URL:?STAGING_DB_URL is not set}" ;;
  production) LOCAL_MODE=0; DB_URL="${PRODUCTION_DB_URL:?PRODUCTION_DB_URL is not set}" ;;
  *) echo "Unknown environment: $ENVIRONMENT (expected 'local', 'staging' or 'production')" >&2; exit 1 ;;
esac

# ORDER MATTERS, and this list is the order. Partitions first -- a sweep that writes to a ledger
# must never run against a month with no partition -- then the check that the new partitions
# carry their parent's row security, then the jobs.
SCRIPTS=(
  configure-pg-partman.sql
  verify-partition-controls.sql
  configure-billing-sweep.sql
  configure-commission-close.sql
  configure-offer-expiry-sweep.sql
  configure-offer-reminder-sweep.sql
  configure-loan-interest-accrual.sql
  # Last: the readiness function the backend's health check calls, which checks all of the above.
  configure-ops-checks.sql
)

# Refuse to run with a script in the folder this list does not name: a new sweep added to the
# folder and not here is exactly how four of them came to be installed nowhere.
for file in db-migrations/_post-migration/*.sql; do
  name="$(basename "$file")"
  listed=0
  for s in "${SCRIPTS[@]}"; do [ "$s" = "$name" ] && listed=1; done
  if [ "$listed" = "0" ]; then
    echo "db-migrations/_post-migration/$name is not listed in scripts/configure-db.sh -- add it in its place in the order" >&2
    exit 1
  fi
done

apply() {
  local script="db-migrations/_post-migration/$1"
  echo "configure-db ($ENVIRONMENT): $1"
  if [ "$LOCAL_MODE" = "1" ]; then
    docker compose -f infra/docker-compose.yml exec -T postgres \
      psql -U postgres -d lifeplatform -v ON_ERROR_STOP=1 -q < "$script"
  else
    psql "$DB_URL" -v ON_ERROR_STOP=1 -q -f "$script"
  fi
}

for s in "${SCRIPTS[@]}"; do
  apply "$s"
done

echo "configure-db ($ENVIRONMENT): done -- ${#SCRIPTS[@]} scripts applied"
