#!/usr/bin/env bash
# IFRS 17 I4: adds the FINANCE_APPROVER realm role and the dev user staff.finance-approver (FINANCE_OFFICER +
# FINANCE_APPROVER, home branch DSM) to a RUNNING dev Keycloak, which does not re-import staff-realm.json once its
# realm exists. Idempotent: what is already there is left alone and reported.
#
# Usage: scripts/apply-finance-approver.sh   (KEYCLOAK_URL, KEYCLOAK_ADMIN, KEYCLOAK_ADMIN_PASSWORD override)
set -euo pipefail

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8081}"
ADMIN_USER="${KEYCLOAK_ADMIN:-admin}"
ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-devadmin}"
REALM="staff"
ROLE="FINANCE_APPROVER"
USERNAME="staff.finance-approver"
TENANT_ID="11111111-1111-1111-1111-111111111111"

log()  { printf '  %s\n' "$*"; }
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

command -v curl >/dev/null || fail "curl is required"
command -v node >/dev/null || fail "node is required (used to read JSON)"

json_get() { node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{try{const v=JSON.parse(s);console.log(($1)??'')}catch{console.log('')}})"; }

printf 'Authenticating against %s\n' "$KEYCLOAK_URL"
TOKEN="$(curl -sS -X POST "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" \
  -d "client_id=admin-cli" -d "username=$ADMIN_USER" -d "password=$ADMIN_PASSWORD" -d "grant_type=password" \
  | json_get 'v.access_token')"
[ -n "$TOKEN" ] || fail "could not obtain an admin token (is Keycloak up, and are the admin credentials right?)"
API="$KEYCLOAK_URL/admin/realms/$REALM"
AUTH=(-H "Authorization: Bearer $TOKEN")

printf '\nRole\n'
code="$(curl -sS -o /dev/null -w '%{http_code}' "${AUTH[@]}" "$API/roles/$ROLE")"
if [ "$code" != "200" ]; then
  code="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "${AUTH[@]}" -H 'Content-Type: application/json' \
    --data '{"name":"FINANCE_APPROVER","description":"IFRS 17 I4: approves or rejects a manual journal prepared by someone else. Additive to FINANCE_OFFICER; given to finance managers."}' \
    "$API/roles")"
  [ "$code" = "201" ] || fail "creating role $ROLE failed (HTTP $code)"
  log "$ROLE created"
else
  log "$ROLE already present"
fi

printf '\nUser\n'
id="$(curl -sS "${AUTH[@]}" "$API/users?username=$USERNAME&exact=true" | json_get 'v[0]&&v[0].id')"
if [ -z "$id" ]; then
  body='{"username":"staff.finance-approver","enabled":true,"emailVerified":true,"email":"staff.finance-approver@example.tz","firstName":"Baraka","lastName":"Approver","attributes":{"tenant_id":["'"$TENANT_ID"'"],"home_branch":["DSM"]},"credentials":[{"type":"password","value":"devpassword","temporary":false}]}'
  code="$(printf '%s' "$body" | curl -sS -o /dev/null -w '%{http_code}' -X POST "${AUTH[@]}" \
    -H 'Content-Type: application/json' --data @- "$API/users")"
  [ "$code" = "201" ] || fail "creating $USERNAME failed (HTTP $code)"
  id="$(curl -sS "${AUTH[@]}" "$API/users?username=$USERNAME&exact=true" | json_get 'v[0]&&v[0].id')"
  log "$USERNAME created"
else
  log "$USERNAME already present"
fi

roles="[$(curl -sS "${AUTH[@]}" "$API/roles/FINANCE_OFFICER"),$(curl -sS "${AUTH[@]}" "$API/roles/$ROLE")]"
code="$(printf '%s' "$roles" | curl -sS -o /dev/null -w '%{http_code}' -X POST "${AUTH[@]}" \
  -H 'Content-Type: application/json' --data @- "$API/users/$id/role-mappings/realm")"
[ "$code" = "204" ] || fail "assigning roles to $USERNAME failed (HTTP $code)"
held="$(curl -sS "${AUTH[@]}" "$API/users/$id/role-mappings/realm" | json_get 'v.map(r=>r.name).sort().join(",")')"
case ",$held," in
  *,FINANCE_APPROVER,*) log "$USERNAME holds: $held";;
  *) fail "$USERNAME read back without $ROLE ($held)";;
esac

printf '\nDone.\n'
