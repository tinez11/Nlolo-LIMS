#!/usr/bin/env bash
# Both databases and every document, then a copy off the server (2026-10-13, deployment pilot §4). Nightly from cron
# and before every release.
#
# Keycloak's database is backed up WITH the platform's, and restoring one without the other is how you get policies
# whose owners cannot sign in: portal logins live in Keycloak and the party they belong to lives here.
#
# Usage: ./backup.sh [label]      prints the backup directory on its last line
set -euo pipefail
cd "$(dirname "$0")"
set -a; . ./.env; . ./versions.env; set +a

DC="docker compose --env-file .env --env-file versions.env -f compose.yml"
STAMP="$(date +%Y%m%d-%H%M%S)-${1:-nightly}"
DIR="$PWD/backups/$STAMP"
mkdir -p "$DIR/objects"

$DC exec -T postgres pg_dump -U postgres -Fc lifeplatform > "$DIR/lifeplatform.dump"
$DC exec -T postgres pg_dump -U postgres -Fc keycloak > "$DIR/keycloak.dump"

# The documents, straight off the object store's own data directory: SeaweedFS keeps them as its volume files, and
# a file-level copy restores without needing the S3 API up.
docker run --rm --volumes-from "$($DC ps -q objectstore)" -v "$DIR/objects:/backup" \
  alpine:3.20 sh -c "cd /data && tar czf /backup/objects.tar.gz ."

echo "${RELEASE:-unknown}" > "$DIR/RELEASE"

# Seven here; the off-server copies below are the ones that matter for losing the machine.
ls -1d backups/*/ 2>/dev/null | sort | head -n -7 | xargs -r rm -rf

if [ -n "${BACKUP_S3_ENDPOINT:-}" ]; then
  rc() {
    docker run --rm -v "$PWD/backups:/data:ro" \
      -e RCLONE_CONFIG_OFF_TYPE=s3 \
      -e RCLONE_CONFIG_OFF_PROVIDER=Other \
      -e RCLONE_CONFIG_OFF_ENDPOINT="$BACKUP_S3_ENDPOINT" \
      -e RCLONE_CONFIG_OFF_ACCESS_KEY_ID="$BACKUP_S3_ACCESS_KEY" \
      -e RCLONE_CONFIG_OFF_SECRET_ACCESS_KEY="$BACKUP_S3_SECRET_KEY" \
      "rclone/rclone:$RCLONE_VERSION" "$@"
  }
  rc copy "/data/$STAMP" "off:$BACKUP_S3_BUCKET/daily/$STAMP"
  [ "$(date +%u)" = "7" ] && rc copy "/data/$STAMP" "off:$BACKUP_S3_BUCKET/weekly/$STAMP"
  rc delete --min-age 15d "off:$BACKUP_S3_BUCKET/daily" || true
  rc delete --min-age 57d "off:$BACKUP_S3_BUCKET/weekly" || true
else
  echo "WARNING: BACKUP_S3_ENDPOINT is empty -- this backup exists only on this server, so it survives a bad" >&2
  echo "         release and not a lost machine. Fill the BACKUP_S3_* settings in .env." >&2
fi

echo "$DIR"
