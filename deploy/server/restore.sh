#!/usr/bin/env bash
# Restores a backup (2026-10-13).
#
# --scratch restores the platform database into a throwaway database and reports what it found, touching nothing
# live. That is the go-live and monthly check, and it is the whole point: a backup nobody has ever restored is a
# file, not a backup.
#
# Without --scratch the live databases and documents are REPLACED. The backend and Keycloak stop first, so nothing
# writes while the data underneath it changes, and start again after.
#
# Usage: ./restore.sh <backups/STAMP> [--scratch]
set -euo pipefail
cd "$(dirname "$0")"
set -a; . ./.env; . ./versions.env; set +a

DC="docker compose --env-file .env --env-file versions.env -f compose.yml"
DIR="${1:?Usage: ./restore.sh <backups/STAMP> [--scratch]}"
[ -f "$DIR/lifeplatform.dump" ] || { echo "$DIR has no lifeplatform.dump" >&2; exit 1; }

psql_() { $DC exec -T postgres psql -U postgres -v ON_ERROR_STOP=1 "$@"; }

if [ "${2:-}" = "--scratch" ]; then
  psql_ -q -c "DROP DATABASE IF EXISTS restore_check" -c "CREATE DATABASE restore_check"
  $DC exec -T postgres pg_restore -U postgres -d restore_check --no-owner < "$DIR/lifeplatform.dump"
  echo "-- what came back --"
  psql_ -d restore_check -c "SELECT count(*) AS policies FROM policy.policy" \
                         -c "SELECT count(*) AS parties FROM party.party" \
                         -c "SELECT count(*) AS premiums_received FROM billing.premium_receipt"
  psql_ -q -c "DROP DATABASE restore_check"
  echo "Scratch restore of $DIR succeeded -- the backup is readable and carries data."
  exit 0
fi

echo "This REPLACES the live databases and documents with $DIR."
read -r -p "Type RESTORE to continue: " answer
[ "$answer" = "RESTORE" ] || { echo "Cancelled."; exit 1; }

$DC stop backend keycloak
for db in lifeplatform keycloak; do
  echo "restoring $db"
  $DC exec -T postgres pg_restore -U postgres -d "$db" --clean --if-exists < "$DIR/$db.dump"
done

if [ -f "$DIR/objects/objects.tar.gz" ]; then
  echo "restoring documents"
  $DC stop objectstore
  docker run --rm --volumes-from "$($DC ps -aq objectstore)" -v "$PWD/$DIR/objects:/backup:ro" \
    alpine:3.20 sh -c "rm -rf /data/* && cd /data && tar xzf /backup/objects.tar.gz"
  $DC up -d objectstore
fi

$DC up -d keycloak backend
echo "Restored $DIR. Run ./smoke.sh once the backend is healthy."
