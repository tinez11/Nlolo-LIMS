#!/usr/bin/env bash
# The release's last gate (2026-10-13). Five questions, each one a way a release has actually gone wrong:
# does the site serve the console, does the API refuse an anonymous caller, is the backend healthy by its own
# account, does Keycloak report the issuer the backend demands, and is the admin console shut to the internet.
set -euo pipefail
cd "$(dirname "$0")"
set -a; . ./.env; set +a

DC="docker compose --env-file .env --env-file versions.env -f compose.yml"
# The rehearsal signs with Caddy's own CA, which this machine has no reason to trust.
K=""; [ -n "${CADDY_GLOBAL_OPTIONS:-}" ] && K="-k"
# Resolve both names to this host: on the server DNS may still be propagating, and the answer must not depend on it.
R="--resolve $APP_DOMAIN:443:127.0.0.1 --resolve $AUTH_DOMAIN:443:127.0.0.1"

fail() { echo "SMOKE FAILED: $1" >&2; exit 1; }

curl -sS $K $R "https://$APP_DOMAIN/" | grep -q '<div id="root"' \
  || fail "https://$APP_DOMAIN/ does not serve the console"

code=$(curl -sS $K $R -o /dev/null -w '%{http_code}' "https://$APP_DOMAIN/api/policies")
[ "$code" = "401" ] || fail "/api without a token answered $code, expected 401 (is the proxy reaching the backend?)"

$DC exec -T backend wget -qO- http://localhost:9090/actuator/health | grep -q '"status":"UP"' \
  || fail "the backend does not report itself UP"

curl -sS $K $R "https://$AUTH_DOMAIN/realms/staff/.well-known/openid-configuration" \
  | grep -q "\"issuer\":\"https://$AUTH_DOMAIN/realms/staff\"" \
  || fail "the staff realm does not report issuer https://$AUTH_DOMAIN/realms/staff -- every token would be refused"

code=$(curl -sS $K $R -o /dev/null -w '%{http_code}' "https://$AUTH_DOMAIN/admin/")
[ "$code" = "404" ] || fail "the Keycloak admin console answered $code from the internet, expected 404"

echo "Smoke check passed."
