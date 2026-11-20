#!/usr/bin/env bash
# Both databases and every document, then a copy off the server (2026-10-13, deployment pilot §4; native since
# 2026-11-20, ticket 10 of .scratch/authentik-cutover). Nightly from cron and before every release.
#
# Authentik's database is backed up WITH the platform's (Keycloak's until 2026-11-20), and restoring one without the
# other is how you get policies whose owners cannot sign in: logins live in Authentik, with the party_id that ties
# each to its record here.
#
# Usage: ./backup.sh [label]      prints the backup directory on its last line
set -euo pipefail
cd "$(dirname "$0")"
. ./platform-lib.sh
. ./offsite.sh

STAMP="$(date +%Y%m%d-%H%M%S)-${1:-nightly}"
DIR="$PWD/backups/$STAMP"
mkdir -p "$DIR"

# The deploy user writes the dumps into its own folder; only the reading needs postgres.
# shellcheck disable=SC2024
sudo -u postgres pg_dump -Fc lifeplatform > "$DIR/lifeplatform.dump"
# shellcheck disable=SC2024
sudo -u postgres pg_dump -Fc authentik > "$DIR/authentik.dump"

# The documents, straight off the object store's own data directory: SeaweedFS keeps them as its volume files, and a
# file-level copy restores without needing the S3 API up.
# shellcheck disable=SC2024
sudo tar czf - -C /var/lib/seaweedfs . > "$DIR/objects.tar.gz"

cat RELEASED > "$DIR/RELEASE" 2>/dev/null || echo unknown > "$DIR/RELEASE"

# Seven here; the off-server copies below are the ones that matter for losing the machine.
ls -1d backups/*/ 2>/dev/null | sort | head -n -7 | xargs -r rm -rf

if offsite_configured; then
  # Encrypted on the way out (offsite.sh); fetch-backup.sh brings one back.
  rc copy "$DIR" "safe:daily/$STAMP"
  if [ "$(date +%u)" = "7" ]; then rc copy "$DIR" "safe:weekly/$STAMP"; fi
  rc delete --min-age 15d "safe:daily" || true
  rc delete --min-age 57d "safe:weekly" || true
else
  echo "WARNING: BACKUP_S3_ENDPOINT is empty -- this backup exists only on this server, so it survives a bad" >&2
  echo "         release and not a lost machine. Fill the BACKUP_S3_* settings in .env." >&2
fi

echo "$DIR"
