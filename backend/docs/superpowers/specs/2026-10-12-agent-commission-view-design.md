# Agent commission view — design

Date: 2026-10-12. Status: approved in brainstorming, not yet planned.

## Goal

One "commission earned" view that shows exactly what one agent has earned, from which client and product,
when it accrued and where it stands on the way to being paid. Three viewers use it:

- an agent, for themselves;
- a supervisor agent, for themselves or one agent in their downline;
- staff, for any agent, from the existing agent detail page.

Today commission sits in "My profile" as a bare list of monthly statements whose lines show only a tier and a
policy number.

## Decisions taken in brainstorming

| # | Question | Decision |
|---|---|---|
| D1 | Where a line's client name comes from | Distribution stores the policyholder's party id on its own `policy_projection` (new migration V7, backfilled) and resolves the name through `party::api`. Rejected: a frontend join through `GET /policies/{n}` + `GET /parties/{id}`, because an agents-realm token may read only parties it registered itself, so supervisors and every credit-life/group-scheme line would show raw UUIDs. |
| D2 | Headline totals | Derived in the frontend from `GET /agents/{id}/commission-statements` (no period). No summary endpoint. |
| D3 | `PAYOUT_FAILED` | Counts as **awaiting payout** — the money is still owed. |
| D4 | "Paid out" | All time, not this year. |
| D5 | Totals presentation | `Field` rows in a `Panel`, not `StatCards`: `StatCards` takes a `number`, and widening it would touch `components/`, outside this work's scope. |

## Backend

### 1. `GET /agents/me/team` (agents realm only)

- `@PreAuthorize("hasRole('REALM_AGENTS')")`. The caller is the token's `party_id`, never client input; a token
  without one is 403, a party that is not an agent is 404 (same as `/agents/me`).
- Rows come from `DistributionApi.resolveAgentTeam(partyId)`: the caller's own profile(s) plus every agent below
  them within the existing hierarchy depth cap. An agent with no downline gets a list of one.
- Response: a bare array of `AgentTeamMember { agentId, licenseNumber, licenseStatus, hierarchyParentId,
  displayName }`. `displayName` comes from `party::api` `getParty(partyId).displayName()`; null if the party
  cannot be resolved. Ordered: the caller's own profile(s) first, then by display name, then licence number.
- `GET /agents` stays staff-only and unchanged. No access rule changes anywhere else: reading a team member's
  statements and lines still passes through `enforceAgentReadAccess`, whose ancestry walk is what keeps a
  downline agent out of a supervisor's figures, whatever the UI shows.

### 2. Enriched accrual lines

- **Migration `db-migrations/distribution/V7__projection_policyholder.sql`:** adds nullable
  `policyholder_party_id uuid` to `distribution.policy_projection`, then backfills it from `policy.policy`
  on `(tenant_id, policy_number)`. The backfill sits in a `DO` block guarded by
  `to_regclass('policy.policy') IS NOT NULL`, because two test classes
  (`CommissionCloseSweepPsqlTest`, `DistributionApiIntegrationTest`) load distribution's migrations without
  policy's schema. `migrate.sh` applies `policy` before `distribution`, so real databases are backfilled.
  Every test class listing `distribution/V6` (13 today) adds V7.
- `PolicyProjection` maps the column; `PolicyEventListener.handlePolicyActivated` stores
  `policyholderPartyId` from the `PolicyActivated` payload, which already carries it. No change to accrual
  rules, amounts or payout logic.
- `CommissionAccrualView` and `CommissionAccrualResponseDto` gain, additively:
  - `clientPartyId`, `clientName` — from the projection's party id via `party::api`; null when there is no
    projection row or no party id;
  - `productId`, `productName` — from the projection's `productId` via `product::api`; null when there is no
    projection row;
  - `accruedOn` — `created_at` as a civil date in `Africa/Dar_es_Salaam`.
- Lookups are de-duplicated per call: one party read per distinct party, one product read per distinct
  product, one projection query for the call's policy numbers. A credit-life or group-scheme line names the
  scheme's holder (the lender or association).
