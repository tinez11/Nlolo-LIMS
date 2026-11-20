#!/usr/bin/env bash
# Creates a staff, agent, customer or regulator login in Authentik with what the platform needs (2026-10-13, deployment
# pilot §3; Authentik since 2026-11-20, ticket 10 of .scratch/authentik-cutover): tenant_id always, home_branch and a
# phone for staff, party_id for agents and customers, and job roles in the job_roles attribute, which the platform
# reads once, at the login's first sign-in, into Users and roles. Prints a one-time password; the sign-in flow makes
# them choose their own at first sign-in.
#
# The workspace is the Authentik group of that name (staff, agent, customer, regulator): it is what lets the login open
# a console at all. A staff login never holds another workspace (decision U10), so `--roles ALL` means every JOB role.
#
# WHY A SCRIPT. Getting these attributes wrong by hand is where mistakes live: a token missing tenant_id is refused by
# TenantContextFilter with a 403 that says nothing about the cause, and a staff member with no home_branch cannot post
# a journal.
#
# Most logins are NOT made here. Staff are added from Users and roles, agents from their page, and customers by a
# portal invite: those write party_id from the record on screen, so nobody transcribes an id. This script is for the
# first ones, before anyone can sign in, and for regulators, who have no screen.
#
# Run on the server, in the platform directory (either form):
#   ./add-user.sh staff asha.juma --roles ADMIN --home-branch DSM --phone +255712345678 --first-name Asha --last-name Juma
#   ./add-user.sh asha.juma --workspace staff --roles ADMIN --home-branch DSM --phone +255712345678
#   ./add-user.sh agent john.mwita --party-id 7ddd05c3-... --first-name John --last-name Mwita
#   ./add-user.sh regulator tira.inspector --first-name Salum --last-name Tira
#
# STAFF NEED --phone (2026-11-14): every staff sign-in asks for a code texted to their phone. A login made here has no
# staff record until its first sign-in, so the phone goes on the login itself; the platform texts it until an
# administrator sets the phone on the staff record (Users and roles).
#
# It runs Authentik's own management shell on this server (platform-lib.sh's ak_manage), so it needs no administrator
# token: nothing on this server holds one.
set -euo pipefail
cd "$(dirname "$0")"
. ./platform-lib.sh

# Every built-in job role, for --roles ALL. The script runs before the backend's first request seeds a new tenant's
# roles, so it carries its own list; AddUserScriptRolesTest keeps it equal to the backend's BuiltInRoles.
ALL_JOB_ROLES="ADMIN,MANAGEMENT,SENIOR_UNDERWRITER,UNDERWRITER,CLAIMS_MANAGER,CLAIMS_ASSESSOR,FINANCE_APPROVER,FINANCE_OFFICER,ACTUARY,CUSTOMER_SERVICE_REP"

USAGE="Usage: ./add-user.sh [<staff|agent|customer|regulator>] <username> [--workspace <...>] [options]"
POSITIONAL=(); WORKSPACE=""
ROLES=""; HOME_BRANCH=""; PARTY_ID=""; EMAIL=""; FIRST=""; LAST=""; PHONE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --workspace)   WORKSPACE="$2"; shift 2 ;;
    --roles)       ROLES="$2"; shift 2 ;;
    --phone)       PHONE="$2"; shift 2 ;;
    --home-branch) HOME_BRANCH="$2"; shift 2 ;;
    --party-id)    PARTY_ID="$2"; shift 2 ;;
    --email)       EMAIL="$2"; shift 2 ;;
    --first-name)  FIRST="$2"; shift 2 ;;
    --last-name)   LAST="$2"; shift 2 ;;
    --*) echo "Unknown option $1" >&2; exit 1 ;;
    *) POSITIONAL+=("$1"); shift ;;
  esac
done
case "${#POSITIONAL[@]}" in
  1) USERNAME="${POSITIONAL[0]}" ;;
  2) [ -z "$WORKSPACE" ] || [ "$WORKSPACE" = "${POSITIONAL[0]}" ] \
       || { echo "Two workspaces given: ${POSITIONAL[0]} and --workspace $WORKSPACE" >&2; exit 1; }
     WORKSPACE="${POSITIONAL[0]}"; USERNAME="${POSITIONAL[1]}" ;;
  *) echo "$USAGE" >&2; exit 1 ;;
esac

case "$WORKSPACE" in
  staff|agent|customer|regulator) ;;
  "") echo "Name the workspace: staff, agent, customer or regulator. $USAGE" >&2; exit 1 ;;
  *) echo "workspace must be staff, agent, customer or regulator, not $WORKSPACE" >&2; exit 1 ;;
