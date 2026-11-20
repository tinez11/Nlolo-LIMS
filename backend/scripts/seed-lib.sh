# Shared by the seed scripts (seed-dev-data.sh, seed-money-cases.sh, books-match.sh and the demo scripts): sourced,
# never run. Each script sets API before calling these; AUTHENTIK defaults to the dev stack's. Moved out of
# seed-dev-data.sh (money movement step 7); from Keycloak to Authentik in ticket 09 of .scratch/authentik-cutover.

AUTHENTIK="${AUTHENTIK_URL:-http://localhost:9100}"
# The dev stack's bootstrap token (backend/infra/docker-compose.yml), an Authentik administrator. Only set_party_id uses it.
AUTHENTIK_ADMIN_TOKEN="${AUTHENTIK_BOOTSTRAP_TOKEN:-dev-only-authentik-bootstrap-token}"

uuid() {
  # macOS/Linux both have python3 or /proc/sys/kernel/random/uuid; Git-Bash on Windows has
  # neither reliably, so fall back to a portable pure-bash UUIDv4 if both are unavailable.
  if [ -r /proc/sys/kernel/random/uuid ]; then
    cat /proc/sys/kernel/random/uuid
  elif command -v uuidgen >/dev/null 2>&1; then
    uuidgen | tr 'A-Z' 'a-z'
  else
    od -An -N16 -tx1 /dev/urandom | tr -d ' \n' | sed -E 's/(.{8})(.{4})(.{4})(.{4})(.{12})/\1-\2-\3-\4-\5/'
  fi
}

token_for() {   # token_for <username>  -- a real access token for a dev test user
  # Authentik's password grant takes an app-password token, not the login's password. The dev blueprint
  # (deploy/authentik/blueprints/dev/dev-users.yaml) gives each test user one, keyed dev-app-password-<username>, and
  # allows the grant on the console's client in dev only. Fails loudly: an empty token would turn every later call
  # into a 401 that looks like the API's fault.
  local body
  body=$(curl -sf -X POST "$AUTHENTIK/application/o/token/" \
    -d grant_type=password -d client_id=lifeplatform-spa -d username="$1" -d password="dev-app-password-$1" \
    --data-urlencode "scope=openid profile lifeplatform") \
    || { echo "Authentik refused a token for $1 (is the dev blueprint applied?)" >&2; return 1; }
  sed 's/.*"access_token": *"\([^"]*\)".*/\1/' <<<"$body"
}

api() {         # api <token> <method> <path> [json-body]
  local token="$1" method="$2" path="$3" body="${4:-}"
  if [ -n "$body" ]; then
    curl -sf -X "$method" "$API$path" -H "Authorization: Bearer $token" \
      -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuid)" -d "$body"
  else
    curl -sf -X "$method" "$API$path" -H "Authorization: Bearer $token"
  fi
}

jsonval() {     # jsonval <json> <field>  -- crude, dependency-free single-level string/uuid extractor.
  # Deliberately the FIRST match, not sed's greedy-.*-picks-the-last-one behaviour: /policies/
  # {policyNumber}/invoices returns an array (a MONTHLY schedule can generate several invoices
  # upfront) and the brief calls for the first one specifically.
  grep -o "\"$2\" *: *\"[^\"]*\"" <<<"$1" | head -1 | sed -E 's/.*: *"([^"]*)"/\1/'
}

set_party_id() {   # set_party_id <username> <partyId>  -- the login's party_id, which its tokens then carry
  # Authentik replaces a user's attributes whole on PATCH, so this reads them, adds party_id, and writes them all back.
  local user
  user=$(curl -sf "$AUTHENTIK/api/v3/core/users/?username=$1" -H "Authorization: Bearer $AUTHENTIK_ADMIN_TOKEN") \
    || { echo "Authentik refused the user lookup for $1" >&2; return 1; }
  node -e '
    const [username, partyId, base, token] = process.argv.slice(1);
    const user = JSON.parse(require("fs").readFileSync(0, "utf8")).results.find((u) => u.username === username);
    if (!user) { console.error("Authentik has no user " + username); process.exit(1); }
    fetch(base + "/api/v3/core/users/" + user.pk + "/", {
      method: "PATCH",
      headers: { authorization: "Bearer " + token, "content-type": "application/json" },
      body: JSON.stringify({ attributes: { ...user.attributes, party_id: partyId } }),
    }).then(async (r) => {
      if (!r.ok) { console.error("PATCH " + username + " -> " + r.status + " " + (await r.text())); process.exit(1); }
    });
  ' "$1" "$2" "$AUTHENTIK" "$AUTHENTIK_ADMIN_TOKEN" <<<"$user" || return 1
  echo "wrote party_id=$2 onto $1"
}
