#!/usr/bin/env bash
# Runs ON the server (2026-10-13, deployment pilot §8; native under systemd since 2026-11-20, ticket 10 of
# .scratch/authentik-cutover). deploy/release.sh copies the files here and calls this.
#
# Stops at the first failure, and the order is the point: nothing restarts the backend until the database has
# migrated and Authentik is up with its configuration applied, so a failed release leaves the previous version serving.
#
# Usage: ./remote-release.sh <version>             release
#        ./remote-release.sh --rollback <version>  put an earlier release back in service (no migrations)
set -euo pipefail
cd "$(dirname "$0")"
. ./platform-lib.sh

# --- settings: each service gets only its own, in a file only root and that service can read ---------------------
write_env() {   # write_env <file> <owner group>   (content on stdin)
  sudo install -m 0640 -o root -g "$2" /dev/stdin "$ETC/$1"
}

render_settings() {
  write_env backend.env platform <<ENV
SPRING_PROFILES_ACTIVE=prod
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/lifeplatform
SPRING_DATASOURCE_USERNAME=app_role
SPRING_DATASOURCE_PASSWORD=$APP_DB_PASSWORD
MINIO_ENDPOINT=http://127.0.0.1:$SEAWEED_S3_PORT
MINIO_ACCESS_KEY=$OBJECT_STORE_ACCESS_KEY
MINIO_SECRET_KEY=$OBJECT_STORE_SECRET_KEY
AUTHENTIK_ISSUER=https://$AUTH_DOMAIN/application/o/lifeplatform/
AUTHENTIK_AUDIENCE=lifeplatform-spa
AUTHENTIK_BASE_URL=http://127.0.0.1:$AUTHENTIK_SERVER_PORT
AUTHENTIK_API_TOKEN=$AUTHENTIK_API_TOKEN
AUTHENTIK_PUBLIC_URL=https://$AUTH_DOMAIN
SIGN_IN_CODE_WEBHOOK_SECRET=$SIGN_IN_CODE_WEBHOOK_SECRET
PASSWORD_CHANGED_WEBHOOK_SECRET=$PASSWORD_CHANGED_WEBHOOK_SECRET
PUBLIC_QUOTE_ENABLED=${PUBLIC_QUOTE_ENABLED:-false}
PUBLIC_QUOTE_TENANT_ID=$PLATFORM_TENANT_ID
TURNSTILE_SECRET=$TURNSTILE_SECRET
PUBLIC_VISIT_SECRET=$PUBLIC_VISIT_SECRET
STAFF_CONSOLE_URL=https://$APP_DOMAIN/staff
AGENT_CONSOLE_URL=https://$APP_DOMAIN/agents
PORTAL_URL=https://$APP_DOMAIN/customers
MOBILE_MONEY_GATEWAY_BASE_URL=http://127.0.0.1:$MOCK_MONEY_PORT
MOBILE_MONEY_CALLBACK_HMAC_SECRET=$MOBILE_MONEY_CALLBACK_HMAC_SECRET
SUPPORT_PHONE=$SUPPORT_PHONE
STAFF_SIGN_IN_CODE=$STAFF_SIGN_IN_CODE
SMS_GATEWAY_BASE_URL=$SMS_GATEWAY_BASE_URL
SMS_GATEWAY_AUTHORIZATION=$SMS_GATEWAY_AUTHORIZATION
SMS_GATEWAY_SENDER_NAME=$SMS_GATEWAY_SENDER_NAME
SMS_GATEWAY_LIVE=$SMS_GATEWAY_LIVE
SMS_GATEWAY_ALLOWED_RECIPIENTS=$SMS_GATEWAY_ALLOWED_RECIPIENTS
MAIL_ENABLED=$MAIL_ENABLED
MAIL_HOST=$MAIL_HOST
MAIL_PORT=$MAIL_PORT
MAIL_USERNAME=$MAIL_USERNAME
MAIL_PASSWORD=$MAIL_PASSWORD
NOTIFICATION_FROM_ADDRESS=$MAIL_FROM
APP_DOMAIN=$APP_DOMAIN
ENV

  # Authentik's settings, shared by its server and worker. Read by the production blueprint too (LP_*, the API token,
  # the sign-in code switch, the webhook secrets).
  write_env authentik.env authentik <<ENV
AUTHENTIK_SECRET_KEY=$AUTHENTIK_SECRET_KEY
AUTHENTIK_POSTGRESQL__HOST=127.0.0.1
AUTHENTIK_POSTGRESQL__NAME=authentik
AUTHENTIK_POSTGRESQL__USER=authentik
AUTHENTIK_POSTGRESQL__PASSWORD=$AUTHENTIK_DB_PASSWORD
AUTHENTIK_POSTGRESQL__SSLMODE=disable
AUTHENTIK_BOOTSTRAP_PASSWORD=$AUTHENTIK_BOOTSTRAP_PASSWORD
AUTHENTIK_DISABLE_UPDATE_CHECK=true
AUTHENTIK_ERROR_REPORTING__ENABLED=false
AUTHENTIK_DISABLE_STARTUP_ANALYTICS=true
AUTHENTIK_BLUEPRINTS_DIR=$AUTHENTIK_STATE/blueprints
AUTHENTIK_STORAGE__FILE__PATH=$AUTHENTIK_STATE/data
AUTHENTIK_CERT_DISCOVERY_DIR=$AUTHENTIK_STATE/certs
AUTHENTIK_LISTEN__TRUSTED_PROXY_CIDRS=127.0.0.0/8,::1/128
PYTHONDONTWRITEBYTECODE=1
PYTHONUNBUFFERED=1
RUST_BACKTRACE=full
LP_APP_ORIGIN=https://$APP_DOMAIN
LP_BACKEND_URL=http://127.0.0.1:$BACKEND_PORT
AUTHENTIK_API_TOKEN=$AUTHENTIK_API_TOKEN
STAFF_SIGN_IN_CODE=$STAFF_SIGN_IN_CODE
SIGN_IN_CODE_WEBHOOK_SECRET=$SIGN_IN_CODE_WEBHOOK_SECRET
PASSWORD_CHANGED_WEBHOOK_SECRET=$PASSWORD_CHANGED_WEBHOOK_SECRET
ENV
  # Server and worker share this host, so each listens on its own loopback ports.
  write_env authentik-server.env authentik <<ENV
AUTHENTIK_LISTEN__HTTP=127.0.0.1:$AUTHENTIK_SERVER_PORT
AUTHENTIK_LISTEN__HTTPS=127.0.0.1:9443
AUTHENTIK_LISTEN__METRICS=127.0.0.1:9300
AUTHENTIK_LISTEN__DEBUG=127.0.0.1:9900
AUTHENTIK_LISTEN__DEBUG_PY=127.0.0.1:9901
ENV
  write_env authentik-worker.env authentik <<ENV
AUTHENTIK_LISTEN__HTTP=127.0.0.1:$AUTHENTIK_WORKER_PORT
AUTHENTIK_LISTEN__HTTPS=127.0.0.1:9444
AUTHENTIK_LISTEN__METRICS=127.0.0.1:9301
AUTHENTIK_LISTEN__DEBUG=127.0.0.1:9902
AUTHENTIK_LISTEN__DEBUG_PY=127.0.0.1:9903
ENV

  write_env caddy.env caddy <<ENV
APP_DOMAIN=$APP_DOMAIN
AUTH_DOMAIN=$AUTH_DOMAIN
ACME_EMAIL=$ACME_EMAIL
CADDY_GLOBAL_OPTIONS=$CADDY_GLOBAL_OPTIONS
ENV

  sudo install -m 0640 -o root -g seaweedfs /dev/stdin "$ETC/seaweedfs-s3.json" <<JSON
{
  "identities": [
    {
      "name": "platform",
      "credentials": [
        { "accessKey": "$OBJECT_STORE_ACCESS_KEY", "secretKey": "$OBJECT_STORE_SECRET_KEY" }
      ],
      "actions": ["Read", "Write", "List", "Tagging", "Admin"]
    }
  ]
}
JSON

  # The backend fetches Authentik's signing keys from https://AUTH_DOMAIN, because a token's issuer must match that
  # address exactly. This sends that call to Caddy on this machine, so it never leaves it and still presents a valid
  # certificate -- what the compose network alias did before.
  sudo sed -i "/# platform: the two site addresses/d" /etc/hosts
  echo "127.0.0.1 $APP_DOMAIN $AUTH_DOMAIN # platform: the two site addresses" | sudo tee -a /etc/hosts > /dev/null
}

