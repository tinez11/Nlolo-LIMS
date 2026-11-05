#!/usr/bin/env bash
# Money movement, one policy per case (money movement step 7, design §12): paid, paid ahead, part paid, withdrawn,
# reversed. Money moves in and out only through deposits and payouts, each confirmed by a second person, so
# books-match.sh has every road to reconcile. Called by seed-dev-data.sh; runs on its own against any stack that has
# its demo product ("Demo Term Life"), adding five new policies each time.
#
# A loan with a surplus needs a cash value, which no policy has on the day it is issued -- the loan specs build that
# case themselves.
set -euo pipefail
cd "$(dirname "$0")/.."
KEYCLOAK="${KEYCLOAK_URL:-http://localhost:8081}"
API="${API_URL:-http://localhost:8080}"
source scripts/seed-lib.sh

STAFF_SECRET=$(realm_secret staff)
FINANCE=$(token_for staff staff.finance "$STAFF_SECRET")
APPROVER=$(token_for staff staff.finance-approver "$STAFF_SECRET")
SENIOR=$(token_for staff staff.senior "$STAFF_SECRET")
UNDERWRITER=$(token_for staff staff.underwriter "$STAFF_SECRET")
AGENT=$(token_for agents agent.senior "$(realm_secret agents)")

if [ -z "${PRODUCT_ID:-}" ]; then
  # On stdin, not as an argument: a long-lived stack's product list is longer than a command line may be.
  PRODUCT_ID=$(api "$FINANCE" GET "/products" | node -e '
    const p = JSON.parse(require("fs").readFileSync(0, "utf8")); const list = Array.isArray(p) ? p : (p.items ?? p.content ?? []);
    const demo = list.find((x) => x.productName === "Demo Term Life"); process.stdout.write(demo ? demo.productId : "");
  ')
  [ -n "$PRODUCT_ID" ] || { echo "FATAL: no \"Demo Term Life\" product -- run seed-dev-data.sh first" >&2; exit 1; }
fi
PRODUCT_VERSION_ID=${PRODUCT_VERSION_ID:-$(jsonval "$(api "$FINANCE" GET "/products/$PRODUCT_ID/active-snapshot")" productVersionId)}

new_case_policy() {   # new_case_policy <full name> <phone>  -> prints the new policy's number
  local party case decision number
  # An agent records the client's sex and an identity document (unique per tenant), or the client cannot be priced or
  # KYC-verified: the number carries this run's stamp so the script can run again on the same stack.
  party=$(jsonval "$(api "$AGENT" POST "/parties/individuals" \
    "{\"fullName\":\"$1\",\"dateOfBirth\":\"1988-06-01\",\"sex\":\"FEMALE\",\"idType\":\"NATIONAL_ID\",\"idNumber\":\"SEED-$RUN-${2: -3}\",\"contactInfo\":{\"phoneNumber\":\"$2\"}}")" partyId)
  [ -n "$party" ] || { echo "FATAL: the client $1 was refused" >&2; exit 1; }
  api "$FINANCE" POST "/parties/$party/kyc" \
    '{"status":"VERIFIED","evidenceDocumentRef":"seed:kyc-evidence-money-case"}' >/dev/null
  case=$(jsonval "$(api "$AGENT" POST "/underwriting/cases" \
    "{\"applicantPartyId\":\"$party\",\"productId\":\"$PRODUCT_ID\",\"productVersionId\":\"$PRODUCT_VERSION_ID\",\"sumAssured\":{\"amount\":\"1500000.00\",\"currencyCode\":\"TZS\"}}")" caseId)
  api "$SENIOR" POST "/underwriting/cases/$case/assessments" \
    '{"assessmentType":"MEDICAL","findings":"Standard risk, no adverse findings","riskScore":10}' >/dev/null
  decision=$(jsonval "$(api "$UNDERWRITER" POST "/underwriting/cases/$case/decision" \
    '{"outcome":"ACCEPT","reason":"Seed data: a money-movement case"}')" decisionOutcome)
  [ "$decision" = "ACCEPT" ] || { echo "FATAL: case $case for $1 was not accepted" >&2; exit 1; }
  for i in $(seq 1 30); do
    number=$(jsonval "$(api "$FINANCE" GET "/policies?policyholderPartyId=$party")" policyNumber)
    [ -n "$number" ] && break
    sleep 1
  done
  [ -n "$number" ] || { echo "FATAL: no policy issued for $1" >&2; exit 1; }
  echo "$number"
}

first_premium() {   # first_premium <policyNumber> -> the first invoice's amount
  local amount
  for i in $(seq 1 20); do
    amount=$(jsonval "$(api "$FINANCE" GET "/policies/$1/invoices")" amount)
    [ -n "$amount" ] && break
    sleep 1
  done
  [ -n "$amount" ] || { echo "FATAL: no invoice raised on $1" >&2; exit 1; }
  echo "$amount"
}

deposit() {   # deposit <policyNumber> <method> <reference> <amount>  -> the confirmed deposit's id
  local id
  id=$(jsonval "$(api "$FINANCE" POST "/receipts" \
    "{\"policyNumber\":\"$1\",\"method\":\"$2\",\"reference\":\"$3\",\"amount\":{\"amount\":\"$4\",\"currencyCode\":\"TZS\"},\"receivedOn\":\"$(date +%F)\",\"payerRef\":\"Seed payer\"}")" receiptId)
  [ -n "$id" ] || { echo "FATAL: the deposit $3 on $1 was refused" >&2; exit 1; }
  api "$APPROVER" POST "/receipts/$id/approval" >/dev/null
  echo "$id"
}

mul() { awk -v a="$1" -v f="$2" 'BEGIN { printf "%.2f", a * f }'; }
RUN=$(date +%s)

echo "=== Money movement, one policy per case ==="
PAID=$(new_case_policy "Neema Paid" "+255713000101")
deposit "$PAID" BANK_DEPOSIT "SEED-PAID-$RUN" "$(first_premium "$PAID")" >/dev/null
echo "paid:       $PAID -- one premium by bank deposit"

AHEAD=$(new_case_policy "Juma Ahead" "+255713000102")
deposit "$AHEAD" CASH "SEED-AHEAD-$RUN" "$(mul "$(first_premium "$AHEAD")" 3)" >/dev/null
echo "paid ahead: $AHEAD -- three premiums in cash; two wait on the balance"

PART=$(new_case_policy "Zawadi Part" "+255713000103")
deposit "$PART" LIPA_NUMBER "SEED-PART-$RUN" "$(mul "$(first_premium "$PART")" 0.5)" >/dev/null
echo "part paid:  $PART -- half a premium by Lipa number"

WITHDRAWN=$(new_case_policy "Baraka Withdrawn" "+255713000104")
PREMIUM=$(first_premium "$WITHDRAWN")
deposit "$WITHDRAWN" BANK_DEPOSIT "SEED-WDR-$RUN" "$(mul "$PREMIUM" 2)" >/dev/null
WITHDRAWAL=$(jsonval "$(api "$FINANCE" POST "/policies/$WITHDRAWN/balance/withdrawals" \
  "{\"amount\":{\"amount\":\"$PREMIUM\",\"currencyCode\":\"TZS\"},\"payee\":{\"method\":\"MOBILE_MONEY\",\"payeeRef\":\"+255713000104\"}}")" withdrawalId)
api "$APPROVER" POST "/balance-withdrawals/$WITHDRAWAL/approve" >/dev/null
echo "withdrawn:  $WITHDRAWN -- two premiums deposited, the second withdrawn to mobile money"

REVERSED=$(new_case_policy "Rehema Reversed" "+255713000105")
CHEQUE=$(deposit "$REVERSED" CHEQUE "SEED-CHQ-$RUN" "$(first_premium "$REVERSED")")
REVERSAL=$(jsonval "$(api "$FINANCE" POST "/deposits/$CHEQUE/reversal" '{"reason":"Seed data: the cheque bounced"}')" reversalId)
api "$APPROVER" POST "/deposit-reversals/$REVERSAL/confirm" >/dev/null
echo "reversed:   $REVERSED -- a cheque deposited, then reversed when it bounced; the premium is owed again"
