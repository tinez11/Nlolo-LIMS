#!/usr/bin/env bash
# BUMACO -- the credit-life scheme, recreated in the dev platform and left EMPTY, ready for the
# lender's enrolment file to be uploaded on screen.
#
# Authored 2026-10-15. It speaks the REAL REST API with a REAL Keycloak token, the way
# seed-dev-data.sh does, so what appears in the console got there the way a person's clicks would.
#
# WHAT THE DOCS ALREADY SETTLED ABOUT THIS LENDER
# (backend/docs/superpowers/specs/2026-09-21-credit-life-design.md)
#
#   Premium basis   FLAT_ON_PRINCIPAL -- the rate ONCE on the disbursed amount, term irrelevant.
#                   A two-month loan costs what a twelve-month one does. This is Bumaco's real
#                   basis, not a mistake in their sheet; LOLC's is different
#                   (ANNUAL_ON_DECLINING_BALANCE). Charging the wrong one was a real defect: one
#                   Bumaco file came out 54,333.33 wrong on 111,000.
#   Cover           Declines STRAIGHT-LINE over the term -- the FLAT_RATE interest method.
#   Free cover      600,000,000 TZS. Their loans reach about 20M, so the capped-cover and
#                   referral path will essentially never fire.
#   Exits           Reported monthly, by file.
#   The borrower    Is never contacted. The insurer deals only with the lender.
#
# THE ONE FIGURE THE DOCS DO NOT PIN
#
#   The rate is "per annum and ADJUSTABLE per lender -- 0.4% for some, 0.5% for others", and
#   Bumaco's own number is never written down. It defaults to 0.5 here and is a parameter, so a
#   corrected figure is one re-run away:
#
#       BUMACO_RATE_PERCENT=0.4 ./scripts/bumaco-credit-life-demo.sh
#
#   Check it before quoting any premium from this scheme as a real number.
#
# THE SCHEME OPENS EMPTY, which credit life allows and an employer scheme does not: the agreement
# with the lender exists before any borrower, and the book arrives by file.
#
# Run it with the dev stack up (infra, backend on 8080, Keycloak on 8081):
#   ./scripts/bumaco-credit-life-demo.sh
set -euo pipefail
cd "$(dirname "$0")/.."

KEYCLOAK="${KEYCLOAK_URL:-http://localhost:8081}"
API="${API_URL:-http://localhost:8080}"
RATE="${BUMACO_RATE_PERCENT:-0.5}"
FCL="${BUMACO_FCL:-600000000.00}"

uuid() {
  if [ -r /proc/sys/kernel/random/uuid ]; then
    cat /proc/sys/kernel/random/uuid
  elif command -v uuidgen >/dev/null 2>&1; then
    uuidgen | tr 'A-Z' 'a-z'
  else
    od -An -N16 -tx1 /dev/urandom | tr -d ' \n' | sed -E 's/(.{8})(.{4})(.{4})(.{4})(.{12})/\1-\2-\3-\4-\5/'
  fi
}

