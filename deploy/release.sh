#!/usr/bin/env bash
# Ships a version (2026-10-13, deployment pilot §8; native under systemd since 2026-11-20, ticket 10 of
# .scratch/authentik-cutover). Runs on the developer's machine:
#   1. refuses a dirty tree or an existing tag, then tags HEAD -- so a released version names an exact commit
#   2. builds the backend (a jar) and the console (static files) here: both are the same on any machine. Authentik is
#      built on the server instead (install-authentik.sh), because what it compiles must match the server's system
#   3. copies the release and the server scripts over, and runs remote-release.sh there
#
# The tag is NOT pushed: it is pushed once the release is known good, which the last line reminds you of.
#
# Usage: deploy/release.sh <version>              e.g. v1.0.0
#        deploy/release.sh --rollback <version>
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f deploy/release.env ] || {
  echo "deploy/release.env is missing -- copy deploy/release.env.example and fill it in" >&2; exit 1; }
set -a; . deploy/release.env; set +a

if [ "${1:-}" = "--rollback" ]; then
  ssh "$DEPLOY_HOST" "cd ~/platform && ./remote-release.sh --rollback ${2:?Usage: deploy/release.sh --rollback <version>}"
  exit 0
fi

VERSION="${1:?Usage: deploy/release.sh <version>}"
git diff --quiet && git diff --cached --quiet || {
  echo "Uncommitted changes -- a release must name a commit. Commit or stash first." >&2; exit 1; }
if git rev-parse -q --verify "refs/tags/$VERSION" > /dev/null; then
  echo "Tag $VERSION already exists -- pick the next version." >&2; exit 1
fi
[ -n "${AUTH_DOMAIN:-}" ] || { echo "AUTH_DOMAIN is empty in deploy/release.env" >&2; exit 1; }
# The reset page checks visitors with it; without it the console would ship Cloudflare's always-pass test key.
[ -n "${TURNSTILE_SITE_KEY:-}" ] || { echo "TURNSTILE_SITE_KEY is empty in deploy/release.env" >&2; exit 1; }

echo "== building $VERSION"
(cd backend && ./mvnw -B -q -DskipTests package)
(
  cd frontend
  npm ci --silent
  npm run generate:api
  # Compiled into the console: Authentik's issuer (trailing slash included), the client, the Turnstile site key.
  VITE_AUTH_ISSUER="https://$AUTH_DOMAIN/application/o/lifeplatform/" \
  VITE_AUTH_CLIENT_ID=lifeplatform-spa \
  VITE_TURNSTILE_SITE_KEY="$TURNSTILE_SITE_KEY" \
  VITE_API_BASE_URL=/api \
    npm run build
)

git tag "$VERSION"

echo "== staging"
STAGE=deploy/.stage
R="$STAGE/incoming/$VERSION"
rm -rf "$STAGE"; mkdir -p "$R/scripts" "$R/server" "$R/authentik/blueprints"
# The release itself, installed on the server as /opt/platform/releases/<version>.
cp backend/target/lifeplatform.jar "$R/"
cp -r frontend/dist "$R/console"
cp backend/scripts/migrate.sh backend/scripts/configure-db.sh "$R/scripts/"
cp -r backend/db-migrations "$R/db-migrations"
cp -r backend/infra/postgres/init "$R/postgres-init"
cp -r backend/mock-mobile-money "$R/mock-mobile-money"
cp -r deploy/server/systemd "$R/server/systemd"
cp deploy/web/Caddyfile "$R/server/Caddyfile"
# The production blueprint and the brand's images. Never the dev blueprint: its test users have passwords everyone
# knows (smoke.sh fails a release if one exists anyway).
cp deploy/authentik/blueprints/production/*.yaml "$R/authentik/blueprints/"
cp -r deploy/authentik/branding "$R/authentik/branding"
# The scripts the operator runs, in ~/platform.
cp deploy/server/*.sh deploy/server/versions.env "$STAGE/"
# A Windows checkout's CRLF makes every one of these unrunnable on the server, with "not found" as the only clue.
find "$STAGE" \( -name '*.sh' -o -name '*.env' -o -name '*.service' -o -name '*.timer' -o -name '*.conf' -o -name 'Caddyfile' \) \
  -exec sed -i 's/\r$//' {} +

echo "== copying to $DEPLOY_HOST"
tar czf - -C "$STAGE" . \
  | ssh "$DEPLOY_HOST" "mkdir -p ~/platform && tar xzf - -C ~/platform && chmod +x ~/platform/*.sh"

ssh "$DEPLOY_HOST" "cd ~/platform && ./remote-release.sh $VERSION"

echo
echo "Released $VERSION. Push the tag once you are happy with it:  git push origin $VERSION"
