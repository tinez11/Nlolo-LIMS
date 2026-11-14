#!/usr/bin/env bash
# Puts the staff sign-in code flow (2026-11-14) on the RUNNING dev realm. Dev only: --import-realm skips a realm that
# already exists, so a change to keycloak/lifeplatform-realm.json reaches a fresh install but not this one. Production
# gets the flow from the generated realm (deploy/keycloak/build-realms.mjs) through keycloak-config-cli.
#
# Idempotent: when the flow already exists it only makes sure the realm uses it.
# Needs the extension jar built and the keycloak container recreated first, so lifeplatform-staff-sign-in-code exists.
set -euo pipefail
# Git Bash on Windows rewrites /opt/... into C:/Program Files/Git/opt/...; docker exec needs the container's own path.
export MSYS_NO_PATHCONV=1

CONTAINER="${KEYCLOAK_CONTAINER:-infra-keycloak-1}"
REALM=lifeplatform
kc() { docker exec "$CONTAINER" /opt/keycloak/bin/kcadm.sh "$@"; }

kc config credentials --server http://localhost:8080 --realm master --user admin \
  --password "${KEYCLOAK_ADMIN_PASSWORD:-devadmin}" >/dev/null

if kc get authentication/flows -r "$REALM" --fields alias | grep -q '"lifeplatform browser"'; then
  echo "Flow 'lifeplatform browser' already exists"
else
  kc create authentication/flows -r "$REALM" -s alias="lifeplatform browser" -s providerId=basic-flow \
    -s topLevel=true -s builtIn=false \
    -s description="Sign in; staff then enter a code texted to the phone on their staff record (2026-11-14)"
  kc create "authentication/flows/lifeplatform%20browser/executions/execution" -r "$REALM" -s provider=auth-cookie
  kc create "authentication/flows/lifeplatform%20browser/executions/flow" -r "$REALM" \
    -s alias="lifeplatform browser forms" -s type=basic-flow \
    -s description="Username and password, then the staff sign-in code (others pass straight through)"
  kc create "authentication/flows/lifeplatform%20browser%20forms/executions/execution" -r "$REALM" \
    -s provider=auth-username-password-form
  kc create "authentication/flows/lifeplatform%20browser%20forms/executions/execution" -r "$REALM" \
    -s provider=lifeplatform-staff-sign-in-code

  # Requirements: cookie and the forms subflow are alternatives; inside the forms, both steps are required.
  set_requirement() {   # set_requirement <flow alias, url-encoded> <display name or provider match> <requirement>
    local id
    id=$(kc get "authentication/flows/$1/executions" -r "$REALM" \
      | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{const e=JSON.parse(s).find(x=>x.providerId==='$2'||x.displayName==='$2');process.stdout.write(e?e.id:'')})")
    [ -n "$id" ] || { echo "No execution $2 in $1" >&2; exit 1; }
    kc update "authentication/flows/$1/executions" -r "$REALM" -b "{\"id\":\"$id\",\"requirement\":\"$3\"}"
  }
  set_requirement "lifeplatform%20browser" auth-cookie ALTERNATIVE
  set_requirement "lifeplatform%20browser" "lifeplatform browser forms" ALTERNATIVE
  set_requirement "lifeplatform%20browser%20forms" auth-username-password-form REQUIRED
  set_requirement "lifeplatform%20browser%20forms" lifeplatform-staff-sign-in-code REQUIRED
fi

kc update "realms/$REALM" -s browserFlow="lifeplatform browser"
echo "Realm $REALM now signs in through 'lifeplatform browser'"