token_for() {
  curl -sf -X POST "$KEYCLOAK/realms/$1/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=lifeplatform-app -d client_secret="$3" \
    -d username="$2" -d password=devpassword \
  | sed 's/.*"access_token":"\([^"]*\)".*/\1/'
}

api() {
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
# Issuing a scheme is gated on UNDERWRITER, not on admin or finance.
STAFF_UNDERWRITER_TOKEN=$(token_for staff staff.underwriter "$STAFF_SECRET")
[ -n "$STAFF_ADMIN_TOKEN" ] || { echo "Could not get a staff.admin token" >&2; exit 1; }

SUFFIX=$(date +%H%M%S)

echo "=== Step 1: Register BUMACO as a corporate party ==="
LENDER_JSON=$(api "$STAFF_ADMIN_TOKEN" POST "/parties/corporates" \
  "{\"registeredName\":\"BUMACO Insurance Partner - Bumaco Ltd\",\"registrationNumber\":\"BUMACO-$SUFFIX\",
    \"contactInfo\":{\"phoneNumber\":\"+255750000001\",\"email\":\"schemes@bumaco.example\"}}")
LENDER_PARTY_ID=$(jsonval "$LENDER_JSON" partyId)
echo "lenderPartyId=$LENDER_PARTY_ID"
[ -n "$LENDER_PARTY_ID" ] || { echo "Lender was not registered: $LENDER_JSON" >&2; exit 1; }

echo "=== Step 2: Author the credit-life product ==="
PRODUCT_JSON=$(api "$STAFF_ADMIN_TOKEN" POST "/products" \
  "{\"productCode\":\"CL-BUMACO-$SUFFIX\",\"productName\":\"Bumaco Credit Life\",\"category\":\"CREDIT_LIFE\",\"portfolioCode\":\"CRL\",\"defaultCurrency\":\"TZS\"}")
PRODUCT_ID=$(jsonval "$PRODUCT_JSON" productId)
echo "productId=$PRODUCT_ID"
[ -n "$PRODUCT_ID" ] || { echo "Product was not created: $PRODUCT_JSON" >&2; exit 1; }

echo "=== Step 3: Publish the version ==="
# PAA: a credit-life book is short-duration. The TIRA class is "Credit Life" and a valid filing
# reference is required before publish, which the platform enforces.
VERSION_RESP=$(curl -sfi -X POST "$API/products/$PRODUCT_ID/versions" \
  -H "Authorization: Bearer $STAFF_ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuid)" -d '{
    "ifrsMeasurementModel":"PAA","effectiveDate":"2020-01-01",
    "tiraFiling":{"reference":"TIRA/DEMO/CREDITLIFE/0001","approvalDate":"2020-01-01"},
    "ratingTable":[{"factorType":"AGE","band":"18-70","multiplier":1.0,"ageFrom":18,"ageTo":70},{"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
    "benefitSchedule":[{"benefitType":"DEATH","calculationMethod":"SUM_ASSURED"}]
  }')
echo "$VERSION_RESP" | head -1

SNAPSHOT_JSON=$(api "$STAFF_FINANCE_TOKEN" GET "/products/$PRODUCT_ID/active-snapshot")
VERSION_ID=$(jsonval "$SNAPSHOT_JSON" productVersionId)
echo "productVersionId=$VERSION_ID"
[ -n "$VERSION_ID" ] || { echo "No active snapshot: $SNAPSHOT_JSON" >&2; exit 1; }

echo "=== Step 4: Issue the scheme, EMPTY ==="
# openingSchedule []        : no borrower yet. The book arrives by file. The service permits an
#                             empty schedule on AMORTISING_LOAN and only there.
# premium 1.00              : a PLACEHOLDER, not a quote. Nothing is owed until a file is
#                             accepted -- a credit-life scheme is paid once per accepted file,
#                             which is why the frequency is SINGLE and never a cycle -- but the
#                             wire layer puts a 0.01 floor on every Money, so an empty scheme
#                             cannot record the zero that would be truthful.
# no issuanceBasis          : omitted on purpose. The five bases are all exceptional
#                             (MIGRATION, CONVERSION, REINSTATEMENT, UNDERWRITING_OVERRIDE,
#                             GUARANTEED_ISSUE); an ordinary new scheme names none, which the
#                             console calls "OFFER" and sends by omission.
# interestMethod FLAT_RATE  : straight-line declining cover -- principal x (n-k)/n, no rate read.
SCHEME_JSON=$(api "$STAFF_UNDERWRITER_TOKEN" POST "/group-schemes" \
  "{\"policyholderPartyId\":\"$LENDER_PARTY_ID\",\"productVersionId\":\"$VERSION_ID\",
    \"benefitBasis\":\"AMORTISING_LOAN\",
    \"fclAmount\":\"$FCL\",\"currency\":\"TZS\",
    \"openingSchedule\":[],
    \"premium\":{\"amount\":\"1.00\",\"currencyCode\":\"TZS\"},
    \"premiumFrequency\":\"SINGLE\",
    \"commencementDate\":\"$(date +%Y-%m-01)\",
    \"reasonForManualIssue\":\"Bumaco scheme set up from agreed terms; the book arrives by file\",
    \"interestMethod\":\"FLAT_RATE\",
    \"repaymentFrequency\":\"MONTHLY\",
    \"premiumRatePercent\":$RATE,
    \"premiumBasis\":\"FLAT_ON_PRINCIPAL\"}")
SCHEME_POLICY=$(jsonval "$SCHEME_JSON" policyNumber)
echo "schemePolicyNumber=$SCHEME_POLICY"
[ -n "$SCHEME_POLICY" ] || { echo "Scheme was not issued: $SCHEME_JSON" >&2; exit 1; }

echo
echo "Bumaco's credit-life scheme is open and empty."
echo "  Lender:   $LENDER_PARTY_ID"
echo "  Product:  $PRODUCT_ID"
echo "  Scheme:   $SCHEME_POLICY"
echo "  Rate:     $RATE% FLAT_ON_PRINCIPAL   (CHECK THIS -- the docs never pin Bumaco's rate)"
echo "  FCL:      TZS $FCL"
echo
echo "Upload the enrolment file on the scheme's page, or from the command line:"
echo "  curl -X POST $API/credit-life-schemes/$SCHEME_POLICY/enrolments \\"
echo "    -H \"Authorization: Bearer \$TOKEN\" -F file=@bumaco-august.xlsx"
echo
echo "Blank template with the exact headers:"
echo "  GET $API/credit-life-schemes/$SCHEME_POLICY/template"
