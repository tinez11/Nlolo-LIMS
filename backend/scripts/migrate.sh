#!/usr/bin/env bash
# Applies every module's V1 schema migration in sequence against the target
# environment's database, mirroring .github/workflows/ci-cd.yml's
# db-migration-validation job. Usage: scripts/migrate.sh <local|staging|production>
#
# NOTE: not idempotent -- safe for a first migration run only. Re-running
# against an already-migrated database will fail (plain CREATE TABLE/INDEX,
# no schema-history tracking). Tracked as an M1 follow-up: switch to Flyway
# CLI per-module (-schemas=<mod> -table=flyway_schema_history) once a V2
# migration exists for any module.
set -euo pipefail

ENVIRONMENT="${1:?Usage: scripts/migrate.sh <local|staging|production>}"

case "$ENVIRONMENT" in
  local)
    # Runs psql INSIDE the postgres container, so no host psql install is needed -- the container
    # already ships it. Each migration is piped in on stdin rather than mounted or `docker cp`ed:
    # docker cp mangles Windows paths on the development machines this targets.
    DB_URL=""            # unused in local mode; every psql call goes through docker compose exec
    LOCAL_MODE=1
    ;;
  staging)
    DB_URL="${STAGING_DB_URL:?STAGING_DB_URL is not set}"
    ;;
  production)
    DB_URL="${PRODUCTION_DB_URL:?PRODUCTION_DB_URL is not set}"
    ;;
  *)
    echo "Unknown environment: $ENVIRONMENT (expected 'local', 'staging' or 'production')" >&2
    exit 1
    ;;
esac

LOCAL_MODE="${LOCAL_MODE:-0}"

apply() {
  local migration="$1"
  if [ "$LOCAL_MODE" = "1" ]; then
    docker compose -f infra/docker-compose.yml exec -T postgres \
      psql -U postgres -d lifeplatform -v ON_ERROR_STOP=1 -q < "$migration"
  else
    psql "$DB_URL" -v ON_ERROR_STOP=1 -f "$migration"
  fi
}

# Every folder under db-migrations/ except _post-migration (configure-db.sh's). benefitpayout was
# missing from this list from the day product step 2 created it, so any environment built from
# nothing came up with no payout schema at all -- and with ddl-auto none, every payout query
# failed. MigrationScriptCoverageTest now fails the build when a folder is not listed here.
MODULES="party product underwriting policy policyloan billing claims payment audit distribution reinsurance finaccounting regreporting communication document refdata benefitpayout accumulation bonus annuity unitlinked omnichannel"

# Every V*.sql per module, in version order -- NOT just V1. Until M3's final-review fix wave
# this loop hardcoded V1__create_<mod>_schema.sql, so db-migrations/refdata/V2 (the
# POLICY_SUSPENSION_ELIGIBLE_CATEGORIES / TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE seed that
# PolicyApiImpl.suspendPolicy and PolicyLoanApiImpl.originateLoan both read at runtime) and
# db-migrations/policyloan/V2 (the partition tenant-control event trigger) would never have
# been applied to staging or production at all.
applied=0
for mod in $MODULES; do
  for migration in $(ls "db-migrations/$mod"/V*.sql 2>/dev/null | sort -V); do
    echo "Applying $migration"
    apply "$migration"
    applied=$((applied + 1))
  done
done

echo "Applied $applied migrations across $(echo $MODULES | wc -w) modules against $ENVIRONMENT."
