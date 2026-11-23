#!/usr/bin/env bash
# Proves the edge rate limits (backend/docs/superpowers/specs/2026-11-23-edge-rate-limit-design.md) on this PC,
# before any server: builds Caddy exactly as the server does (install-caddy.sh --build-to), in a golang container at
# the pinned Go, then runs the REAL deploy/web/Caddyfile with test domains in front of stub upstreams.
#
# Usage, from Git Bash at the repository root: deploy/test/rate-limit-rehearsal.sh
set -euo pipefail

if [ "${1:-}" != "--inside" ]; then
  cd "$(dirname "$0")/../.."
  . deploy/server/versions.env
  # Git Bash would rewrite /repo into a Windows path; pwd -W gives Docker the C:/ form.
  MSYS_NO_PATHCONV=1 exec docker run --rm -v "$(pwd -W 2>/dev/null || pwd):/repo:ro" \
    "golang:$CADDY_GO_VERSION" bash /repo/deploy/test/rate-limit-rehearsal.sh --inside
fi

# ---- inside the container ----------------------------------------------------------------------------------------
cp -r /repo/deploy/server /tmp/server
sed -i 's/\r$//' /tmp/server/*.sh /tmp/server/versions.env
bash /tmp/server/install-caddy.sh --build-to /tmp/bin
CADDY=/tmp/bin/caddy

# Stub upstreams: the backend (8080) and Authentik (9000) each answer 200 "ok".
"$CADDY" respond --listen 127.0.0.1:8080 --body ok >/dev/null 2>&1 &
"$CADDY" respond --listen 127.0.0.1:9000 --body ok >/dev/null 2>&1 &
mkdir -p /opt/platform/current/console
echo '<div id="root"></div>' > /opt/platform/current/console/index.html

sed 's/\r$//' /repo/deploy/web/Caddyfile > /tmp/Caddyfile
APP=app.rehearsal.test; AUTH=auth.rehearsal.test
FAILED=0
fail() { echo "FAIL: $*" >&2; FAILED=1; }

start_caddy() {   # start_caddy <RATE_LIMIT_EXEMPT value>
  if [ -n "${CADDY_PID:-}" ]; then kill "$CADDY_PID"; wait "$CADDY_PID" 2>/dev/null || true; fi
  APP_DOMAIN=$APP AUTH_DOMAIN=$AUTH ACME_EMAIL=ops@rehearsal.test RATE_LIMIT_EXEMPT="$1" \
    CADDY_GLOBAL_OPTIONS=$'local_certs\n\tskip_install_trust' \
    "$CADDY" run --config /tmp/Caddyfile --adapter caddyfile >/tmp/caddy.log 2>&1 &
  CADDY_PID=$!
  for _ in $(seq 1 50); do
    curl -sk -o /dev/null --resolve "$APP:443:127.0.0.1" "https://$APP/" && return 0
    sleep 0.2
  done
  cat /tmp/caddy.log >&2; echo "Caddy did not start with RATE_LIMIT_EXEMPT='$1'" >&2; exit 1
}

code() {   # code <host> <curl args...>  -> the HTTP status
  local host="$1"; shift
  curl -sk -o /dev/null -w '%{http_code}' --resolve "$host:443:127.0.0.1" "$@"
}

# expect_limit <label> <allowed> <host> <curl args...>: the first <allowed> answers are not 429, the next one is.
expect_limit() {
  local label="$1" allowed="$2" host="$3"; shift 3
  local i c
  for i in $(seq 1 "$allowed"); do
    c=$(code "$host" "$@"); [ "$c" != 429 ] || { fail "$label: request $i of $allowed was already 429"; return; }
  done
  c=$(code "$host" "$@"); [ "$c" = 429 ] || fail "$label: request $((allowed + 1)) answered $c, expected 429"
}

expect_never() {   # expect_never <label> <count> <host> <curl args...>
  local label="$1" n="$2" host="$3"; shift 3
  local i c
  for i in $(seq 1 "$n"); do
    c=$(code "$host" "$@"); [ "$c" != 429 ] || { fail "$label: request $i was 429"; return; }
  done
}

SIGNIN=(-X POST "https://$AUTH/api/v3/flows/executor/lifeplatform-sign-in/")

echo "-- nothing exempt (the release's placeholder)"
start_caddy 192.0.2.255/32
expect_never "console files" 700 "$APP" "https://$APP/"
expect_never "token endpoint" 100 "$AUTH" -X POST "https://$AUTH/application/o/token/"
expect_limit "signin" 60 "$AUTH" "${SIGNIN[@]}"
headers=$(curl -sk -D - -o /tmp/body --resolve "$AUTH:443:127.0.0.1" "${SIGNIN[@]}")
grep -qi '^retry-after:' <<<"$headers" || fail "signin 429 has no Retry-After"
grep -qi '^content-type: text/plain' <<<"$headers" || fail "signin 429 is not text/plain"
grep -q 'Too many requests from your network. Wait a minute and try again.' /tmp/body || fail "signin 429 words"
expect_limit "public" 30 "$APP" "https://$APP/api/public/products"
expect_limit "api" 600 "$APP" "https://$APP/api/policies"
headers=$(curl -sk -D - -o /tmp/body --resolve "$APP:443:127.0.0.1" "https://$APP/api/policies")
grep -qi '^retry-after:' <<<"$headers" || fail "api 429 has no Retry-After"
grep -qi '^content-type: application/problem+json' <<<"$headers" || fail "api 429 is not problem+json"
grep -q '"errorCode":"RATE_LIMITED"' /tmp/body || fail "api 429 body: $(cat /tmp/body)"

echo "-- this address exempt, second in a two-entry list (the setting is space-separated)"
start_caddy "192.0.2.1/32 127.0.0.1/32"
expect_never "signin, exempt" 100 "$AUTH" "${SIGNIN[@]}"
expect_never "api, exempt" 700 "$APP" "https://$APP/api/policies"
expect_limit "public, exempt address still limited" 30 "$APP" "https://$APP/api/public/products"

echo "-- RATE_LIMIT_EXEMPT unset: the Caddyfile's own default"
kill "$CADDY_PID"; wait "$CADDY_PID" 2>/dev/null || true
APP_DOMAIN=$APP AUTH_DOMAIN=$AUTH ACME_EMAIL=ops@rehearsal.test CADDY_GLOBAL_OPTIONS=$'local_certs\n\tskip_install_trust' \
  "$CADDY" validate --config /tmp/Caddyfile --adapter caddyfile >/tmp/validate.log 2>&1 \
  || { cat /tmp/validate.log >&2; fail "the Caddyfile does not validate with RATE_LIMIT_EXEMPT unset"; }

[ "$FAILED" = 0 ] || { echo "REHEARSAL FAILED" >&2; exit 1; }
echo "REHEARSAL PASSED"
