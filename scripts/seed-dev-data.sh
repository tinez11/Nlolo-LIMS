#!/usr/bin/env bash
# Seeds a local stack with coherent demo data by driving the REAL HTTP API -- never SQL inserts.
#
# Why HTTP: this platform is event-driven end to end. One accepted underwriting decision cascades
# into policy issuance, a billing schedule and invoices, a commission accrual, a reinsurance
# cession, GL journal entries and four reporting projections. Inserting a policy row directly
# produces none of that, and the resulting "policy with no invoices" looks like a frontend bug.
#
# It is also the only artefact on this platform that proves a REAL Keycloak-issued token works
# against real endpoints -- every test fabricates its own JWT, so a broken claim mapper is
# invisible to the whole suite. If this script 403s at its first call, the mappers are wrong.
set -euo pipefail
cd "$(dirname "$0")/.."

KEYCLOAK="${KEYCLOAK_URL:-http://localhost:8081}"
API="${API_URL:-http://localhost:8080}"
DEV_TENANT="11111111-1111-1111-1111-111111111111"

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

STAFF_SECRET=$(grep -o '"secret" *: *"[^"]*"' keycloak/staff-realm.json | head -1 | cut -d'"' -f4)
CUSTOMERS_SECRET=$(grep -o '"secret" *: *"[^"]*"' keycloak/customers-realm.json | head -1 | cut -d'"' -f4)
AGENTS_SECRET=$(grep -o '"secret" *: *"[^"]*"' keycloak/agents-realm.json | head -1 | cut -d'"' -f4)

STAFF_FINANCE_TOKEN=$(token_for staff staff.finance "$STAFF_SECRET")
# Minted early, deliberately: party_id is NOT required to CALL /parties/individuals (only to
# later scope reads of the resulting party), so an agents-realm token obtained before Task 8's
# party_id write-back is already fully usable here. party.infrastructure.PartyController gates
# POST /parties/individuals on `hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS')` -- NOT
# REALM_STAFF -- discovered empirically (a real 403) rather than by re-reading the brief, which
# assumed staff.finance could do this.
AGENT_SENIOR_TOKEN=$(token_for agents agent.senior "$AGENTS_SECRET")

# Not idempotent on purpose: re-running would create a second set of parties and policies, and
# silently doubling seed data is worse than refusing. Detect and refuse.
if api "$STAFF_FINANCE_TOKEN" GET "/products" | grep -q '"productName":"Demo Term Life"'; then
  echo "Already seeded (found the demo product). Reset with: docker compose --profile tools down -v" >&2
  exit 1
fi

echo "=== Step 1: Product + version (staff.finance) ==="
PRODUCT_JSON=$(api "$STAFF_FINANCE_TOKEN" POST "/products" \
  '{"productCode":"DEMO-TERM-01","productName":"Demo Term Life","category":"TERM_LIFE","defaultCurrency":"TZS"}')
PRODUCT_ID=$(jsonval "$PRODUCT_JSON" productId)
echo "productId=$PRODUCT_ID"

VERSION_RESP=$(curl -sfi -X POST "$API/products/$PRODUCT_ID/versions" \
  -H "Authorization: Bearer $STAFF_FINANCE_TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuid)" -d '{
    "ifrsMeasurementModel":"PAA","effectiveDate":"2020-01-01",
    "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0},{"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
    "benefitSchedule":[{"benefitType":"DEATH","calculationMethod":"SUM_ASSURED"}]
  }')
echo "$VERSION_RESP" | head -1
SNAPSHOT_JSON=$(api "$STAFF_FINANCE_TOKEN" GET "/products/$PRODUCT_ID/active-snapshot")
PRODUCT_VERSION_ID=$(jsonval "$SNAPSHOT_JSON" productVersionId)
echo "productVersionId=$PRODUCT_VERSION_ID"

echo "=== Step 2: Parties (agent.senior -- see the role-gate note above STAFF_FINANCE_TOKEN's mint) ==="
OWNER_JSON=$(api "$AGENT_SENIOR_TOKEN" POST "/parties/individuals" \
  '{"fullName":"Amina Owner","dateOfBirth":"1990-04-12","contactInfo":{"phoneNumber":"+255712345678","email":"customer.owner@example.tz"}}')
OWNER_PARTY_ID=$(jsonval "$OWNER_JSON" partyId)
echo "ownerPartyId=$OWNER_PARTY_ID"

