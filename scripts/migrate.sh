#!/usr/bin/env bash
# Applies every module's V1 schema migration in sequence against the target
# environment's database, mirroring .github/workflows/ci-cd.yml's
# db-migration-validation job. Usage: scripts/migrate.sh <staging|production>
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

for mod in $MODULES; do
  echo "Applying $mod"
  psql "$DB_URL" -v ON_ERROR_STOP=1 -f "db-migrations/$mod/V1__create_${mod}_schema.sql"
done

echo "All 16 module migrations applied against $ENVIRONMENT."
