#!/usr/bin/env bash
# Creates a staff, agent or regulator login with the claims the platform needs (2026-10-13, deployment pilot §3):
# tenant_id always, home_branch for staff, party_id for agents. Prints a one-time password; Keycloak makes them
# choose their own at first sign-in.
#
# WHY A SCRIPT. Typing these attributes into Keycloak's admin screens is where mistakes live: a token missing
# tenant_id is refused by TenantContextFilter before it reaches a controller, with a 403 that says nothing about the
# cause, and a staff member with no home_branch cannot post a journal. Nothing about that is discoverable from the
# admin console, so the knowledge belongs here.
#
# Customers are NOT created here -- staff invite them from the console, which creates the login and writes party_id
# from the record on screen, so nobody transcribes an id.
#
# Run on the server, in the platform directory:
#   ./add-user.sh staff asha.juma --roles UNDERWRITER --home-branch DSM --first-name Asha --last-name Juma
#   ./add-user.sh agents john.mwita --roles '' --party-id 7ddd05c3-... --first-name John --last-name Mwita
set -euo pipefail
cd "$(dirname "$0")"
set -a; . ./.env; . ./versions.env; set +a

REALM="${1:?Usage: ./add-user.sh <staff|agents|regulators> <username> [options]}"
USERNAME="${2:?username}"
shift 2

ROLES=""; HOME_BRANCH=""; PARTY_ID=""; EMAIL=""; FIRST=""; LAST=""
while [ $# -gt 0 ]; do
  case "$1" in
    --roles)       ROLES="$2"; shift 2 ;;
    --home-branch) HOME_BRANCH="$2"; shift 2 ;;
    --party-id)    PARTY_ID="$2"; shift 2 ;;
    --email)       EMAIL="$2"; shift 2 ;;
    --first-name)  FIRST="$2"; shift 2 ;;
    --last-name)   LAST="$2"; shift 2 ;;
    *) echo "Unknown option $1" >&2; exit 1 ;;
  esac
done

case "$REALM" in
  staff|agents|regulators) ;;
  *) echo "realm must be staff, agents or regulators (customers are invited from the console)" >&2; exit 1 ;;
esac
if [ "$REALM" = staff ] && [ -z "$HOME_BRANCH" ]; then
  echo "staff need --home-branch (it goes in the token; without it they cannot post a journal)" >&2; exit 1
fi
if [ "$REALM" = agents ] && [ -z "$PARTY_ID" ]; then
  echo "agents need --party-id -- the agent's party id, copied from their record in the console" >&2; exit 1
fi

DC="docker compose --env-file .env --env-file versions.env -f compose.yml"
kc() { $DC exec -T keycloak /opt/keycloak/bin/kcadm.sh "$@"; }

kc config credentials --server http://localhost:8080 --realm master \
  --user admin --password "$KC_ADMIN_PASSWORD" > /dev/null

ATTRIBUTES="\"tenant_id\":[\"$PLATFORM_TENANT_ID\"]"
[ -n "$HOME_BRANCH" ] && ATTRIBUTES="$ATTRIBUTES,\"home_branch\":[\"$HOME_BRANCH\"]"
[ -n "$PARTY_ID" ] && ATTRIBUTES="$ATTRIBUTES,\"party_id\":[\"$PARTY_ID\"]"

BODY="{\"username\":\"$USERNAME\",\"enabled\":true,\"firstName\":\"$FIRST\",\"lastName\":\"$LAST\""
BODY="$BODY,\"attributes\":{$ATTRIBUTES}"
[ -n "$EMAIL" ] && BODY="$BODY,\"email\":\"$EMAIL\",\"emailVerified\":true"
BODY="$BODY,\"requiredActions\":[\"UPDATE_PASSWORD\"]}"
printf '%s' "$BODY" | kc create users -r "$REALM" -f - > /dev/null

# Ends in Aa1 so it satisfies the realm's policy whatever the random part came out as.
PASSWORD="$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-14)Aa1"
kc set-password -r "$REALM" --username "$USERNAME" --new-password "$PASSWORD" --temporary

if [ "$ROLES" = "ALL" ]; then
  ROLES=$(kc get roles -r "$REALM" --fields name --format csv --noquotes \
    | tr -d '\r' | grep -vE '^(offline_access|uma_authorization|default-roles-.*|)$' | paste -sd, -)
fi
if [ -n "$ROLES" ]; then
  IFS=',' read -ra GRANTED <<< "$ROLES"
  for role in "${GRANTED[@]}"; do
    [ -n "$role" ] && kc add-roles -r "$REALM" --uusername "$USERNAME" --rolename "$role"
  done
fi

echo "Created $REALM login $USERNAME with roles [${ROLES:-none}]."
echo "One-time password (shown once): $PASSWORD"
echo "They must change it at first sign-in."