OTHER_JSON=$(api "$AGENT_SENIOR_TOKEN" POST "/parties/individuals" \
  '{"fullName":"Baraka Other","dateOfBirth":"1988-09-01","contactInfo":{"phoneNumber":"+255712345679","email":"customer.other@example.tz"}}')
OTHER_PARTY_ID=$(jsonval "$OTHER_JSON" partyId)
echo "otherPartyId=$OTHER_PARTY_ID"

SENIOR_AGENT_PARTY_JSON=$(api "$AGENT_SENIOR_TOKEN" POST "/parties/individuals" \
  '{"fullName":"Juma Senior","dateOfBirth":"1975-01-10","contactInfo":{"phoneNumber":"+255712345680","email":"agent.senior@example.tz"}}')
SENIOR_AGENT_PARTY_ID=$(jsonval "$SENIOR_AGENT_PARTY_JSON" partyId)
echo "seniorAgentPartyId=$SENIOR_AGENT_PARTY_ID"

JUNIOR_AGENT_PARTY_JSON=$(api "$AGENT_SENIOR_TOKEN" POST "/parties/individuals" \
  '{"fullName":"Neema Junior","dateOfBirth":"1995-06-20","contactInfo":{"phoneNumber":"+255712345681","email":"agent.junior@example.tz"}}')
JUNIOR_AGENT_PARTY_ID=$(jsonval "$JUNIOR_AGENT_PARTY_JSON" partyId)
echo "juniorAgentPartyId=$JUNIOR_AGENT_PARTY_ID"

echo "=== Step 2b: KYC-verify the two agent parties (staff.finance) ==="
# Discovered empirically (a real 422), not in the brief: DistributionApiImpl.onboardAgent
# requires the party's KYC status to already be VERIFIED ("Party ... has KYC status PENDING;
# an agent must be VERIFIED to onboard") -- every freshly-registered party starts PENDING, so
# this step is a real precondition, not optional polish.
api "$STAFF_FINANCE_TOKEN" POST "/parties/$SENIOR_AGENT_PARTY_ID/kyc" \
  '{"status":"VERIFIED","evidenceDocumentRef":"seed:kyc-evidence-senior"}' >/dev/null
api "$STAFF_FINANCE_TOKEN" POST "/parties/$JUNIOR_AGENT_PARTY_ID/kyc" \
  '{"status":"VERIFIED","evidenceDocumentRef":"seed:kyc-evidence-junior"}' >/dev/null
echo "both agent parties KYC-VERIFIED"

echo "=== Step 3: Agents (staff.finance, FINANCE_OFFICER-gated) ==="
SENIOR_AGENT_JSON=$(api "$STAFF_FINANCE_TOKEN" POST "/agents" \
  "{\"partyId\":\"$SENIOR_AGENT_PARTY_ID\",\"licenseNumber\":\"LIC-SENIOR-001\",\"licenseExpiryDate\":\"2030-01-01\"}")
SENIOR_AGENT_ID=$(jsonval "$SENIOR_AGENT_JSON" agentId)
echo "seniorAgentId=$SENIOR_AGENT_ID"

JUNIOR_AGENT_JSON=$(api "$STAFF_FINANCE_TOKEN" POST "/agents" \
  "{\"partyId\":\"$JUNIOR_AGENT_PARTY_ID\",\"licenseNumber\":\"LIC-JUNIOR-001\",\"licenseExpiryDate\":\"2030-01-01\",\"hierarchyParentId\":\"$SENIOR_AGENT_ID\"}")
JUNIOR_AGENT_ID=$(jsonval "$JUNIOR_AGENT_JSON" agentId)
echo "juniorAgentId=$JUNIOR_AGENT_ID"