- `GET /commission-accruals?policyNumber=` shares the DTO and so carries the same fields.
- The IDOR guard in `listAccruals` (statement must belong to the path's agent, else 404) is untouched.

### 3. Contract

`api/openapi/openapi-distribution.yaml` gains the `/agents/me/team` path, an `AgentTeamMember` schema and the
five accrual fields, all `nullable` where null is possible and none with a `default:` (openapi-typescript would
make it required). `frontend/src/types/api/distribution.ts` is regenerated, never hand-edited.

### 4. Backend tests

- `/agents/me/team`: supervisor sees self + downline; downline agent sees only self; staff token 403;
  token without `party_id` 403.
- Enriched lines: client name, product name and `accruedOn` on a normal line; a clawback line; a line whose
  policy has no projection row (nulls, no error); a line created at 01:30 Dar es Salaam time (22:30 UTC the day
  before) is dated on the Dar day, not the UTC one.
- Access: supervisor reads a downline agent's statements and lines (200); downline agent reads supervisor's
  (403); statement paired with the wrong agent (404).
- Listener: `PolicyActivated` stores the policyholder party id.
- Run the affected classes only, after `./mvnw clean test-compile`. Dev backend stopped first.

## Frontend

### Shared component: `AgentCommission({ agentId, canManage })`

In `features/distribution/`. Replaces `CommissionStatementsPanel`, which is deleted. A stack of three panels:

1. **Totals** — `Field` rows: Earned this month, Earned this year, Paid out, Awaiting payout. A note: "Net of
   clawbacks. Paid when finance releases a closed month." (No payout schedule exists; payouts are a staff action.)
2. **Statements** — `DataTable`, no pager: Month, Status (`StatusBadge kind="commissionStatement"`), Total
   (right-aligned). The first cell is a real `<button>` selecting the month; the selected row takes
   `aria-current`. The current Dar es Salaam month is always listed; if it has no statement its status and
   total read "—".
3. **Lines for the selected month** — title "October 2026 — 12 lines". `DataTable`: Client, Product, Policy,
   Type, Accrued, Amount (right-aligned). Product and Accrued are secondary columns, hidden below `sm`. Policy
   links to `/agents/policies/:n` or `/staff/policies/:n` by realm. A clawback (`reversesAccrualId` set) is
   labelled "Clawback (first year)" etc. and its negative amount takes danger ink. A month with no statement or
   no lines shows the empty state "No commission earned in October 2026 yet." — never an error.
   When `canManage`, the existing `PayoutForm` (unchanged `ConfirmAct`, idempotency and receipt) sits at the
   foot of this panel for the selected statement.

Default selection: the current month. Lines load only for the selected statement, through the existing
accruals endpoint.

### Pure functions (unit-tested)

- `commissionTotals(statements, today)` → the four figures, per currency; never mixes currencies. Month/year
  from `Intl` with `timeZone: 'Africa/Dar_es_Salaam'`.
- `accrualTypeLabel(line)` → First year / Renewal / Override / Supervisor override / Clawback (<tier>).
- `statementMonths(statements, today)` → the month list including the always-present current month.

### Agent screen: "My commission"

- Registered in `AGENTS_SCREENS`, path `commission`, menu group `my-business`, label "My commission".
- `PageHeader` "My commission"; description: the viewed agent's name and licence number.
- Loads `/agents/me` and `/agents/me/team`. If the team has more than one member, the header actions carry an
  **Agent** select: "Me" first, then the downline by name and licence. The choice lives in `?agent=<id>` so a
  reload keeps it; an id not in the team falls back to self. One-member teams show no picker.

### Staff `AgentDetailPage`

The "Commission statements" panel becomes `AgentCommission` with today's `canManage`
(FINANCE_OFFICER or ADMIN). Nothing else on the page changes.

### My profile

The statements panel is removed and replaced by a short panel linking to "My commission". The commission-plan
panel stays.

### Frontend tests

- Vitest: the three pure functions — Dar month/year boundary, `PAYOUT_FAILED` in awaiting, negative statement
  totals, mixed currencies, every type label, current month present with and without a statement.
- Typecheck, lint, vitest pass.
- Playwright, new: `agents-my-commission.spec.ts` (agent.senior's own view), `agents-commission-team-picker.spec.ts`
  (agent.senior picks agent.junior, already seeded as its downline), `staff-agent-commission.spec.ts` (staff view,
  payout still requestable). Existing specs matching the changed pages' accessible names are updated and re-run:
  `agents-my-profile`, `staff-distribution`, `staff-payouts`, `staff-credit-life-commission`, and any other
  found referencing "Commission statements" / "View accruals".

## Out of scope

Payout logic, commission calculation and accrual rules, Keycloak realm files, the login theme, other modules'
internals, `StatCards` or any shared component, package/lock files.

## Real-stack verification

The dev backend applies neither migrations nor new endpoints by itself. Before real-stack e2e the user runs the V7
SQL through psql and restarts the backend; the exact commands are handed over at that point, not run silently.
