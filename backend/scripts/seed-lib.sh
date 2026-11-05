# Shared by the seed scripts (seed-dev-data.sh, seed-money-cases.sh, books-match.sh): sourced, never run. Each script
# sets KEYCLOAK and API before calling these. Moved out of seed-dev-data.sh unchanged (money movement step 7).

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

token_for() {   # token_for <realm> <username> <client-secret>
  curl -sf -X POST "$KEYCLOAK/realms/$1/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=lifeplatform-app -d client_secret="$3" \
    -d username="$2" -d password=devpassword \
  | sed 's/.*"access_token":"\([^"]*\)".*/\1/'
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

realm_secret() {   # realm_secret <staff|customers|agents> -- lifeplatform-app's secret, the client token_for uses
  # By client id, not "the first secret in the file": the agents realm gained lifeplatform-agents-admin ahead of
  # lifeplatform-app, and the first-match version quietly handed every agent token request the wrong secret.
  node -e '
    const realm = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8"));
    const app = (realm.clients || []).find((c) => c.clientId === "lifeplatform-app");
    if (!app || !app.secret) { console.error("no lifeplatform-app secret in " + process.argv[1]); process.exit(1); }
    process.stdout.write(app.secret);
  ' "keycloak/$1-realm.json"
}
