#!/usr/bin/env bash
# Restores a backup (2026-10-13; native since 2026-11-20, ticket 10 of .scratch/authentik-cutover).
#
# --scratch restores the platform database into a throwaway database and reports what it found, touching nothing
# live. That is the go-live and monthly check, and it is the whole point: a backup nobody has ever restored is a
# file, not a backup.
#
# Without --scratch the live databases and documents are REPLACED. The backend and Authentik stop first, so nothing
# writes while the data underneath it changes, and start again after. lifeplatform and authentik go back together:
# one without the other leaves logins that point at records that are not there, or records nobody can sign in to.
#
# Usage: ./restore.sh <backups/STAMP> [--scratch]
set -euo pipefail
cd "$(dirname "$0")"
. ./platform-lib.sh

DIR="${1:?Usage: ./restore.sh <backups/STAMP> [--scratch]}"
[ -f "$DIR/lifeplatform.dump" ] || { echo "$DIR has no lifeplatform.dump" >&2; exit 1; }
# A backup made before the Authentik cut-over holds keycloak.dump instead, and cannot bring the sign-in back.
[ "${2:-}" = "--scratch" ] || [ -f "$DIR/authentik.dump" ] \
  || { echo "$DIR has no authentik.dump -- it predates Authentik and would restore logins nobody can use" >&2; exit 1; }

if [ "${2:-}" = "--scratch" ]; then
  psql_ -q -c "DROP DATABASE IF EXISTS restore_check" -c "CREATE DATABASE restore_check"
  sudo -u postgres pg_restore -d restore_check --no-owner < "$DIR/lifeplatform.dump"
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

sudo systemctl stop platform-backend authentik-server authentik-worker
for db in lifeplatform authentik; do
  echo "restoring $db"
  sudo -u postgres pg_restore -d "$db" --clean --if-exists < "$DIR/$db.dump"
done

if [ -f "$DIR/objects.tar.gz" ]; then
  echo "restoring documents"
  sudo systemctl stop seaweedfs
  # The old contents go first, so a restore is the backup's state and not a merge of two.
  sudo find /var/lib/seaweedfs -mindepth 1 -delete
  sudo tar xzf - -C /var/lib/seaweedfs < "$DIR/objects.tar.gz"
  sudo chown -R seaweedfs:seaweedfs /var/lib/seaweedfs
  sudo systemctl start seaweedfs
fi

sudo systemctl start authentik-server authentik-worker platform-backend
echo "Restored $DIR. Run ./smoke.sh once the backend is healthy (./status.sh shows it)."