install_units() {
  local src="$1"
  sudo install -m 0644 "$src"/systemd/*.service /etc/systemd/system/
  sudo install -d /etc/systemd/system/caddy.service.d
  sudo install -m 0644 "$src/systemd/caddy-platform.conf" /etc/systemd/system/caddy.service.d/platform.conf
  sudo install -m 0644 "$src/systemd/postgresql-platform.conf" "/etc/postgresql/$POSTGRES_MAJOR/main/conf.d/platform.conf"
  sudo install -m 0644 "$src/Caddyfile" /etc/caddy/Caddyfile
  sudo systemctl daemon-reload
}

# --- the database, from nothing on the first release ---------------------------------------------------------------
database_up() {
  sudo systemctl enable --now "$PG_SERVICE"
  # The settings above need a restart the first time (shared_preload_libraries); after that it changes nothing.
  if [ "$(psql_ -tAc "SHOW max_connections")" != "200" ]; then sudo systemctl restart "$PG_SERVICE"; fi
  wait_for "Postgres" sudo -u postgres pg_isready -q
  if [ "$(psql_ -tAc "SELECT 1 FROM pg_roles WHERE rolname = 'app_role'")" != "1" ]; then
    echo "== database: first time"
    # The same bootstrap the dev stack's Postgres image runs (backend/infra/postgres/init): the application's
    # non-superuser role, the databases, pg_partman and pg_cron.
    psql_ -q -c "ALTER ROLE postgres PASSWORD '$POSTGRES_PASSWORD'"
    psql_ -q -c "CREATE DATABASE lifeplatform"
    psql_ -q -v app_role_password="$APP_DB_PASSWORD" -f "$RELEASE_DIR/postgres-init/01-create-app-role.sql.template"
    psql_ -q -v ak_password="$AUTHENTIK_DB_PASSWORD" \
      -c "CREATE ROLE authentik LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'ak_password'" \
      -c "ALTER DATABASE authentik OWNER TO authentik"
    psql_ -q -d authentik -c "ALTER SCHEMA public OWNER TO authentik"
  fi
}

# --- Authentik: built for this version, its blueprint and brand images in place, healthy, configured --------------
authentik_up() {
  ./install-authentik.sh "$AUTHENTIK_VERSION"
  sudo ln -sfn "$AUTHENTIK_OPT/$AUTHENTIK_VERSION" "$AUTHENTIK_OPT/current"
  # Authentik's own stock blueprints from this version, then the platform's -- the production blueprint only, never
  # the dev one, which release.sh never ships.
  sudo rm -rf "$AUTHENTIK_STATE/blueprints"
  sudo cp -r "$AUTHENTIK_OPT/current/blueprints" "$AUTHENTIK_STATE/blueprints"
  sudo install -d "$AUTHENTIK_STATE/blueprints/lifeplatform"
  sudo install -m 0644 "$RELEASE_DIR"/authentik/blueprints/*.yaml "$AUTHENTIK_STATE/blueprints/lifeplatform/"
  # The brand names its photo, mark and favicon lifeplatform/<file>; Authentik serves them from data/media/public.
  sudo install -d "$AUTHENTIK_STATE/data/media/public/lifeplatform"
  sudo install -m 0644 "$RELEASE_DIR"/authentik/branding/* "$AUTHENTIK_STATE/data/media/public/lifeplatform/"
  sudo chown -R authentik:authentik "$AUTHENTIK_STATE"
  sudo systemctl enable authentik-server authentik-worker
  sudo systemctl restart authentik-server authentik-worker
  wait_for "authentik-server" ak_healthy server || { unit_log authentik-server; exit 1; }
  wait_for "authentik-worker" ak_healthy worker || { unit_log authentik-worker; exit 1; }
  # The worker applies blueprints at start too, but in the background, where a failure is a line in its log. Applied
  # here it either succeeds or stops the release with Authentik's own error, before anything relies on it. The
  # blueprint is the only way the sign-in is configured (DEPLOYMENT-GUIDE.md, "Changing the sign-in").
  if ! out=$(ak_manage -- apply_blueprint lifeplatform/lifeplatform.yaml 2>&1); then
    printf '%s\n' "$out" | grep -v '"level": "debug"' | tail -40 >&2
    echo "The lifeplatform blueprint did not apply -- the sign-in is not configured" >&2
    exit 1
  fi
}

# The four buckets the platform writes to. weed shell is idempotent enough: an existing bucket reports so.
create_buckets() {
  for bucket in policy-documents kyc-evidence underwriting-evidence claim-evidence; do
    echo "s3.bucket.create -name $bucket" | weed shell -master="127.0.0.1:$SEAWEED_MASTER_PORT" 2>&1 \
      | grep -iE "error|exist" | grep -vi "already" || true
  done
}

backend_up() {
  sudo systemctl enable platform-backend
  sudo systemctl restart platform-backend
  wait_for "the backend" backend_ready || { unit_log platform-backend; exit 1; }
}

# --- rollback --------------------------------------------------------------------------------------------------------
if [ "${1:-}" = "--rollback" ]; then
  VERSION="${2:?Usage: ./remote-release.sh --rollback <version>}"
  RELEASE_DIR="$PLATFORM_OPT/releases/$VERSION"
  [ -d "$RELEASE_DIR" ] || { echo "$VERSION is not on this server (ls $PLATFORM_OPT/releases)" >&2; exit 1; }
  # That release's own pins: the Authentik version it was released with, among others.
  set -a; . "$RELEASE_DIR/versions.env"; set +a
  [ -f "$AUTHENTIK_OPT/$AUTHENTIK_VERSION/.built" ] \
    || { echo "Authentik $AUTHENTIK_VERSION, which $VERSION ran with, is no longer built here; run ./install-authentik.sh $AUTHENTIK_VERSION first" >&2; exit 1; }
  sudo ln -sfn "$RELEASE_DIR" "$PLATFORM_OPT/current"
  # The settings are rendered again, so a rollback to the version already running is also how an edit to .env reaches
  # the services (DEPLOYMENT-GUIDE.md, Part 5).
  render_settings
  install_units "$RELEASE_DIR/server"
  authentik_up
  sudo systemctl restart platform-mock-mobile-money caddy
  backend_up
  ./smoke.sh
  echo "$VERSION" > RELEASED
  echo "Rolled back to $VERSION."
  echo "Migrations were NOT undone -- if this version cannot read the schema, restore the pre-release backup."
  exit 0
fi

# --- release ---------------------------------------------------------------------------------------------------------
VERSION="${1:?Usage: ./remote-release.sh <version>}"
INCOMING="$PLATFORM_HOME/incoming/$VERSION"
RELEASE_DIR="$PLATFORM_OPT/releases/$VERSION"
[ -d "$INCOMING" ] || { echo "$INCOMING is missing -- run deploy/release.sh, which puts it there" >&2; exit 1; }

if systemctl is-active --quiet "$PG_SERVICE" && [ "$(psql_ -tAc "SELECT 1 FROM pg_database WHERE datname = 'lifeplatform'")" = "1" ]; then
  echo "== backup before $VERSION"
  ./backup.sh "pre-$VERSION"
fi

echo "== installing $VERSION"
sudo rm -rf "$RELEASE_DIR"
sudo mv "$INCOMING" "$RELEASE_DIR"
sudo cp versions.env "$RELEASE_DIR/versions.env"
sudo chown -R root:root "$RELEASE_DIR"
sudo chmod -R go+rX "$RELEASE_DIR"
./install-tools.sh
render_settings
install_units "$RELEASE_DIR/server"

echo "== database"
database_up
(cd "$RELEASE_DIR" && sudo env POSTGRES_PASSWORD="$POSTGRES_PASSWORD" FLYWAY_VERSION="$FLYWAY_VERSION" \
   scripts/migrate.sh native)
(cd "$RELEASE_DIR" && sudo scripts/configure-db.sh native)

echo "== data services"
# The mock reads its answers from the release in service, so it starts after current points here (below) as well.
sudo systemctl enable --now seaweedfs
wait_for "the object store" curl -s -o /dev/null "http://127.0.0.1:$SEAWEED_S3_PORT" || { unit_log seaweedfs; exit 1; }
create_buckets

echo "== identity"
authentik_up
sudo systemctl enable caddy
sudo systemctl restart caddy

if [ ! -f .first-admin-created ]; then
  echo "== first staff login"
  # Staff sign in with a texted code (2026-11-14), so the first administrator needs a phone from the start.
  [ -n "${FIRST_ADMIN_PHONE:-}" ] || { echo "Set FIRST_ADMIN_PHONE in .env: the first administrator's sign-in code goes to it" >&2; exit 1; }
  # Not staff.admin: that is a dev test user's name, and smoke.sh fails a release where one exists.
  ./add-user.sh staff platform.admin --roles ALL --home-branch "${FIRST_ADMIN_HOME_BRANCH:-DSM}" \
    --phone "$FIRST_ADMIN_PHONE" --first-name Platform --last-name Admin
  touch .first-admin-created
fi

echo "== application"
sudo ln -sfn "$RELEASE_DIR" "$PLATFORM_OPT/current"
sudo systemctl enable platform-mock-mobile-money
sudo systemctl restart platform-mock-mobile-money
backend_up

./smoke.sh
echo "$VERSION" > RELEASED
# Five releases are kept for rollbacks; the one in service is never removed.
ls -1d "$PLATFORM_OPT"/releases/*/ | sort -V | head -n -5 | grep -v "/$VERSION/" | xargs -r sudo rm -rf
echo "Released $VERSION."
