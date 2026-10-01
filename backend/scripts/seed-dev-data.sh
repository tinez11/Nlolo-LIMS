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

# Product AUTHORING is ADMIN-only and staff.finance cannot do it. ProductController gates both
# createProduct and publishVersion on `hasRole('REALM_STAFF') and hasRole('ADMIN')`, and
# FINANCE_OFFICER is not ADMIN -- so Step 1 below needs its own token.
#
# This script authored products as staff.finance until a fresh bootstrap finally exercised it and
# Step 1 died on a real 403. It had passed once, long ago, BEFORE those endpoints were tightened
# from "any staff member" to ADMIN; after that, the "Already seeded" guard at the top meant nobody
# ever ran Step 1 against the tightened gate again. The e2e suite hit the same wall and fixed it
# separately ("mint product fixtures as admin, since only admin may author one") -- this script was
# missed, because nothing re-runs it.
STAFF_ADMIN_TOKEN=$(token_for staff staff.admin "$STAFF_SECRET")

# Not idempotent on purpose: re-running would create a second set of parties and policies, and
# silently doubling seed data is worse than refusing. Detect and refuse.
# Uses the same " *: *"-tolerant matching as jsonval() above, rather than an exact-substring grep
# -- an exact match on '"productName":"Demo Term Life"' only works because Spring Boot's default
# Jackson output happens to have no space after the colon today. If that ever changed (a
# pretty-printer, a DTO field reorder), the exact match would silently stop detecting prior seed
# state, and the script would run until hitting ux_product_code's unique constraint instead --
# still safe, but a far less informative failure than "Already seeded".
if api "$STAFF_FINANCE_TOKEN" GET "/products" | grep -q '"productName" *: *"Demo Term Life"'; then
  echo "Already seeded (found the demo product). Reset with: docker compose --profile tools down -v" >&2
  exit 1
fi

echo "=== Step 1: Product + version (staff.admin -- authoring is ADMIN-only) ==="
PRODUCT_JSON=$(api "$STAFF_ADMIN_TOKEN" POST "/products" \
  '{"productCode":"DEMO-TERM-01","productName":"Demo Term Life","category":"TERM_LIFE","defaultCurrency":"TZS"}')
PRODUCT_ID=$(jsonval "$PRODUCT_JSON" productId)
echo "productId=$PRODUCT_ID"

# ageFrom/ageTo on the AGE factor are REQUIRED, and must be ABSENT on every other factor type
# (openapi-product.yaml's ratingTable description, enforced by product/V5__rating_table_age_bounds).
# Age used to be resolved by exact string equality on `band`, which underwriting could never
# produce -- so age was not rated at all. This payload omitted them and a fresh bootstrap died on
# a real 422: "AGE rating factor '30-39' needs an age range". `band` stays as the human label.
VERSION_RESP=$(curl -sfi -X POST "$API/products/$PRODUCT_ID/versions" \
  -H "Authorization: Bearer $STAFF_ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuid)" -d '{
    "ifrsMeasurementModel":"PAA","effectiveDate":"2020-01-01",
    "payoutTerms":{"freeLookDays":15},
    "tiraFiling":{"reference":"TIRA/DEMO/0001","approvalDate":"2020-01-01"},
    "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0,"ageFrom":30,"ageTo":39},{"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
    "benefitSchedule":[{"benefitType":"DEATH","calculationMethod":"SUM_ASSURED"}]
  }')
echo "$VERSION_RESP" | head -1
SNAPSHOT_JSON=$(api "$STAFF_FINANCE_TOKEN" GET "/products/$PRODUCT_ID/active-snapshot")
PRODUCT_VERSION_ID=$(jsonval "$SNAPSHOT_JSON" productVersionId)
echo "productVersionId=$PRODUCT_VERSION_ID"

# An ENDOWMENT product carrying a cash-value table, so the savings half of the platform is
# reachable at all.
#
# Without one, nothing a fresh dev stack can produce ever has a cash value: every seeded product
# is term or credit life, which never do. So the policy page's Value panel (surrender, paid-up)
# and the loans panel's origination are permanently inert, and a person clicking through the
# console would reasonably conclude the features do not work. The table is the one from step 1's
# own tests -- 200 per 1,000 at year 2, rising -- and the basis reference says plainly that it is
# a demo figure, not an actuary's.
#
# No policy is seeded on it, for the same reason no credit-life scheme is: issuing one is what
# the form is for.
echo "=== Step 1a: Endowment product with a cash-value table (staff.admin) ==="
END_PRODUCT_JSON=$(api "$STAFF_ADMIN_TOKEN" POST "/products" \
  '{"productCode":"DEMO-END-01","productName":"Demo Endowment","category":"ENDOWMENT","defaultCurrency":"TZS"}')
