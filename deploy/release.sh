#!/usr/bin/env bash
# Ships a version (2026-10-13, deployment pilot §8). Runs on the developer's machine:
#   1. refuses a dirty tree or an existing tag, then tags HEAD -- so a released version names an exact commit
#   2. builds the four images and pushes them (skipped in the local rehearsal)
#   3. copies the server files over and runs remote-release.sh there
#
# The tag is NOT pushed: it is pushed once the release is known good, which the last line reminds you of.
#
# Usage: deploy/release.sh <version>              e.g. v0.1.0
#        deploy/release.sh --rollback <version>
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f deploy/release.env ] || {
  echo "deploy/release.env is missing -- copy deploy/release.env.example and fill it in" >&2; exit 1; }
set -a; . deploy/release.env; . deploy/server/versions.env; set +a

remote() {
  if [ "$DEPLOY_MODE" = "local" ]; then
    (cd deploy/.rehearsal && SKIP_PULL=1 bash "$@")
  else
    ssh "$DEPLOY_HOST" "cd ~/platform && $*"
  fi
}

if [ "${1:-}" = "--rollback" ]; then
  remote ./remote-release.sh --rollback "${2:?Usage: deploy/release.sh --rollback <version>}"
  exit 0
fi

VERSION="${1:?Usage: deploy/release.sh <version>}"
git diff --quiet && git diff --cached --quiet || {
  echo "Uncommitted changes -- a release must name a commit. Commit or stash first." >&2; exit 1; }
if git rev-parse -q --verify "refs/tags/$VERSION" > /dev/null; then
  echo "Tag $VERSION already exists -- pick the next version." >&2; exit 1
fi

echo "== building $VERSION"
docker build -t "$IMAGE_PREFIX-backend:$VERSION" -f backend/infra/app/Dockerfile backend
docker build -t "$IMAGE_PREFIX-postgres:$VERSION" backend/infra/postgres
docker build -t "$IMAGE_PREFIX-auth:$VERSION" --build-arg KEYCLOAK_VERSION="$KEYCLOAK_VERSION" \
  -f deploy/auth/Dockerfile backend
docker build -t "$IMAGE_PREFIX-web:$VERSION" --build-arg AUTH_DOMAIN="$AUTH_DOMAIN" \
  --build-arg NODE_VERSION="$NODE_VERSION" --build-arg CADDY_VERSION="$CADDY_VERSION" \
  -f deploy/web/Dockerfile .

if [ "$DEPLOY_MODE" != "local" ]; then
  echo "== pushing"
  for image in backend postgres auth web; do docker push "$IMAGE_PREFIX-$image:$VERSION"; done
fi

git tag "$VERSION"

echo "== staging the server files"
STAGE=deploy/.stage
rm -rf "$STAGE"; mkdir -p "$STAGE/scripts"
cp deploy/server/compose.yml deploy/server/versions.env deploy/server/*.sh "$STAGE/"
cp backend/scripts/migrate.sh backend/scripts/configure-db.sh "$STAGE/scripts/"
cp -r backend/db-migrations "$STAGE/db-migrations"
cp -r backend/infra/postgres/init "$STAGE/postgres-init"
cp -r backend/mock-mobile-money "$STAGE/mock-mobile-money"
node deploy/keycloak/build-realms.mjs "$STAGE/realms"
# A Windows checkout's CRLF makes every one of these unrunnable on the server, with "not found" as the only clue.
find "$STAGE" -name '*.sh' -exec sed -i 's/\r$//' {} +

if [ "$DEPLOY_MODE" = "local" ]; then
  mkdir -p deploy/.rehearsal
  cp -r "$STAGE"/. deploy/.rehearsal/
else
  echo "== copying to $DEPLOY_HOST"
  tar czf - -C "$STAGE" . \
    | ssh "$DEPLOY_HOST" "mkdir -p ~/platform && tar xzf - -C ~/platform && chmod +x ~/platform/*.sh ~/platform/scripts/*.sh"
fi

remote ./remote-release.sh "$VERSION"

echo
echo "Released $VERSION. Push the tag once you are happy with it:  git push origin $VERSION"