esac
if [ "$WORKSPACE" = staff ] && [ -z "$HOME_BRANCH" ]; then
  echo "staff need --home-branch (it goes in the token; without it they cannot post a journal)" >&2; exit 1
fi
if [ "$WORKSPACE" = staff ] && [ -z "$PHONE" ]; then
  echo "staff need --phone (their sign-in code is texted to it; without one they cannot sign in)" >&2; exit 1
fi
if [ -n "$PHONE" ] && ! printf '%s' "$PHONE" | grep -Eq '^\+?[0-9 ]{9,16}$'; then
  echo "--phone takes digits, spaces and a leading +, e.g. +255712345678" >&2; exit 1
fi
if { [ "$WORKSPACE" = agent ] || [ "$WORKSPACE" = customer ]; } && [ -z "$PARTY_ID" ]; then
  echo "${WORKSPACE}s need --party-id -- their party id, copied from their record in the console" >&2; exit 1
fi
if [ "$WORKSPACE" != staff ] && [ -n "$ROLES" ]; then
  echo "only staff hold job roles; leave out --roles for a $WORKSPACE login" >&2; exit 1
fi
[ "$ROLES" = "ALL" ] && ROLES="$ALL_JOB_ROLES"

# The values travel as environment variables, never inside the Python text, so no name or phone can change the code.
# Exactly one line starts RESULT: -- Authentik prints its own start-up chatter around it.
OUT=$(ak_manage \
  LP_USERNAME="$USERNAME" LP_WORKSPACE="$WORKSPACE" LP_TENANT="$PLATFORM_TENANT_ID" \
  LP_ROLES="$ROLES" LP_HOME_BRANCH="$HOME_BRANCH" LP_PARTY_ID="$PARTY_ID" LP_PHONE="$PHONE" \
  LP_EMAIL="$EMAIL" LP_NAME="$(echo "$FIRST $LAST" | xargs)" \
  -- shell -c '
import os, secrets, string
from django.db import transaction
from authentik.core.models import Group, User
from authentik.rbac.models import Role

e = os.environ
username = e["LP_USERNAME"]
if User.objects.filter(username=username).exists():
    print("RESULT:ERROR A login named " + username + " already exists")
    raise SystemExit(0)
if not e.get("LP_TENANT"):
    print("RESULT:ERROR PLATFORM_TENANT_ID is empty in .env")
    raise SystemExit(0)

# reset_password: the sign-in flow makes them choose their own password before anything else (ticket 03).
attributes = {"tenant_id": e["LP_TENANT"], "reset_password": True}
for key, name in (("home_branch", "LP_HOME_BRANCH"), ("party_id", "LP_PARTY_ID"), ("phone", "LP_PHONE")):
    if e.get(name):
        attributes[key] = e[name]
roles = [r.strip() for r in e.get("LP_ROLES", "").split(",") if r.strip()]
if roles:
    attributes["job_roles"] = roles

# Ends in Aa1 so it satisfies the password rules whatever the random part came out as.
alphabet = string.ascii_letters + string.digits
password = "".join(secrets.choice(alphabet) for _ in range(14)) + "Aa1"

with transaction.atomic():
    user = User.objects.create(
        username=username, name=e.get("LP_NAME") or username, email=e.get("LP_EMAIL", ""),
        path="lifeplatform/staff" if e["LP_WORKSPACE"] == "staff" else "lifeplatform/people",
        attributes=attributes)
    user.set_password(password)
    user.save()
    user.groups.add(Group.objects.get(name=e["LP_WORKSPACE"]))
    # The backend may change only the logins it created (Authentik grants it those on creation). This one was made
    # here, so it is given the same rights explicitly -- or Users and roles could never reset or close it.
    Role.objects.get(name="lifeplatform-backend").assign_perms(
        ["authentik_core.view_user", "authentik_core.change_user", "authentik_core.delete_user",
         "authentik_core.reset_user_password"], user)
print("RESULT:OK " + password)
' 2>&1) || { echo "$OUT" >&2; echo "Authentik refused to run the script (is authentik-server running? ./status.sh)" >&2; exit 1; }

RESULT=$(printf '%s\n' "$OUT" | grep '^RESULT:' | tail -1)
case "$RESULT" in
  RESULT:OK\ *)
    echo "Created $WORKSPACE login $USERNAME with job roles [${ROLES:-none}]."
    echo "One-time password (shown once): ${RESULT#RESULT:OK }"
    echo "They must change it at first sign-in." ;;
  RESULT:ERROR\ *) echo "${RESULT#RESULT:ERROR }" >&2; exit 1 ;;
  *) echo "$OUT" >&2; echo "The login was not confirmed: read the output above." >&2; exit 1 ;;
esac