END_PRODUCT_ID=$(jsonval "$END_PRODUCT_JSON" productId)
echo "endowmentProductId=$END_PRODUCT_ID"

END_VERSION_RESP=$(curl -sfi -X POST "$API/products/$END_PRODUCT_ID/versions" \
  -H "Authorization: Bearer $STAFF_ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuid)" -d '{
    "ifrsMeasurementModel":"GMM","effectiveDate":"2020-01-01",
    "payoutTerms":{"freeLookDays":15},
    "payoutSchedule":[{"kind":"MATURITY","amountBasis":"PERCENT_OF_SA","amountValue":100}],
    "tiraFiling":{"reference":"TIRA/DEMO/END/0001","approvalDate":"2020-01-01"},
    "ratingTable":[{"factorType":"AGE","band":"18-60","multiplier":1.0,"ageFrom":18,"ageTo":60},{"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
    "benefitSchedule":[{"benefitType":"DEATH","calculationMethod":"SUM_ASSURED"},{"benefitType":"MATURITY","calculationMethod":"SUM_ASSURED"}],
    "cashValue":{"basisReference":"DEMO-BASIS-NOT-ACTUARIAL","basisDate":"2020-01-01",
                 "paidUpBasis":"PROPORTIONATE","minYearsForValue":2,
                 "rows":[{"policyYear":2,"cashValuePerMille":200},{"policyYear":3,"cashValuePerMille":300},
                         {"policyYear":5,"cashValuePerMille":450},{"policyYear":10,"cashValuePerMille":700}]}
  }')
echo "$END_VERSION_RESP" | head -1

# A MONEY-BACK endowment: one that pays the customer WHILE THEY ARE ALIVE.
#
# DEMO-END-01 above pays only at maturity, so nothing seeded on a fresh stack ever produces a
# survival benefit -- and the payouts queue, the payout page and the whole review/approve pair are
# therefore permanently empty, which reads as "this does not work" rather than "nothing is due".
# This one pays 10% of the sum assured on the 5th, 10th and 15th anniversaries and the balance at
# maturity, which is how a money-back plan is actually sold (guide §14).
#
# survivalBenefitsDeductedFromDeath is stated explicitly rather than left out: a product with
# SURVIVAL rows MUST say whether what has already been paid alive comes off the death benefit, and
# the server refuses a version that does not. false here -- the plan pays both in full, which is
# the more common Tanzanian shape and the more generous one to demonstrate.
#
# No policy is seeded on it, for the same reason none is on the others: issuing one is what the
# form is for. The e2e suite issues against this product per run.
echo "=== Step 1a2: Money-back endowment, paying while the life assured lives (staff.admin) ==="
MB_PRODUCT_JSON=$(api "$STAFF_ADMIN_TOKEN" POST "/products" \
  '{"productCode":"END-MB-20","productName":"Nlolo Money-Back 20","category":"ENDOWMENT","defaultCurrency":"TZS"}')
MB_PRODUCT_ID=$(jsonval "$MB_PRODUCT_JSON" productId)
echo "moneyBackProductId=$MB_PRODUCT_ID"

MB_VERSION_RESP=$(curl -sfi -X POST "$API/products/$MB_PRODUCT_ID/versions" \
  -H "Authorization: Bearer $STAFF_ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuid)" -d '{
    "ifrsMeasurementModel":"GMM","effectiveDate":"2020-01-01",
    "payoutTerms":{"freeLookDays":15,"survivalBenefitsDeductedFromDeath":false},
    "payoutSchedule":[
      {"kind":"SURVIVAL","fromPolicyYear":5,"toPolicyYear":5,"amountBasis":"PERCENT_OF_SA","amountValue":10,"frequency":"ANNUAL"},
      {"kind":"SURVIVAL","fromPolicyYear":10,"toPolicyYear":10,"amountBasis":"PERCENT_OF_SA","amountValue":10,"frequency":"ANNUAL"},
      {"kind":"SURVIVAL","fromPolicyYear":15,"toPolicyYear":15,"amountBasis":"PERCENT_OF_SA","amountValue":10,"frequency":"ANNUAL"},
      {"kind":"MATURITY","amountBasis":"PERCENT_OF_SA","amountValue":70}
    ],
    "tiraFiling":{"reference":"TIRA/DEMO/MB/0001","approvalDate":"2020-01-01"},
    "ratingTable":[{"factorType":"AGE","band":"18-60","multiplier":1.0,"ageFrom":18,"ageTo":60},{"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
    "benefitSchedule":[{"benefitType":"DEATH","calculationMethod":"SUM_ASSURED"},{"benefitType":"MATURITY","calculationMethod":"SUM_ASSURED"}],
    "cashValue":{"basisReference":"DEMO-BASIS-NOT-ACTUARIAL","basisDate":"2020-01-01",
                 "paidUpBasis":"PROPORTIONATE","minYearsForValue":2,
                 "rows":[{"policyYear":2,"cashValuePerMille":180},{"policyYear":3,"cashValuePerMille":270},
                         {"policyYear":5,"cashValuePerMille":400},{"policyYear":10,"cashValuePerMille":620}]}
  }')
echo "$MB_VERSION_RESP" | head -1

# A CREDIT_LIFE product, and a lender to hold a scheme on it.
#
# Neither existed here, and the consequence was not cosmetic: the console's "Credit-life scheme"
# form filters products to CREDIT_LIFE and parties are picked by search, so on a freshly
# bootstrapped dev environment that form offered an empty product list and no lender to choose --
# a screen that looks broken because the data it needs was never seeded. The e2e suite works
# around the same hole by authoring its own product per run, which is right for a test and no
# help at all to somebody clicking through a fresh stack.
#
# No scheme is seeded on purpose. Setting one up is exactly what the form is for, and seeding one
# would hide whether that form works.
echo "=== Step 1b: Credit-life product + a lender (staff.admin) ==="
CL_PRODUCT_JSON=$(api "$STAFF_ADMIN_TOKEN" POST "/products" \
  '{"productCode":"DEMO-CL-01","productName":"Demo Credit Life","category":"CREDIT_LIFE","defaultCurrency":"TZS"}')
CL_PRODUCT_ID=$(jsonval "$CL_PRODUCT_JSON" productId)
echo "creditLifeProductId=$CL_PRODUCT_ID"

# The age band is deliberately wide. A borrower's entry age is checked against it, a lender's file
# carries whoever the lender lent to, and a narrow demo band would refuse most of a sample file
# for a reason that has nothing to do with what is being demonstrated.
CL_VERSION_RESP=$(curl -sfi -X POST "$API/products/$CL_PRODUCT_ID/versions" \
  -H "Authorization: Bearer $STAFF_ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuid)" -d '{
    "ifrsMeasurementModel":"PAA","effectiveDate":"2020-01-01",
    "tiraFiling":{"reference":"TIRA/DEMO/CL/0001","approvalDate":"2020-01-01"},
    "ratingTable":[{"factorType":"AGE","band":"18-70","multiplier":1.0,"ageFrom":18,"ageTo":70},{"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
    "benefitSchedule":[{"benefitType":"DEATH","calculationMethod":"SUM_ASSURED"}]
  }')
echo "$CL_VERSION_RESP" | head -1

LENDER_JSON=$(api "$STAFF_ADMIN_TOKEN" POST "/parties/corporates" \
  '{"registeredName":"Demo Microfinance","registrationNumber":"REG-DEMO-0001","contactInfo":{"phoneNumber":"+255712000111","email":"ops@demo-microfinance.example.tz"}}')
echo "lenderPartyId=$(jsonval "$LENDER_JSON" partyId)"

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

echo "=== Step 2b: KYC-verify the agent parties and the demo owner (staff.finance) ==="
# Discovered empirically (a real 422), not in the brief: DistributionApiImpl.onboardAgent
# requires the party's KYC status to already be VERIFIED ("Party ... has KYC status PENDING;
# an agent must be VERIFIED to onboard") -- every freshly-registered party starts PENDING, so
# this step is a real precondition, not optional polish.
api "$STAFF_FINANCE_TOKEN" POST "/parties/$SENIOR_AGENT_PARTY_ID/kyc" \
  '{"status":"VERIFIED","evidenceDocumentRef":"seed:kyc-evidence-senior"}' >/dev/null
api "$STAFF_FINANCE_TOKEN" POST "/parties/$JUNIOR_AGENT_PARTY_ID/kyc" \
  '{"status":"VERIFIED","evidenceDocumentRef":"seed:kyc-evidence-junior"}' >/dev/null

# Amina Owner too, and for the same class of reason. Several e2e specs onboard her as a
# fresh agent or name her as a policyholder through the picker labelled "Search for a
# VERIFIED party by name" -- staff-distribution, staff-policy-lifecycle and
# staff-group-schemes all do. She was left PENDING here, so on a database that had only ever
# been seeded once and then accumulated state, she happened to be verified by some earlier
# manual action; on a genuinely fresh seed she is not, and four distribution specs time out
# waiting for a name a VERIFIED-only picker can never show.
#
# Baraka Other is deliberately NOT verified, so the KYC review queue and the two nav badges
# that count it still have something in them to demonstrate.
api "$STAFF_FINANCE_TOKEN" POST "/parties/$OWNER_PARTY_ID/kyc" \
  '{"status":"VERIFIED","evidenceDocumentRef":"seed:kyc-evidence-owner"}' >/dev/null
echo "both agent parties and the demo owner KYC-VERIFIED (Baraka Other stays PENDING on purpose)"

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
# Separation of duties: whoever assesses a case may not decide it. staff.senior writes the
# evidence so that staff.underwriter can record the ordinary, in-line decision below.
STAFF_SENIOR_TOKEN=$(token_for staff staff.senior "$STAFF_SECRET")

# Sum assured 1,500,000 TZS. It resolves to NO sum assured band at all, and that is now a
# deliberate neutral rather than an accident: the seeded SUM_ASSURED_BAND row above carries the
# label "LOW" and no amount bounds, and as of product V9 a band is matched by RANGE against the
# real amount. An unbounded row covers nothing, which contributes the neutral 1.0.
#
# (It contributed 1.0 before V9 too, by a worse route. resolveSumAssuredBand produced one of
# three strings hardcoded in Java -- LOW/MEDIUM/HIGH at two and ten million -- and "LOW" here
# matched by luck of spelling. A real product published with the band "5000000" matched nothing
# and silently priced as though it had no sum assured factor.)
#
# Age resolves by range against the applicant's date of birth (product V5), and the seeded
# 30-39 band carries 1.0. Occupation class is unrated here because this applicant is registered
# without one. So combinedMultiplier = 1.0 x 1.0 x 1.0 = 1.0 (not > 1.0) and a risk score of
# 10 (< 40) => SimpleRulesEngine returns ACCEPT.
#
# This product publishes no base rate table, so issuance prices from the platform's flat
# TZ_BASE_PREMIUM_RATE_PER_MILLE. A version WITH a rate table now prices from its own cells and
# refuses a life the table does not cover -- see UnderwritingDecisionEventListener.
CASE_JSON=$(api "$AGENT_SENIOR_TOKEN" POST "/underwriting/cases" \
  "{\"applicantPartyId\":\"$OWNER_PARTY_ID\",\"productId\":\"$PRODUCT_ID\",\"productVersionId\":\"$PRODUCT_VERSION_ID\",\"sumAssured\":{\"amount\":\"1500000.00\",\"currencyCode\":\"TZS\"}}")
CASE_ID=$(jsonval "$CASE_JSON" caseId)
echo "caseId=$CASE_ID"

ASSESSMENT_JSON=$(api "$STAFF_SENIOR_TOKEN" POST "/underwriting/cases/$CASE_ID/assessments" \
  '{"assessmentType":"MEDICAL","findings":"Standard risk, no adverse findings","riskScore":10}')
RECOMMENDATION=$(jsonval "$ASSESSMENT_JSON" recommendationOutcome)
echo "recommendationOutcome=$RECOMMENDATION"
if [ "$RECOMMENDATION" != "ACCEPT" ]; then
  echo "FATAL: expected a recommendation of ACCEPT, got '$RECOMMENDATION' -- rating table / risk score assumptions are wrong. Full response:" >&2
  echo "$ASSESSMENT_JSON" >&2
  exit 1
fi

# The decision is now a separate, explicit human act. Submitting an assessment records evidence
# and the engine's recommendation and settles nothing -- it used to decide the case outright and
# issue a policy off a placeholder rules engine with no person involved.
#
# staff.underwriter, not staff.senior: this decision AGREES with the recommendation, and a junior
# underwriter may record that. Seeding it as a senior would quietly stop exercising the ordinary
# path, which is the one almost every real decision takes.
DECISION_JSON=$(api "$STAFF_UNDERWRITER_TOKEN" POST "/underwriting/cases/$CASE_ID/decision" \
  '{"outcome":"ACCEPT","reason":"Seed data: standard risk, in line with the recommendation"}')
DECISION_OUTCOME=$(jsonval "$DECISION_JSON" decisionOutcome)
echo "decisionOutcome=$DECISION_OUTCOME"
if [ "$DECISION_OUTCOME" != "ACCEPT" ]; then
  echo "FATAL: expected ACCEPT, got '$DECISION_OUTCOME'. Full response:" >&2
  echo "$DECISION_JSON" >&2
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
