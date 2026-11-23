#!/usr/bin/env bash
# The release's last gate (2026-10-13; Authentik since 2026-11-20, ticket 10 of .scratch/authentik-cutover). Each
# check is a way a release has gone wrong, or could:
#
#   the console is served, the API refuses an anonymous caller, the backend is healthy by its own account,
#   Authentik reports the issuer the backend demands, its blueprint applied, the sign-in page is up, sign-out's revoke
#   reaches it, everything else on the auth address is shut to the internet, no dev test user exists, the front
#   door's Caddy has its rate limits, and the watcher's timer runs.
set -euo pipefail
cd "$(dirname "$0")"
. ./platform-lib.sh
# The rehearsal signs with Caddy's own CA, which this machine has no reason to trust.
K=""; [ -n "${CADDY_GLOBAL_OPTIONS:-}" ] && K="-k"
# Resolve both names to this host: on the server DNS may still be propagating, and the answer must not depend on it.
R="--resolve $APP_DOMAIN:443:127.0.0.1 --resolve $AUTH_DOMAIN:443:127.0.0.1"

fail() { echo "SMOKE FAILED: $1" >&2; exit 1; }
status() { curl -sS $K $R -o /dev/null -w '%{http_code}' "$@"; }

curl -sS $K $R "https://$APP_DOMAIN/" | grep -q '<div id="root"' \
  || fail "https://$APP_DOMAIN/ does not serve the console"

code=$(status "https://$APP_DOMAIN/api/policies")
[ "$code" = "401" ] || fail "/api without a token answered $code, expected 401 (is the proxy reaching the backend?)"

curl -sf "http://127.0.0.1:$BACKEND_MANAGEMENT_PORT/actuator/health" | grep -q '"status":"UP"' \
  || fail "the backend does not report itself UP"

ISSUER="https://$AUTH_DOMAIN/application/o/lifeplatform/"
curl -sS $K $R "${ISSUER}.well-known/openid-configuration" | grep -q "\"issuer\": *\"$ISSUER\"" \
  || fail "Authentik does not report issuer $ISSUER -- every token would be refused (is the blueprint applied?)"

code=$(status "https://$AUTH_DOMAIN/if/flow/lifeplatform-sign-in/")
[ "$code" = "200" ] || fail "the sign-in page answered $code, expected 200"

# Sign-out revokes through the console's own origin (Authentik's revoke sends no CORS headers). A request with no
# client is refused by Authentik itself; a 404 or the console's own page means Caddy did not forward it.
body=$(curl -sS $K $R -X POST "https://$APP_DOMAIN/oauth/revoke/" -w '\n%{http_code}')
code=$(printf '%s' "$body" | tail -1)
{ [ "$code" != "404" ] && ! printf '%s' "$body" | grep -q '<div id="root"'; } \
  || fail "https://$APP_DOMAIN/oauth/revoke/ does not reach Authentik (answered $code)"

# Everything but the sign-in pages is shut to the internet: the admin, the admin API, Authentik's user pages, and its
# stock flows -- default-authentication-flow above all, which signs in around the platform's own flow.
for path in /if/admin/ /api/v3/core/users/ /if/user/ /if/flow/default-authentication-flow/ \
            /api/v3/flows/executor/default-authentication-flow/ /-/health/live/; do
  code=$(status "https://$AUTH_DOMAIN$path")
  [ "$code" = "404" ] || fail "https://$AUTH_DOMAIN$path answered $code from the internet, expected 404"
done

# Inside Authentik: the blueprint applied cleanly, and no dev test user exists. The dev blueprint is never in the
# -auth image, but a login copied from a dev database, or made by hand with a dev name, would hold the dev password
# everybody knows.
OUT=$(ak_manage -- shell -c '
from authentik.blueprints.models import BlueprintInstance
from authentik.core.models import User
DEV = ["staff.underwriter", "staff.senior", "staff.assessor", "staff.manager", "staff.management", "staff.finance",
       "staff.finance-approver", "staff.service", "staff.admin", "agent.senior", "agent.junior", "customer.owner",
       "customer.other", "regulator.tira", "agent.customer", "e2e.sign-in-code"]
blueprint = BlueprintInstance.objects.filter(path="lifeplatform/lifeplatform.yaml").first()
print("RESULT:BLUEPRINT " + (blueprint.status if blueprint else "missing"))
dev = User.objects.filter(path__startswith="lifeplatform/dev") | User.objects.filter(username__in=DEV)
print("RESULT:DEV " + ",".join(sorted(set(dev.values_list("username", flat=True)))))
' 2>&1) || fail "could not ask Authentik about its blueprint and users"
blueprint=$(printf '%s\n' "$OUT" | sed -n 's/^RESULT:BLUEPRINT //p')
[ "$blueprint" = "successful" ] || fail "Authentik's lifeplatform blueprint is '${blueprint:-unknown}', not successful"
dev=$(printf '%s\n' "$OUT" | sed -n 's/^RESULT:DEV //p' | tr -d '\r')
[ -z "$dev" ] || fail "dev test users exist in Authentik: $dev -- delete them; they hold a password everyone knows"

# The front door's rate limits (2026-11-23): the Caddy in service has the module. Only the module, not the exact build:
# after a rollback the build is the older release's, and this folder's versions.env names the newer one. Tripping a
# limit here would spend real visitors' allowance, so the limits themselves are proven by
# deploy/test/rate-limit-rehearsal.sh.
caddy list-modules 2>/dev/null | grep -qx 'http.handlers.rate_limit' \
  || fail "the installed Caddy has no rate limiting (run ./install-caddy.sh)"

# The watcher (2026-11-23): without it, a broken server stays broken until someone looks.
systemctl is-active --quiet platform-watch.timer || fail "the watcher's timer (platform-watch.timer) is not active"
[ -n "${ALERT_PHONES:-}" ] || fail "ALERT_PHONES is empty in .env: the watcher has nobody to text"

echo "Smoke check passed."
