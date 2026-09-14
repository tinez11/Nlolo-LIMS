#!/usr/bin/env bash
# DEVELOPMENT FIXTURE. Never run this against staging or production.
#
# WHY THIS EXISTS. billing.field_receipt is empty on every environment, and unlike the arrears
# queue that is not an aging problem -- nothing can create a receipt through any UI. Capture is
# POST /agents/{agentId}/field-receipts, gated REALM_AGENTS, and:
#   * no agent screen offers it (the agents realm mounts five screens, none for receipts),
#   * no frontend client calls it (api/billing.ts has only the GET), and
#   * the seeder creates none.
# So the staff reconciliation queue is a read surface over an entity the platform cannot
# originate, and the FieldReceiptReconciliationOverdue alert can never have anything behind it.
#
# WHAT THIS DOES. Captures real receipts through the REAL endpoint with a REAL agent token --
# not SQL inserts. The only thing done in SQL is moving captured_at_server backwards on two of
# them, because the 24h reconciliation SLA runs from that column and no API can back-date it.
# The escalation to RECONCILIATION_OVERDUE is then left to the real billing.sweep_billing_state().
# Same principle as scripts/dev-arrears-fixtures.sql: move the clock, never the logic.
#
# Re-runnable. Capture dedups on (tenant_id, client_idempotency_key) and the keys below are
# stable, so a second run returns the same receipts rather than making four more.
#
# Usage: scripts/dev-field-receipt-fixtures.sh    (from the backend/ directory, stack running)
set -euo pipefail

API="${API:-http://localhost:8080}"
KEYCLOAK="${KEYCLOAK:-http://localhost:8081}"
PSQL=(docker compose -f infra/docker-compose.yml exec -T postgres psql -U postgres -d lifeplatform -tAc)

AGENTS_SECRET=$(grep -o '"secret" *: *"[^"]*"' keycloak/agents-realm.json | head -1 | cut -d'"' -f4)

token_for() {   # token_for <realm> <username> <client-secret>
  curl -sf -X POST "$KEYCLOAK/realms/$1/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=lifeplatform-app -d client_secret="$3" \
    -d username="$2" -d password=devpassword \
  | sed 's/.*"access_token":"\([^"]*\)".*/\1/'
}

echo "=== Step 1: agent token and agent id ==="
AGENT_TOKEN=$(token_for agents agent.senior "$AGENTS_SECRET")
[ -n "$AGENT_TOKEN" ] || { echo "could not mint an agents token -- is the stack up and seeded?" >&2; exit 1; }

# /agents/me is the only way an agent discovers its own agentId: the token carries party_id,
# never an agentId, and the GET /agents list is staff-only.
AGENT_ID=$(curl -sf "$API/agents/me" -H "Authorization: Bearer $AGENT_TOKEN" \
  | grep -o '"agentId" *: *"[^"]*"' | head -1 | cut -d'"' -f4)
[ -n "$AGENT_ID" ] || { echo "could not resolve agent.senior's agentId from /agents/me" >&2; exit 1; }
echo "agent.senior = $AGENT_ID"

echo "=== Step 2: four ACTIVE policies to collect against ==="
mapfile -t POLICIES < <("${PSQL[@]}" \
  "select policy_number from policy.policy where status = 'ACTIVE' order by policy_number limit 4;" | tr -d '\r')
[ "${#POLICIES[@]}" -eq 4 ] || { echo "expected 4 ACTIVE policies, found ${#POLICIES[@]}" >&2; exit 1; }
printf '  %s\n' "${POLICIES[@]}"

echo "=== Step 3: capture four receipts through the real endpoint ==="
capture() {     # capture <policyNumber> <amount> <idempotency-suffix> <capturedAt>
  curl -sf -X POST "$API/agents/$AGENT_ID/field-receipts" \
    -H "Authorization: Bearer $AGENT_TOKEN" -H 'Content-Type: application/json' \
    -d "{\"policyNumber\":\"$1\",\"amount\":{\"amount\":\"$2\",\"currencyCode\":\"TZS\"},\"clientIdempotencyKey\":\"dev-fixture-receipt-$3\",\"capturedAt\":\"$4\"}"
  echo
}

# capturedAt is the AGENT's own clock, deliberately earlier than the server's for the two aged
# ones -- that gap is what the "Collected" column exists to show against "Reached us".
capture "${POLICIES[0]}"  "50000.00" 1 "$(date -u -d '2 hours ago'  +%Y-%m-%dT%H:%M:%SZ)"
capture "${POLICIES[1]}" "125000.00" 2 "$(date -u -d '3 hours ago'  +%Y-%m-%dT%H:%M:%SZ)"
capture "${POLICIES[2]}"  "80000.00" 3 "$(date -u -d '3 days ago'   +%Y-%m-%dT%H:%M:%SZ)"
capture "${POLICIES[3]}" "240000.00" 4 "$(date -u -d '5 days ago'   +%Y-%m-%dT%H:%M:%SZ)"

echo "=== Step 4: age two of them past the 24h SLA, then let the REAL sweep escalate ==="
# captured_at_server is set by the server on capture and no API can back-date it, so this is the
# one thing that has to be SQL. The status transition itself is still the sweep's decision.
"${PSQL[@]}" "update billing.field_receipt
                 set captured_at_server = now() - interval '3 days'
               where client_idempotency_key = 'dev-fixture-receipt-3';" >/dev/null
"${PSQL[@]}" "update billing.field_receipt
                 set captured_at_server = now() - interval '5 days'
               where client_idempotency_key = 'dev-fixture-receipt-4';" >/dev/null
"${PSQL[@]}" "select billing.sweep_billing_state();" >/dev/null

echo "=== Result: read it back rather than trusting the writes ==="
"${PSQL[@]}" "select status || ' x' || count(*) from billing.field_receipt group by status order by 1;"