echo "=== Step 4: Write party_id back into Keycloak (closes Task 8's open loop) ==="
ADMIN_TOKEN=$(curl -sf -X POST "$KEYCLOAK/realms/master/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=admin-cli -d username=admin -d password=devadmin \
  | sed 's/.*"access_token":"\([^"]*\)".*/\1/')

write_party_id() {   # write_party_id <realm> <username> <partyId>
  local realm="$1" username="$2" partyId="$3"
  local user_id user_json
  user_json=$(curl -sf "$KEYCLOAK/admin/realms/$realm/users?username=$username&exact=true" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
  # The search endpoint returns a JSON ARRAY ([{...}]); the PUT endpoint below needs the single
  # OBJECT inside it, not the array -- stripping the outer brackets is safe because &exact=true
  # guarantees exactly one element.
  user_json="${user_json#\[}"
  user_json="${user_json%\]}"
  user_id=$(jsonval "$user_json" id)
  # Discovered empirically, not in the brief: a PUT carrying ONLY {"attributes": {...}} silently
  # nulls out every OTHER UserRepresentation field the JSON body omits (email, firstName,
  # lastName all vanished from a follow-up GET) -- and a user missing those fields then fails
  # the password grant entirely with "invalid_grant: Account is not fully set up" (Keycloak's
  # declarative User Profile validation kicking in at authentication time), even though
  # `enabled`/`emailVerified`/`requiredActions` all still read as healthy. Fixed by GET-modify-
  # PUT on the FULL representation -- splicing the new attributes object into the JSON Keycloak
  # itself just returned -- rather than constructing a minimal body from scratch. attributes is a
  # flat map of string arrays (no nested braces), so a bounded `{[^}]*}` replacement is safe.
  local new_attrs="\"attributes\":{\"tenant_id\":[\"$DEV_TENANT\"],\"party_id\":[\"$partyId\"]}"
  local updated_json
  if echo "$user_json" | grep -q '"attributes"'; then
    updated_json=$(echo "$user_json" | sed -E "s/\"attributes\":\{[^}]*\}/${new_attrs//\//\\/}/")
  else
    updated_json=$(echo "$user_json" | sed -E "s/^\{/{${new_attrs//\//\\/},/")
  fi
  curl -sf -X PUT "$KEYCLOAK/admin/realms/$realm/users/$user_id" \
    -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
    -d "$updated_json"
  echo "wrote party_id=$partyId onto $realm/$username (userId=$user_id)"
}

write_party_id customers customer.owner "$OWNER_PARTY_ID"
write_party_id customers customer.other "$OTHER_PARTY_ID"
write_party_id agents agent.senior "$SENIOR_AGENT_PARTY_ID"
write_party_id agents agent.junior "$JUNIOR_AGENT_PARTY_ID"

# AGENT_SENIOR_TOKEN was minted before party_id existed at all (see its mint site above) and is
# reused as-is below purely for its REALM_AGENTS role -- none of its remaining uses need the
# party_id claim. CUSTOMER_OWNER_TOKEN, by contrast, genuinely needs the claim written just above
# (claims/loans enforce customer-owns-this-resource against it), so it MUST be minted fresh here,
# after the write-back, not reused from any earlier point.
CUSTOMER_OWNER_TOKEN=$(token_for customers customer.owner "$CUSTOMERS_SECRET")

echo "=== Step 5: Underwriting -> auto-issue ==="
STAFF_UNDERWRITER_TOKEN=$(token_for staff staff.underwriter "$STAFF_SECRET")

# Sum assured 1,500,000 TZS -> resolveSumAssuredBand's LOW band (<2,000,000), matching the
# SUM_ASSURED_BAND=LOW/multiplier=1.0 rating row above. Age band always resolves to the
# "UNKNOWN" sentinel (PartyView exposes no dateOfBirth yet -- see
# UnderwritingApiImpl.resolveAgeBand's own javadoc), which falls back to a neutral 1.0
# multiplier regardless of the rating table. combinedMultiplier = 1.0 x 1.0 = 1.0 (not > 1.0)
# and a risk score of 10 (< 40) => SimpleRulesEngine returns ACCEPT.
CASE_JSON=$(api "$AGENT_SENIOR_TOKEN" POST "/underwriting/cases" \
  "{\"applicantPartyId\":\"$OWNER_PARTY_ID\",\"productId\":\"$PRODUCT_ID\",\"productVersionId\":\"$PRODUCT_VERSION_ID\",\"sumAssured\":{\"amount\":\"1500000.00\",\"currencyCode\":\"TZS\"}}")
CASE_ID=$(jsonval "$CASE_JSON" caseId)
echo "caseId=$CASE_ID"

ASSESSMENT_JSON=$(api "$STAFF_UNDERWRITER_TOKEN" POST "/underwriting/cases/$CASE_ID/assessments" \
  '{"assessmentType":"MEDICAL","findings":"Standard risk, no adverse findings","riskScore":10}')
DECISION_OUTCOME=$(jsonval "$ASSESSMENT_JSON" decisionOutcome)
echo "decisionOutcome=$DECISION_OUTCOME"
if [ "$DECISION_OUTCOME" != "ACCEPT" ]; then
  echo "FATAL: expected ACCEPT, got '$DECISION_OUTCOME' -- rating table / risk score assumptions are wrong. Full response:" >&2
  echo "$ASSESSMENT_JSON" >&2
  exit 1
fi

echo "Polling GET /policies for the auto-issued policy (arrives via an AFTER_COMMIT listener, not synchronously)..."
POLICY_NUMBER=""
for i in $(seq 1 30); do
  SEARCH_JSON=$(api "$STAFF_FINANCE_TOKEN" GET "/policies?policyholderPartyId=$OWNER_PARTY_ID")
  POLICY_NUMBER=$(jsonval "$SEARCH_JSON" policyNumber)
  if [ -n "$POLICY_NUMBER" ]; then
    break
  fi
  sleep 1
done
if [ -z "$POLICY_NUMBER" ]; then
  echo "FATAL: no policy appeared for party $OWNER_PARTY_ID after 30s -- auto-issuance listener did not fire or failed." >&2
  exit 1
fi
echo "policyNumber=$POLICY_NUMBER"

echo "=== Step 6: Policy loan (attempted while the policy is still ACTIVE) ==="
# Deliberately BEFORE the claim below, not after, as an earlier draft had it (matching the
# brief's own step order literally). A DEATH claim's settlement-decision closes the policy
# one-way (policy.PolicyClaimClosureTest / the recent "one-way policy closure (I5, I6)" commit)
# -- attempting the loan afterward always 409s with LOAN_NOT_ELIGIBLE ("must be in force"),
# which is real but masks the more fundamental gap this step actually exists to surface, so the
# loan is tried here, against the still-in-force policy, instead.
LOAN_STATUS="SKIPPED"
LOAN_ID=""
set +e
LOAN_RAW=$(curl -s -w '\n%{http_code}' -X POST "$API/policies/$POLICY_NUMBER/loans" \
  -H "Authorization: Bearer $CUSTOMER_OWNER_TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuid)" -d '{"requestedAmount":{"amount":"1.00","currencyCode":"TZS"},"payeeRef":"+255712345678"}')
set -e
LOAN_HTTP=$(echo "$LOAN_RAW" | tail -1)
LOAN_RESP=$(echo "$LOAN_RAW" | sed '$d')
if [ "$LOAN_HTTP" = "202" ]; then
  LOAN_ID=$(jsonval "$LOAN_RESP" loanId)
  LOAN_STATUS="ORIGINATED (loanId=$LOAN_ID)"
  echo "loanId=$LOAN_ID"
else
  LOAN_STATUS="FAILED (HTTP $LOAN_HTTP): $LOAN_RESP"
  echo "Policy loan origination did NOT succeed -- HTTP $LOAN_HTTP" >&2
  echo "$LOAN_RESP" >&2
  echo "KNOWN PLATFORM GAP, not a seeder bug: policy.domain.PolicyAccount.cashValueAmount is" >&2
  echo "hardcoded to ZERO at issuance (PolicyApiImpl.issuePolicy) and NO production code path" >&2
  echo "anywhere in this codebase ever credits it above zero (verified by search -- only test" >&2
  echo "code writes a non-zero cash value, directly via the repository). availableLoanValue()" >&2
  echo "is therefore always 0 for a freshly-issued policy, and reserveLoanValue's own guard" >&2
  echo "(amount.compareTo(available) > 0) rejects ANY positive loan request with a real 409" >&2
  echo "InsufficientLoanValueException. This is expected, real behaviour of the current" >&2
  echo "codebase, not a defect in this script -- continuing rather than aborting, so the rest" >&2
  echo "of the seed (which fully succeeded) is not lost." >&2
fi

echo "=== Step 7: Collect a premium ==="
INVOICES_JSON=$(api "$STAFF_FINANCE_TOKEN" GET "/policies/$POLICY_NUMBER/invoices")
INVOICE_ID=$(jsonval "$INVOICES_JSON" invoiceId)
echo "invoiceId=$INVOICE_ID"

curl -sf -X POST "$API/invoices/$INVOICE_ID/payment-request" \
  -H "Authorization: Bearer $STAFF_FINANCE_TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuid)" -d '{"payerRef":"+255712345678"}' >/dev/null
echo "payment-request accepted for invoiceId=$INVOICE_ID (WireMock confirms synchronously; billing.PremiumCollected follows)"

echo "=== Step 8: Claim, with evidence ==="
CLAIM_JSON=$(curl -sf -X POST "$API/claims" \
  -H "Authorization: Bearer $CUSTOMER_OWNER_TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuid)" -d "{
    \"policyNumber\":\"$POLICY_NUMBER\",\"claimantPartyId\":\"$OWNER_PARTY_ID\",\"claimType\":\"DEATH\",
    \"dateOfEvent\":\"2026-08-10\",
    \"details\":{\"claimType\":\"DEATH\",\"causeOfDeath\":\"Natural causes\",\"placeOfDeath\":\"Dar es Salaam\",\"dateOfDeath\":\"2026-08-10\",\"attendingPhysician\":\"Dr. Juma\"}
  }")
CLAIM_ID=$(jsonval "$CLAIM_JSON" claimId)
echo "claimId=$CLAIM_ID"

EVIDENCE_JPEG="$(dirname "$0")/seed-evidence.jpg"
# Minimal genuine JPEG (1x1 pixel), base64-decoded, so Task 3's download endpoint has real bytes,
# a real image/jpeg content type and a real filename to serve -- not a text file wearing a .jpg name.
base64 -d > "$EVIDENCE_JPEG" <<'JPEGBASE64'
/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0a
HBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIy
MjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAABAAEDASIA
AhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAj/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFQEB
AQAAAAAAAAAAAAAAAAAAAAX/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIRAxEAPwCdABmX
/9k=
JPEGBASE64

EVIDENCE_JSON=$(curl -sf -X POST "$API/claims/$CLAIM_ID/evidence" \
  -H "Authorization: Bearer $CUSTOMER_OWNER_TOKEN" \
  -F "file=@$EVIDENCE_JPEG;type=image/jpeg;filename=death-certificate.jpg" \
  -F "description=Death certificate scan")
DOCUMENT_REF=$(jsonval "$EVIDENCE_JSON" documentRef)
echo "documentRef=$DOCUMENT_REF"

STAFF_ASSESSOR_TOKEN=$(token_for staff staff.assessor "$STAFF_SECRET")
STAFF_MANAGER_TOKEN=$(token_for staff staff.manager "$STAFF_SECRET")

ASSESSMENT2_JSON=$(api "$STAFF_ASSESSOR_TOKEN" POST "/claims/$CLAIM_ID/assessments" \
  '{"findings":"Documentation in order, recommend approval","recommendedAmount":{"amount":"1500000.00","currencyCode":"TZS"},"fraudIndicator":false}')
echo "claim assessment submitted: $(jsonval "$ASSESSMENT2_JSON" claimAssessmentId)"

curl -sf -X POST "$API/claims/$CLAIM_ID/settlement-decision" \
  -H "Authorization: Bearer $STAFF_MANAGER_TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuid)" -d '{"approved":true,"approvedAmount":{"amount":"1500000.00","currencyCode":"TZS"},"payeeRef":"+255712345678"}' >/dev/null
echo "settlement-decision approved for claimId=$CLAIM_ID (assessor=staff.assessor, manager=staff.manager -- separation of duties satisfied)"

echo ""
echo "================================================================"
echo " Seed complete"
echo "================================================================"
echo " productId:          $PRODUCT_ID"
echo " productVersionId:   $PRODUCT_VERSION_ID"
echo " ownerPartyId:       $OWNER_PARTY_ID"
echo " otherPartyId:       $OTHER_PARTY_ID"
echo " seniorAgentId:      $SENIOR_AGENT_ID"
echo " juniorAgentId:      $JUNIOR_AGENT_ID"
echo " caseId:             $CASE_ID"
echo " policyNumber:       $POLICY_NUMBER"
echo " invoiceId:          $INVOICE_ID"
echo " claimId:            $CLAIM_ID"
echo " documentRef:        $DOCUMENT_REF"
echo " policyLoan:         $LOAN_STATUS"
echo "================================================================"
echo "Paste policyNumber/claimId/documentRef above into pgAdmin or a browser to explore."
