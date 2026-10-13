#!/usr/bin/env bash
# Runs ON the server (2026-10-13, deployment pilot §8). deploy/release.sh copies the files here and calls this.
#
# Stops at the first failure, and the order is the point: nothing restarts the backend until the database has
# migrated, so a failed migration leaves the previous version serving the database it was built for.
#
# Usage: ./remote-release.sh <version>             release
#        ./remote-release.sh --rollback <version>  point the containers at an earlier version (no migrations)
set -euo pipefail
cd "$(dirname "$0")"

DC="docker compose --env-file .env --env-file versions.env -f compose.yml"

wait_healthy() {
  local service="$1" i=0
  until [ "$($DC ps --format '{{.Health}}' "$service" 2>/dev/null)" = "healthy" ]; do
    i=$((i + 1))
    if [ "$i" -gt 90 ]; then
      echo "$service did not become healthy" >&2
      $DC logs --tail 80 "$service" >&2
      exit 1
    fi
    sleep 2
  done
}

set_release() { sed -i "s/^RELEASE=.*/RELEASE=$1/" .env; }

# The object store's credentials live in .env; its config file is written from them rather than committed.
write_objectstore_config() {
  set -a; . ./.env; set +a
  mkdir -p objectstore
  cat > objectstore/s3.json <<JSON
{
  "identities": [
    {
      "name": "platform",
      "credentials": [
        { "accessKey": "${OBJECT_STORE_ACCESS_KEY}", "secretKey": "${OBJECT_STORE_SECRET_KEY}" }
      ],
      "actions": ["Read", "Write", "List", "Tagging", "Admin"]
    }
  ]
}
JSON
  chmod 600 objectstore/s3.json
}

# The four buckets the platform writes to. weed shell is idempotent enough for this: an existing bucket reports so
# and the loop carries on.
create_buckets() {
  for bucket in policy-documents kyc-evidence underwriting-evidence claim-evidence; do
    echo "s3.bucket.create -name $bucket" | $DC exec -T objectstore weed shell 2>&1 \
      | grep -iE "error|exist" | grep -vi "already" || true
  done
}

if [ "${1:-}" = "--rollback" ]; then
  VERSION="${2:?Usage: ./remote-release.sh --rollback <version>}"
  set_release "$VERSION"
  [ "${SKIP_PULL:-0}" = "1" ] || $DC pull keycloak backend caddy
  $DC up -d keycloak && wait_healthy keycloak
  $DC up -d backend caddy && wait_healthy backend
  ./smoke.sh
  echo "$VERSION" > RELEASED
  echo "Rolled back to $VERSION."
  echo "Migrations were NOT undone -- if this version cannot read the schema, restore the pre-release backup."
  exit 0
fi

VERSION="${1:?Usage: ./remote-release.sh <version>}"

if $DC ps --status running --services 2>/dev/null | grep -q '^postgres$'; then
  echo "== backup before $VERSION"
  ./backup.sh "pre-$VERSION"
fi

set_release "$VERSION"
write_objectstore_config
[ "${SKIP_PULL:-0}" = "1" ] || $DC pull postgres objectstore mock-mobile-money keycloak backend caddy

echo "== data services"
$DC up -d postgres objectstore mock-mobile-money
wait_healthy postgres
wait_healthy objectstore
create_buckets

echo "== database"
./scripts/migrate.sh server
./scripts/configure-db.sh server

echo "== identity"
$DC up -d keycloak
wait_healthy keycloak
$DC up -d caddy
$DC run --rm keycloak-config

if [ ! -f .first-admin-created ]; then
  echo "== first staff login"
  set -a; . ./.env; set +a
  ./add-user.sh staff staff.admin --roles ALL --home-branch "${FIRST_ADMIN_HOME_BRANCH:-DSM}" \
    --first-name Platform --last-name Admin
  touch .first-admin-created
fi

echo "== application"
$DC up -d backend
wait_healthy backend

./smoke.sh
echo "$VERSION" > RELEASED
echo "Released $VERSION."
