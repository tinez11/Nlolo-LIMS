#!/usr/bin/env bash
# Brings an off-server backup back to this server, decrypted, ready for restore.sh (2026-11-16). The off-server copy is
# encrypted (offsite.sh); this needs the same BACKUP_CRYPT_PASSWORD it was made with -- on a replacement server, put the
# copy kept off the old one into .env first.
#
# Usage: ./fetch-backup.sh               lists what is off the server
#        ./fetch-backup.sh daily/STAMP   copies that one to backups/STAMP, then: ./restore.sh backups/STAMP --scratch
set -euo pipefail
cd "$(dirname "$0")"
set -a; . ./.env; . ./versions.env; set +a
. ./offsite.sh

offsite_configured || { echo "BACKUP_S3_ENDPOINT is empty: there is no off-server copy to fetch" >&2; exit 1; }

if [ -z "${1:-}" ]; then
  rc lsf --dirs-only "safe:daily"  | sed 's|^|daily/|'
  rc lsf --dirs-only "safe:weekly" | sed 's|^|weekly/|'
  exit 0
fi

STAMP="$(basename "$1")"
mkdir -p "backups/$STAMP"
rc copy "safe:$1" "/data/$STAMP"
[ -f "backups/$STAMP/lifeplatform.dump" ] || { echo "backups/$STAMP came back without lifeplatform.dump" >&2; exit 1; }
echo "backups/$STAMP"
