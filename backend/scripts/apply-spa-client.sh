#!/usr/bin/env bash
#
# Creates or updates the `lifeplatform-spa` public PKCE client in a Keycloak realm.
#
# WHY THIS SCRIPT EXISTS
# ----------------------
# Keycloak's `--import-realm` only imports a realm that does not already exist. This
# stack keeps Keycloak's own data in Postgres (KC_DB=postgres, volume
# infra_postgres-data), so on any environment that has been started before, editing
# keycloak/*-realm.json has NO EFFECT and the change is skipped in silence -- no
# warning, no error, just a client that is not there. The SPA then fails with an
# opaque "invalid client" at the Keycloak login page.
#
# A fresh volume (or CI) imports the file correctly, which is exactly what makes
# this trap easy to miss.
#
# Idempotent: safe to re-run. Creates the client if absent, updates it if present.
#
# Usage:  ./scripts/apply-spa-client.sh [realm ...]        (default: staff)
set -euo pipefail

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8081}"
ADMIN_USER="${KEYCLOAK_ADMIN:-admin}"
ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-devadmin}"
CLIENT_ID="lifeplatform-spa"
SPA_ORIGIN="${SPA_ORIGIN:-http://localhost:5173}"

REALMS=("${@:-staff}")

log()  { printf '  %s\n' "$*"; }
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

command -v curl >/dev/null || fail "curl is required"
command -v node >/dev/null || fail "node is required (used to read JSON)"

# The expression is parenthesised: `a && b ?? ''` is a syntax error in JS without it.
json_get() { node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{try{const v=JSON.parse(s);console.log(($1)??'')}catch{console.log('')}})"; }

printf 'Authenticating against %s\n' "$KEYCLOAK_URL"
TOKEN="$(curl -sS -X POST "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" \
  -d "client_id=admin-cli" \
  -d "username=$ADMIN_USER" \
  -d "password=$ADMIN_PASSWORD" \
  -d "grant_type=password" | json_get 'v.access_token')"
[ -n "$TOKEN" ] || fail "could not obtain an admin token (is Keycloak up, and are the admin credentials right?)"

# Mirrors keycloak/<realm>-realm.json. A browser cannot hold a secret, so this is
# publicClient with PKCE S256 required; the pre-existing confidential
# `lifeplatform-app` is left untouched for backend/service use.
payload() {
  cat <<JSON
{
  "clientId": "$CLIENT_ID",
  "name": "Life Platform SPA (browser, PKCE)",
  "enabled": true,
  "publicClient": true,
  "protocol": "openid-connect",
  "standardFlowEnabled": true,
  "implicitFlowEnabled": false,
  "directAccessGrantsEnabled": false,
  "serviceAccountsEnabled": false,
  "redirectUris": ["$SPA_ORIGIN/*"],
  "webOrigins": ["$SPA_ORIGIN"],
  "attributes": {
    "pkce.code.challenge.method": "S256",
    "post.logout.redirect.uris": "$SPA_ORIGIN/*"
  },
  "protocolMappers": [
    {
      "name": "tenant_id",
      "protocol": "openid-connect",
      "protocolMapper": "oidc-usermodel-attribute-mapper",
      "consentRequired": false,
      "config": {
        "user.attribute": "tenant_id",
        "claim.name": "tenant_id",
        "jsonType.label": "String",
        "id.token.claim": "true",
        "access.token.claim": "true",
        "userinfo.token.claim": "true"
      }
    }
  ]
}
JSON
}

for realm in "${REALMS[@]}"; do
  printf '\nRealm: %s\n' "$realm"

  status="$(curl -sS -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" \
    "$KEYCLOAK_URL/admin/realms/$realm")"
  [ "$status" = "200" ] || fail "realm '$realm' not found (HTTP $status)"

  uuid="$(curl -sS -H "Authorization: Bearer $TOKEN" \
    "$KEYCLOAK_URL/admin/realms/$realm/clients?clientId=$CLIENT_ID" \
    | json_get 'v[0]&&v[0].id')"

  if [ -n "$uuid" ]; then
    log "client exists ($uuid) -- updating"
    code="$(payload | curl -sS -o /dev/null -w '%{http_code}' -X PUT \
      -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
      --data @- "$KEYCLOAK_URL/admin/realms/$realm/clients/$uuid")"
    [ "$code" = "204" ] || fail "update failed (HTTP $code)"
    log "updated"
  else
    log "client absent -- creating"
    code="$(payload | curl -sS -o /dev/null -w '%{http_code}' -X POST \
      -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
      --data @- "$KEYCLOAK_URL/admin/realms/$realm/clients")"
    [ "$code" = "201" ] || fail "create failed (HTTP $code)"
    log "created"
  fi

  # Verify by reading it back rather than trusting the write's status code.
  verified="$(curl -sS -H "Authorization: Bearer $TOKEN" \
    "$KEYCLOAK_URL/admin/realms/$realm/clients?clientId=$CLIENT_ID" \
    | json_get 'v[0]&&[v[0].publicClient,(v[0].attributes||{})["pkce.code.challenge.method"],(v[0].redirectUris||[]).join(",")].join(" | ")')"
  [ -n "$verified" ] || fail "client not readable after write"
  log "verified: public | pkce | redirects = $verified"
done

printf '\nDone.\n'
