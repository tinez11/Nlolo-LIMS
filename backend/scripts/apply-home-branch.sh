#!/usr/bin/env bash
#
# Gives the seeded staff users a home branch and puts it in their tokens (IFRS 17 I2).
#
# WHY THIS SCRIPT EXISTS
# ----------------------
# A staff member's home branch is the branch a case they open defaults to. Staff users live in Keycloak -- the
# platform has no staff table -- so the branch is a Keycloak user attribute, `home_branch`, sent as a token claim by a
# user-attribute mapper on both staff clients. keycloak/staff-realm.json carries both for a FRESH import; on an
# environment that has been started before, `--import-realm` skips the realm in silence (see apply-spa-client.sh), so
# this script applies the same two changes to the running Keycloak.
#
# Idempotent: adds the mapper only where it is missing and sets the attribute without dropping any other.
# Verifies by reading each user back, and fails loudly if Keycloak dropped the attribute (a user-profile policy that
# refuses unmanaged attributes would): the platform then falls back to the case's other defaults, which is safe but
# is not what this script was asked to do.
#
# Usage:  ./scripts/apply-home-branch.sh
set -euo pipefail

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8081}"
ADMIN_USER="${KEYCLOAK_ADMIN:-admin}"
ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-devadmin}"
REALM="staff"
CLIENTS=(lifeplatform-app lifeplatform-spa)
# username:branch -- staff.finance sits in Arusha so a staff default can be told from the head-office fallback.
USERS=(staff.underwriter:DSM staff.senior:DSM staff.assessor:DSM staff.manager:DSM staff.finance:ARU staff.admin:DSM)

log()  { printf '  %s\n' "$*"; }
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

command -v curl >/dev/null || fail "curl is required"
command -v node >/dev/null || fail "node is required (used to read and write JSON)"

json_get() { node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{try{const v=JSON.parse(s);console.log(($1)??'')}catch{console.log('')}})"; }

printf 'Authenticating against %s\n' "$KEYCLOAK_URL"
TOKEN="$(curl -sS -X POST "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" \
  -d "client_id=admin-cli" -d "username=$ADMIN_USER" -d "password=$ADMIN_PASSWORD" -d "grant_type=password" \
  | json_get 'v.access_token')"
[ -n "$TOKEN" ] || fail "could not obtain an admin token (is Keycloak up, and are the admin credentials right?)"

MAPPER='{"name":"home_branch","protocol":"openid-connect","protocolMapper":"oidc-usermodel-attribute-mapper","consentRequired":false,"config":{"user.attribute":"home_branch","claim.name":"home_branch","jsonType.label":"String","id.token.claim":"true","access.token.claim":"true","userinfo.token.claim":"true"}}'

printf '\nMappers on realm %s\n' "$REALM"
for client_id in "${CLIENTS[@]}"; do
  uuid="$(curl -sS -H "Authorization: Bearer $TOKEN" "$KEYCLOAK_URL/admin/realms/$REALM/clients?clientId=$client_id" \
    | json_get 'v[0]&&v[0].id')"
  [ -n "$uuid" ] || fail "client $client_id not found in realm $REALM"
  models="$KEYCLOAK_URL/admin/realms/$REALM/clients/$uuid/protocol-mappers/models"
  has="$(curl -sS -H "Authorization: Bearer $TOKEN" "$models" | json_get "v.some(m=>m.name==='home_branch')")"
  if [ "$has" != "true" ]; then
    code="$(printf '%s' "$MAPPER" | curl -sS -o /dev/null -w '%{http_code}' -X POST \
      -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' --data @- "$models")"
    [ "$code" = "201" ] || fail "adding the home_branch mapper to $client_id failed (HTTP $code)"
  fi
  has="$(curl -sS -H "Authorization: Bearer $TOKEN" "$models" | json_get "v.some(m=>m.name==='home_branch')")"
  [ "$has" = "true" ] || fail "home_branch mapper missing on $client_id after write"
  log "$client_id: home_branch mapper present"
done

printf '\nUsers\n'
for pair in "${USERS[@]}"; do
  username="${pair%%:*}"; branch="${pair##*:}"
  user_json="$(curl -sS -H "Authorization: Bearer $TOKEN" \
    "$KEYCLOAK_URL/admin/realms/$REALM/users?username=$username&exact=true")"
  id="$(printf '%s' "$user_json" | json_get 'v[0]&&v[0].id')"
  [ -n "$id" ] || fail "user $username not found"
  # The whole representation goes back, with home_branch merged into the attributes it already has: a PUT carrying
  # only `attributes` would replace them, and a lost tenant_id is a user who 403s on every request.
  body="$(printf '%s' "$user_json" | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{const u=JSON.parse(s)[0];u.attributes=Object.assign({},u.attributes,{home_branch:['$branch']});console.log(JSON.stringify(u))})")"
  code="$(printf '%s' "$body" | curl -sS -o /dev/null -w '%{http_code}' -X PUT \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' --data @- \
    "$KEYCLOAK_URL/admin/realms/$REALM/users/$id")"
  [ "$code" = "204" ] || fail "updating $username failed (HTTP $code)"
  read_back="$(curl -sS -H "Authorization: Bearer $TOKEN" "$KEYCLOAK_URL/admin/realms/$REALM/users/$id" \
    | json_get '[(v.attributes||{}).home_branch?.[0], (v.attributes||{}).tenant_id?.[0]].join(" ")')"
  case "$read_back" in
    "$branch "?*) log "$username: home_branch=$branch (tenant_id kept)";;
    *) fail "$username read back as '$read_back' -- Keycloak did not keep home_branch, or tenant_id was lost";;
  esac
done

printf '\nDone.\n'
