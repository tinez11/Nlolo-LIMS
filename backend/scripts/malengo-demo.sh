#!/usr/bin/env bash
# Malengo -- the savings plan, configured in the dev platform so it can be shown on screen.
#
# Authored 2026-10-15 for a product demo. It speaks the REAL REST API with a REAL Keycloak token,
# the same way seed-dev-data.sh does, so what appears in the console went in the way a person's
# clicks would -- not by writing rows behind the application's back.
#
# THE PLAN
#   4% a year guaranteed, credited at each month end.
#   Deposit whenever you like (a regular contribution, plus ad-hoc top-ups).
#   Withdraw whenever you like, down to and including the whole balance: minimum balance is ZERO,
#   which is the one setting that makes Malengo different from the Akiba plan beside it.
#   No fixed sum assured -- the contract carries the opening deposit as its face amount, because
#   the wire layer refuses a zero one, and the maturity payout is ACCOUNT_VALUE, not a multiple of
#   that number. The same convention the fixed-term deposit already uses.
#   Entry ages 18 to 60.
#
# Run it with the dev stack up (infra, backend on 8080, Keycloak on 8081):
#   ./scripts/malengo-demo.sh
#
# Re-running creates a SECOND product: it is deliberately not idempotent, for the reason
# seed-dev-data.sh gives -- a demo script that silently reuses yesterday's data hides a failure to
# create today's.
set -euo pipefail
cd "$(dirname "$0")/.."

KEYCLOAK="${KEYCLOAK_URL:-http://localhost:8081}"
API="${API_URL:-http://localhost:8080}"

# The client this demo is for: an existing, KYC-verified party in the dev database.
CLIENT_PARTY_ID="${MALENGO_CLIENT_PARTY_ID:-8b077231-ef62-4a5c-8ab0-9343d3d1f307}"

uuid() {
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

jsonval() {     # jsonval <json> <field> -- first match, single level.
  grep -o "\"$2\" *: *\"[^\"]*\"" <<<"$1" | head -1 | sed -E 's/.*: *"([^"]*)"/\1/'
}

STAFF_SECRET=$(grep -o '"secret" *: *"[^"]*"' keycloak/staff-realm.json | head -1 | cut -d'"' -f4)
STAFF_ADMIN_TOKEN=$(token_for staff staff.admin "$STAFF_SECRET")
STAFF_FINANCE_TOKEN=$(token_for staff staff.finance "$STAFF_SECRET")
[ -n "$STAFF_ADMIN_TOKEN" ] || { echo "Could not get a staff.admin token" >&2; exit 1; }

SUFFIX=$(date +%H%M%S)

echo "=== Step 1: Author the Malengo product ==="
PRODUCT_JSON=$(api "$STAFF_ADMIN_TOKEN" POST "/products" \
  "{\"productCode\":\"MALENGO-$SUFFIX\",\"productName\":\"Malengo Savings Plan\",\"category\":\"ENDOWMENT\",\"portfolioCode\":\"SAV\",\"defaultCurrency\":\"TZS\"}")
PRODUCT_ID=$(jsonval "$PRODUCT_JSON" productId)
echo "malengoProductId=$PRODUCT_ID"
[ -n "$PRODUCT_ID" ] || { echo "Product was not created: $PRODUCT_JSON" >&2; exit 1; }

echo "=== Step 2: Publish the version -- this is the configurable part ==="
# guaranteedRatePercent 4  : the 4% a year, the floor a declared rate may never credit below.
# minimumBalance 0         : what lets a client withdraw the entire balance.
# charges                  : nothing held back from a deposit, 1,000 a month policy fee. Small on
#                            purpose for the demo; a real basis is an actuary's call.
# ratingTable AGE 18-60    : entry ages, enforced at underwriting.
# payoutSchedule           : ACCOUNT_VALUE -- the client is owed the account, not a face amount.
VERSION_RESP=$(curl -sfi -X POST "$API/products/$PRODUCT_ID/versions" \
  -H "Authorization: Bearer $STAFF_ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuid)" -d '{
    "ifrsMeasurementModel":"GMM","effectiveDate":"2020-01-01",
    "payoutTerms":{"freeLookDays":15},
    "payoutSchedule":[{"kind":"MATURITY","amountBasis":"ACCOUNT_VALUE","amountValue":100}],
    "tiraFiling":{"reference":"TIRA/DEMO/MALENGO/0001","approvalDate":"2020-01-01"},
    "ratingTable":[{"factorType":"AGE","band":"18-60","multiplier":1.0,"ageFrom":18,"ageTo":60},{"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
    "benefitSchedule":[{"benefitType":"DEATH","calculationMethod":"SUM_ASSURED"}],
    "eligibility":{"minEntryAge":18,"maxEntryAge":60},
    "accumulation":{"guaranteedRatePercent":4,"minimumBalance":0,
                    "charges":[{"fromPolicyYear":1,"contributionAllocationPercent":0,"transferAllocationPercent":0,"monthlyPolicyFee":1000}]}
  }')
echo "$VERSION_RESP" | head -1

SNAPSHOT_JSON=$(api "$STAFF_FINANCE_TOKEN" GET "/products/$PRODUCT_ID/active-snapshot")
VERSION_ID=$(jsonval "$SNAPSHOT_JSON" productVersionId)
echo "malengoProductVersionId=$VERSION_ID"
[ -n "$VERSION_ID" ] || { echo "No active snapshot: $SNAPSHOT_JSON" >&2; exit 1; }

echo
echo "Malengo is configured and live for new business."
echo "  Product:  $PRODUCT_ID"
echo "  Version:  $VERSION_ID"
echo "  Client:   $CLIENT_PARTY_ID"
echo
echo "Open the console at /staff/products to see it, and its version page for the"
echo "4% rate, the zero minimum balance, the 18-60 entry ages and the monthly fee."
