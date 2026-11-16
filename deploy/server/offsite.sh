# The off-server copy of the backups, ENCRYPTED (2026-11-16, security audit M3). Sourced by backup.sh and
# fetch-backup.sh, never run on its own.
#
# The dumps hold every customer record and Keycloak's password hashes, so they leave this machine only through
# rclone's crypt layer: file contents AND names are encrypted with BACKUP_CRYPT_PASSWORD before upload, and the bucket
# sees nothing readable. Whoever holds the bucket keys still cannot read a backup.
#
# KEEP A COPY OF BACKUP_CRYPT_PASSWORD OFF THIS SERVER (the runbook's safe). The off-server copy exists for the day this
# server is lost -- and on that day the .env is lost with it, so a password kept only here makes every copy unreadable.
#
# `safe:` is the encrypted view of BACKUP_S3_BUCKET; `safe:daily/STAMP` is what rclone stores, encrypted, in it.

offsite_configured() { [ -n "${BACKUP_S3_ENDPOINT:-}" ]; }

offsite_require_key() {
  if [ -z "${BACKUP_CRYPT_PASSWORD:-}" ]; then
    echo "BACKUP_S3_* is filled but BACKUP_CRYPT_PASSWORD is empty: refusing to send customer data off this server" >&2
    echo "unencrypted. Set it in .env (openssl rand -base64 36), and keep a copy of it off this server." >&2
    exit 1
  fi
}

# rclone in a container, with the S3 remote `off` and the encrypted remote `safe` on top of it. The crypt password is
# handed over obscured, as rclone's config requires; obscuring is an encoding, the password itself is the secret.
rc() {
  offsite_require_key
  local obscured
  obscured="$(docker run --rm "rclone/rclone:$RCLONE_VERSION" obscure "$BACKUP_CRYPT_PASSWORD")"
  docker run --rm -v "$PWD/backups:/data" \
    -e RCLONE_CONFIG_OFF_TYPE=s3 \
    -e RCLONE_CONFIG_OFF_PROVIDER=Other \
    -e RCLONE_CONFIG_OFF_ENDPOINT="$BACKUP_S3_ENDPOINT" \
    -e RCLONE_CONFIG_OFF_ACCESS_KEY_ID="$BACKUP_S3_ACCESS_KEY" \
    -e RCLONE_CONFIG_OFF_SECRET_ACCESS_KEY="$BACKUP_S3_SECRET_KEY" \
    -e RCLONE_CONFIG_SAFE_TYPE=crypt \
    -e RCLONE_CONFIG_SAFE_REMOTE="off:$BACKUP_S3_BUCKET" \
    -e RCLONE_CONFIG_SAFE_PASSWORD="$obscured" \
    "rclone/rclone:$RCLONE_VERSION" "$@"
}
