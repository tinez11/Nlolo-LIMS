#!/usr/bin/env bash
# Ndoto -- the fixed-term deposit, configured in the dev platform and sold to one client.
#
# Authored 2026-10-15 from the product owner's printed rate card ("Kiasi cha Uwekezaji na Faida").
# It speaks the REAL REST API with a REAL Keycloak token, the way seed-dev-data.sh does, so what
# appears in the console got there the way a person's clicks would.
#
# THE RATE CARD, exactly as printed:
#
#   Kiasi Cha Uwekezaji        Miezi 3    Miezi 6    Mwaka 1
#   500,000 - 5,000,000           3%         4%         5%
#   6,000,000 - 10,000,000        4%         5%         6%
#   11,000,000 - 20,000,000       5%         6%         7%
#   21,000,000 na zaidi           6%         7%         8%
#
# ONE THING THE CARD LEAVES OPEN, already decided on 2026-10-02: the printed bands have gaps --
# nothing covers 5,000,001 to 5,999,999. The platform puts an in-between amount in the HIGHER
# band, so each band below starts one cent above the previous band's top. An amount of 5,500,000
# therefore earns the 6,000,000 band's rate, not the 500,000 band's.
#
# Run it with the dev stack up (infra, backend on 8080, Keycloak on 8081):
#   ./scripts/ndoto-demo.sh
#
# Deliberately not idempotent: re-running creates another product and another deposit, for the
# reason seed-dev-data.sh gives -- silently reusing yesterday's data hides a failure to make
# today's.
set -euo pipefail
cd "$(dirname "$0")/.."

KEYCLOAK="${KEYCLOAK_URL:-http://localhost:8081}"
API="${API_URL:-http://localhost:8080}"

# The client: an existing, KYC-verified party in the dev database.
CLIENT_PARTY_ID="${NDOTO_CLIENT_PARTY_ID:-8b077231-ef62-4a5c-8ab0-9343d3d1f307}"
# 6,000,000 for 12 months lands in the SECOND band, so the demo shows a rate the first band does
# not give: 6% a year, not 5%.
DEPOSIT_AMOUNT="${NDOTO_AMOUNT:-6000000.00}"
DEPOSIT_TERM_MONTHS="${NDOTO_TERM:-12}"

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

jsonval() { grep -o "\"$2\" *: *\"[^\"]*\"" <<<"$1" | head -1 | sed -E 's/.*: *"([^"]*)"/\1/'; }

STAFF_SECRET=$(grep -o '"secret" *: *"[^"]*"' keycloak/staff-realm.json | head -1 | cut -d'"' -f4)
STAFF_ADMIN_TOKEN=$(token_for staff staff.admin "$STAFF_SECRET")
STAFF_FINANCE_TOKEN=$(token_for staff staff.finance "$STAFF_SECRET")
STAFF_SENIOR_TOKEN=$(token_for staff staff.senior "$STAFF_SECRET")
STAFF_UNDERWRITER_TOKEN=$(token_for staff staff.underwriter "$STAFF_SECRET")
[ -n "$STAFF_ADMIN_TOKEN" ] || { echo "Could not get a staff.admin token" >&2; exit 1; }

SUFFIX=$(date +%H%M%S)

echo "=== Step 1: Author the Ndoto product ==="
# Portfolio DEP: a fixed-term deposit is its own portfolio, not the SAV a flexible account uses.
PRODUCT_JSON=$(api "$STAFF_ADMIN_TOKEN" POST "/products" \
  "{\"productCode\":\"NDOTO-$SUFFIX\",\"productName\":\"Ndoto Fixed Deposit\",\"category\":\"ENDOWMENT\",\"portfolioCode\":\"DEP\",\"defaultCurrency\":\"TZS\"}")
PRODUCT_ID=$(jsonval "$PRODUCT_JSON" productId)
echo "ndotoProductId=$PRODUCT_ID"
[ -n "$PRODUCT_ID" ] || { echo "Product was not created: $PRODUCT_JSON" >&2; exit 1; }

