#!/usr/bin/env bash
# Applies every module's migrations with Flyway (2026-10-13, the deployment pilot design §4). One history table per
# module, public.flyway_history_<module>, so module version numbers never collide; modules run in the order below.
#
# Safe to re-run -- which the psql loop this replaces was not, and said so: it could migrate a database exactly once,
# so the second deploy of any environment would have failed on "relation already exists". Flyway applies only what is
# missing, and refuses to run at all when an already-applied migration's file has changed since (a checksum
# mismatch), which catches an edited migration before it silently diverges one environment from another.
#
# Runs the Flyway CLI image, so no local Flyway install is needed anywhere.
#
# Usage: scripts/migrate.sh <local|ci|server|staging|production> [--baseline]
#   local       the dev stack (infra/docker-compose.yml, network infra_default), as postgres
#   ci          a Postgres on localhost:5432, database postgres (the CI service container), PGPASSWORD
#   server      the deploy/server/compose.yml stack, run from the server's platform directory (reads ./.env)
#   staging|production  MIGRATE_JDBC_URL, MIGRATE_USER, MIGRATE_PASSWORD from the environment
#   --baseline  ONCE, on a database migrated before Flyway existed (the hand-migrated dev database): records each
#               module's current migrations as already applied, running none of them. Flyway refuses this where a
#               history table already has rows, so it cannot be used to paper over a real failure.
set -euo pipefail
cd "$(dirname "$0")/.."

ENVIRONMENT="${1:?Usage: scripts/migrate.sh <local|ci|server|staging|production> [--baseline]}"
BASELINE=0
[ "${2:-}" = "--baseline" ] && BASELINE=1

FLYWAY_IMAGE="flyway/flyway:${FLYWAY_VERSION:-10.20.1}"

case "$ENVIRONMENT" in
  local)
    JDBC_URL="jdbc:postgresql://postgres:5432/lifeplatform"; DB_USER=postgres
    DB_PASSWORD="${POSTGRES_PASSWORD:-devpassword}"; NETWORK=infra_default ;;
  ci)
    JDBC_URL="jdbc:postgresql://localhost:5432/postgres"; DB_USER=postgres
    DB_PASSWORD="${PGPASSWORD:?PGPASSWORD is not set}"; NETWORK=host ;;
  server)
    set -a; . ./.env; set +a
    if [ -f versions.env ]; then set -a; . ./versions.env; set +a; FLYWAY_IMAGE="flyway/flyway:${FLYWAY_VERSION}"; fi
    JDBC_URL="jdbc:postgresql://postgres:5432/lifeplatform"; DB_USER=postgres
    DB_PASSWORD="${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is not set in .env}"; NETWORK=platform_default ;;
  staging|production)
    JDBC_URL="${MIGRATE_JDBC_URL:?MIGRATE_JDBC_URL is not set}"
    DB_USER="${MIGRATE_USER:?MIGRATE_USER is not set}"
    DB_PASSWORD="${MIGRATE_PASSWORD:?MIGRATE_PASSWORD is not set}"; NETWORK=host ;;
  *)
    echo "Unknown environment: $ENVIRONMENT (expected local, ci, server, staging or production)" >&2; exit 1 ;;
esac

# Git Bash on Windows rewrites /flyway/... arguments into Windows paths and needs a Windows path for the mount.
export MSYS_NO_PATHCONV=1
MOUNT="$(pwd -W 2>/dev/null || pwd)/db-migrations"

# Every module's history table lives in public, one table each. baselineOnMigrate at version 0 is what makes that
# work: from the second module onward public already holds the previous modules' history tables, and Flyway refuses a
# non-empty schema that has no history table of its own. Version 0 is below every migration (they start at V1), so a
# fresh database still applies all of them -- the baseline row records only "this module had not run here before".
flyway() {
  local module="$1"; shift
  docker run --rm --network "$NETWORK" -v "$MOUNT:/flyway/sql:ro" "$FLYWAY_IMAGE" \
    -url="$JDBC_URL" -user="$DB_USER" -password="$DB_PASSWORD" \
    -locations="filesystem:/flyway/sql/$module" -table="flyway_history_$module" \
    -schemas=public -createSchemas=false -placeholderReplacement=false -outputType=json \
    -baselineOnMigrate=true -baselineVersion=0 "$@"
}

# Every folder under db-migrations/ except _post-migration (configure-db.sh's). benefitpayout was missing from this
# list from the day product step 2 created it, so any environment built from nothing came up with no payout schema at
# all. MigrationScriptCoverageTest now fails the build when a folder is not listed here. iam (staff roles and
# permissions, 2026-10-26) was missing the same way until 2026-10-28.
MODULES="party product underwriting policy policyloan billing claims payment audit distribution reinsurance finaccounting regreporting communication document refdata benefitpayout accumulation bonus annuity unitlinked omnichannel reporting iam"

applied=0
for mod in $MODULES; do
  if [ "$BASELINE" = "1" ]; then
    latest=$(ls "db-migrations/$mod"/V*.sql | sed -E 's#.*/V([0-9_.]+)__.*#\1#' | tr _ . | sort -V | tail -1)
    echo "Baselining $mod at $latest"
    flyway "$mod" baseline -baselineVersion="$latest" \
      -baselineDescription="applied by hand before Flyway" > /dev/null
    continue
  fi
  echo "Migrating $mod"
  if ! out=$(flyway "$mod" migrate); then
    echo "$out" >&2
    echo "Migration of $mod FAILED against $ENVIRONMENT -- nothing after it was applied" >&2
    exit 1
  fi
  n=$(printf '%s' "$out" | grep -o '"migrationsExecuted"[ :]*[0-9]*' | grep -oE '[0-9]+$' || true)
  applied=$((applied + ${n:-0}))
done

if [ "$BASELINE" = "1" ]; then
  echo "Baselined $(echo $MODULES | wc -w) modules against $ENVIRONMENT."
else
  echo "Applied $applied migrations across $(echo $MODULES | wc -w) modules against $ENVIRONMENT."
fi
