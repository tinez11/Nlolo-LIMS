#!/usr/bin/env bash
# Applies every module's V1 schema migration in sequence against the target
# environment's database, mirroring .github/workflows/ci-cd.yml's
# db-migration-validation job. Usage: scripts/migrate.sh <staging|production>
#
# NOTE: not idempotent -- safe for a first migration run only. Re-running
# against an already-migrated database will fail (plain CREATE TABLE/INDEX,
# no schema-history tracking). Tracked as an M1 follow-up: switch to Flyway
# CLI per-module (-schemas=<mod> -table=flyway_schema_history) once a V2
# migration exists for any module.
set -euo pipefail

ENVIRONMENT="${1:?Usage: scripts/migrate.sh <staging|production>}"

case "$ENVIRONMENT" in
  staging)
    DB_URL="${STAGING_DB_URL:?STAGING_DB_URL is not set}"
    ;;
  production)
    DB_URL="${PRODUCTION_DB_URL:?PRODUCTION_DB_URL is not set}"
    ;;
  *)
    echo "Unknown environment: $ENVIRONMENT (expected 'staging' or 'production')" >&2
    exit 1
    ;;
esac

MODULES="party product underwriting policy policyloan billing claims payment audit distribution reinsurance finaccounting regreporting communication document refdata"

# Every V*.sql per module, in version order -- NOT just V1. Until M3's final-review fix wave
# this loop hardcoded V1__create_<mod>_schema.sql, so db-migrations/refdata/V2 (the
# POLICY_SUSPENSION_ELIGIBLE_CATEGORIES / TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE seed that
# PolicyApiImpl.suspendPolicy and PolicyLoanApiImpl.originateLoan both read at runtime) and
# db-migrations/policyloan/V2 (the partition tenant-control event trigger) would never have
# been applied to staging or production at all.
for mod in $MODULES; do
  for migration in $(ls "db-migrations/$mod"/V*.sql 2>/dev/null | sort -V); do
    echo "Applying $migration"
    psql "$DB_URL" -v ON_ERROR_STOP=1 -f "$migration"
  done
done

echo "All 16 modules' migrations applied against $ENVIRONMENT."