echo "=== Step 2: Publish the version carrying the rate card ==="
# Twelve rows: four amount bands x three terms. minAmount is the band FLOOR; each band past the
# first starts one cent above the one below it, which is what closes the printed card's gaps.
# No accumulation block: the server builds the account behind a deposit itself, and a version
# carrying both is refused. The payout schedule is empty for the same reason -- a deposit pays at
# maturity from its own term, not from a payout row.
VERSION_RESP=$(curl -sfi -X POST "$API/products/$PRODUCT_ID/versions" \
  -H "Authorization: Bearer $STAFF_ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuid)" -d '{
    "ifrsMeasurementModel":"GMM","effectiveDate":"2020-01-01",
    "payoutTerms":{"freeLookDays":15},
    "payoutSchedule":[],
    "tiraFiling":{"reference":"TIRA/DEMO/NDOTO/0001","approvalDate":"2020-01-01"},
    "ratingTable":[{"factorType":"AGE","band":"18-60","multiplier":1.0,"ageFrom":18,"ageTo":60},{"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
    "benefitSchedule":[{"benefitType":"DEATH","calculationMethod":"SUM_ASSURED"}],
    "eligibility":{"minEntryAge":18,"maxEntryAge":60},
    "deposit":{"rates":[
      {"minAmount":"500000","termMonths":3,"ratePercent":3},
      {"minAmount":"500000","termMonths":6,"ratePercent":4},
      {"minAmount":"500000","termMonths":12,"ratePercent":5},
      {"minAmount":"5000000.01","termMonths":3,"ratePercent":4},
      {"minAmount":"5000000.01","termMonths":6,"ratePercent":5},
      {"minAmount":"5000000.01","termMonths":12,"ratePercent":6},
      {"minAmount":"10000000.01","termMonths":3,"ratePercent":5},
      {"minAmount":"10000000.01","termMonths":6,"ratePercent":6},
      {"minAmount":"10000000.01","termMonths":12,"ratePercent":7},
      {"minAmount":"20000000.01","termMonths":3,"ratePercent":6},
      {"minAmount":"20000000.01","termMonths":6,"ratePercent":7},
      {"minAmount":"20000000.01","termMonths":12,"ratePercent":8}
    ]}
  }')
echo "$VERSION_RESP" | head -1

SNAPSHOT_JSON=$(api "$STAFF_FINANCE_TOKEN" GET "/products/$PRODUCT_ID/active-snapshot")
VERSION_ID=$(jsonval "$SNAPSHOT_JSON" productVersionId)
echo "ndotoProductVersionId=$VERSION_ID"
[ -n "$VERSION_ID" ] || { echo "No active snapshot: $SNAPSHOT_JSON" >&2; exit 1; }

echo "=== Step 3: Open an underwriting case for the client ==="
# A deposit is a SINGLE premium equal to the amount deposited, over the chosen term.
CASE_JSON=$(api "$STAFF_SENIOR_TOKEN" POST "/underwriting/cases" \
  "{\"applicantPartyId\":\"$CLIENT_PARTY_ID\",\"productId\":\"$PRODUCT_ID\",\"productVersionId\":\"$VERSION_ID\",
    \"sumAssured\":{\"amount\":\"$DEPOSIT_AMOUNT\",\"currencyCode\":\"TZS\"},
    \"requestedTermMonths\":$DEPOSIT_TERM_MONTHS,\"premiumFrequency\":\"SINGLE\"}")
CASE_ID=$(jsonval "$CASE_JSON" caseId)
echo "caseId=$CASE_ID"
[ -n "$CASE_ID" ] || { echo "Case was not opened: $CASE_JSON" >&2; exit 1; }

echo "=== Step 4: Assess and decide ==="
ASSESSMENT_JSON=$(api "$STAFF_SENIOR_TOKEN" POST "/underwriting/cases/$CASE_ID/assessments" \
  '{"assessmentType":"MEDICAL","findings":"Standard risk, no adverse findings","riskScore":10}')
echo "recommendationOutcome=$(jsonval "$ASSESSMENT_JSON" recommendationOutcome)"

DECISION_JSON=$(api "$STAFF_UNDERWRITER_TOKEN" POST "/underwriting/cases/$CASE_ID/decision" \
  '{"outcome":"ACCEPT","reason":"Ndoto demo: standard risk, in line with the recommendation"}')
echo "decisionOutcome=$(jsonval "$DECISION_JSON" decisionOutcome)"

echo "=== Step 5: Wait for the policy (auto-issued by an AFTER_COMMIT listener) ==="
POLICY_NUMBER=""
for i in $(seq 1 30); do
  SEARCH_JSON=$(api "$STAFF_FINANCE_TOKEN" GET "/policies?policyholderPartyId=$CLIENT_PARTY_ID")
  CANDIDATE=$(jsonval "$SEARCH_JSON" policyNumber)
  if [ -n "$CANDIDATE" ]; then POLICY_NUMBER="$CANDIDATE"; break; fi
  sleep 1
done
echo "policyNumber=$POLICY_NUMBER"
[ -n "$POLICY_NUMBER" ] || { echo "No policy appeared within 30s" >&2; exit 1; }

echo "=== Step 6: Pay the deposit in ==="
INVOICES_JSON=$(api "$STAFF_FINANCE_TOKEN" GET "/policies/$POLICY_NUMBER/invoices")
INVOICE_ID=$(jsonval "$INVOICES_JSON" invoiceId)
echo "invoiceId=$INVOICE_ID"
if [ -n "$INVOICE_ID" ]; then
  curl -sf -X POST "$API/invoices/$INVOICE_ID/payment-request" \
    -H "Authorization: Bearer $STAFF_FINANCE_TOKEN" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $(uuid)" -d '{"payerRef":"+255747577461"}' >/dev/null
  echo "payment-request accepted; the deposit opens once the rail confirms"
else
  echo "No invoice yet -- collect the deposit from the policy screen." >&2
fi

echo
echo "Ndoto is configured and one deposit is sold."
echo "  Product:  $PRODUCT_ID"
echo "  Version:  $VERSION_ID"
echo "  Policy:   $POLICY_NUMBER"
echo "  Deposit:  TZS $DEPOSIT_AMOUNT over $DEPOSIT_TERM_MONTHS months"
echo "  Expected: 6% a year -- the 6,000,000-10,000,000 band, Mwaka 1 column"
