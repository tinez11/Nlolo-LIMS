# Console redesign, plan 5 of 5 — lane D: clients, underwriting, schemes, and closing the console

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the console. The remaining nav groups — clients, new business, distribution, products, communications — get what the first four lanes established, the party record gets the tabs the original brief agreed and never received, and a design guard makes the three defect classes impossible to reintroduce.

**Architecture:** No new components. This lane consumes everything plans 1–4 built. Its one structural change is `RecordTabs` on the party record, which is not a new idea — it was in the proposal you approved on 2026-09-25, alongside the policy record that got them in plan 2.

**Tech Stack:** unchanged from plan 1.

## Prerequisite

Plan 4 is merged (`b8774db`), plus its correction (`e9e530e`). Branch `console-redesign-5-clients` from `main`.

## Why this lane is bigger than "the leftovers"

A mechanical inventory of `src/features`, run rather than recalled, at the point plan 4 merged:

| directory | files | label-swapping buttons | raw `role="alert"` | hand-rolled back link |
|---|---|---|---|---|
| **policies** | 12 | 6 | 15 | 5 |
| distribution | 4 | 4 | 3 | 2 |
| underwriting | 4 | 4 | 2 | 2 |
| party | 3 | 2 | 3 | 1 |
| products | 3 | 2 | 4 | 2 |
| communications | 1 | 0 | 1 | 0 |
| **total** | **27** | **18** | **28** | **12** |

**`policies` is the worst directory on the platform, and plan 2 declared it done.** `PolicyDetailPage` alone still carries two label-swapping buttons and three raw alerts. Plan 4 then made the same mistake in miniature and shipped a lane commit saying "that clears the finance lane" while `RegulatoryReturnsPage` still had both defects — the sweep had grepped a directory list assembled from memory, which included `distribution` (another lane's) and omitted `regreporting` (its own).

So this lane closes the console rather than adding to it, and task 8 makes the closure hold.

**Two corrections to that table, both found by checking rather than trusting the grep:**

1. **28 is really 26.** `BeneficiariesPanel`'s two matches are in a comment explaining why `role="alert"` is right there — and it already uses `InlineError`, which renders `role="alert"` itself. Do not "fix" it.
2. **Not every raw alert is convertible.** `InlineError` takes an `ApiError`. `PublishVersionForm`'s four render `rowMessage` — per-row form validation, not a request failure — and `FreeCoverLimitEditor`'s is the same shape. Those stay as they are. **Convert only blocks that render an `error.detail ?? error.title` pair off a store resource.**

## Global Constraints

Plan 1's Global Constraints bind. The ones this lane touches most:

- **No backend change, no OpenAPI change.**
- **Preserve every role gate.** This lane touches the most heavily gated screens on the platform — agent lifecycle, underwriting decisions, KYC. `staff-role-gates`, `staff-agent-lifecycle` and `staff-distribution` assert what an identity *cannot* see.
- **Separation of duties is a real rule, not a UI preference.** `UnderwritingCaseDetailPage` withholds the decision form from whoever opened or assessed the case (`5571ca6`). Nothing here may soften that.
- **e2e locates by accessible name.** Roughly 40 tests across `staff-clients`, `staff-party-kyc`, `staff-underwriting`, `staff-underwriting-queue`, `staff-distribution`, `staff-agent-lifecycle`, `staff-products`, `staff-group-schemes`, `staff-credit-life-*`, `staff-communications`, and both `agents-*` specs.
- **Run Playwright in batches, not one full pass.** A single run reached 3h18m and spent most of it re-authenticating, because the Keycloak cookie idles out at ~30 minutes. Batch by feature area; each batch authenticates fresh. See `feedback-e2e-budget-failures-look-like-regressions`.
- **Never run vitest while Playwright is running**, and never edit `frontend/src` mid-run.

## File map

| File | Lines | Task |
|---|---|---|
| `features/party/PartyDetailPage.tsx` | **1181** | 1, 2, 4, 5 |
| `features/policies/GroupSchemePage.tsx` | 818 | 3, 4, 5, 6 |
| `features/policies/CreditLifeSchemePage.tsx` | 706 | 3, 6 |
| `features/products/ProductDetailPage.tsx` | 650 | 3 |
| `features/underwriting/UnderwritingCaseDetailPage.tsx` | 480 | 3, 4, 5 |
| `features/products/PublishVersionForm.tsx` | — | 4 only (see caveat 2) |
| the other 21 files in the table above | — | 3, 4, 5 |
| `test/designGuards.test.ts` | 110 | 8 |

---

### Task 1: Render tests for the party record, before it is restructured

**Files:**
- Create: `frontend/src/features/party/PartyDetailPage.test.tsx`

Plan 4 proved the order: write the tests, then move the code, and let the tests' silence be the evidence the move changed nothing. `PartyDetailPage` is 1181 lines and task 2 rebuilds its structure, so this is where that discipline pays most.

- [ ] **Step 1: Read the store slice and the role gates first**

`src/store/partyStore.ts` for the real resource shapes, and the file's own `staffRoles` usage for which panels are gated. Invent no fixture field — plan 4's first attempt did, and typecheck caught `REVENUE` for `INCOME` only because the `as` cast was removed.

- [ ] **Step 2: Cover what the restructure could silently break**

At minimum: the record renders with a party in the store; each gated panel appears for an identity holding its role and not otherwise; the KYC status renders from the store rather than a derived guess.

- [ ] **Step 3: Verify non-vacuously**

Break one assertion deliberately, watch it fail, restore. **Confirm the break actually landed** — plan 3's first attempt at this matched nothing because the JSX was multi-line, and passed while proving nothing.

- [ ] **Step 4: Commit**

---

### Task 2: The party record becomes tabs

**Files:**
- Modify: `frontend/src/features/party/PartyDetailPage.tsx`
- Create: `frontend/src/features/party/partySections.tsx` if the tab array grows past a handful of gates

**This was agreed in the original proposal and never built.** The brief settled on "tabs for policy and party, the section bar for claims". The policy record got tabs in plan 2; the claim record got the bar in plan 3; the party record got neither and is now, at 1181 lines, the largest screen on the platform.

- [ ] **Step 1: Confirm tabs are still right for this record, then build them**

The test is `RecordTabs`' own doc: tabs are for registers that are separate jobs done by separate people, where nobody needs two at once. A party's policies, claims, KYC history, relationships and agent-of-record book are exactly that. If reading the file shows two panels that must be seen together, say so and keep those two on one tab rather than splitting them.

- [ ] **Step 2: Build the tab array the way `claimSections` does**

One array of `{ value, label, content }`, gates applied while building it, so a tab can never be offered for a panel the page did not render. Plan 3's comment on `claimSections` gives the reasoning; do not keep a separate list beside a stack of conditionals.

- [ ] **Step 3: Watch for the regression tabs caused in plan 2**

Inactive tabs are unmounted, so **anything that fetched on mount now fetches on tab open.** Plan 2 shipped a rail that counted invoices from a store slot only the invoices panel filled, and the count silently vanished. Grep the rail and the header for any figure derived from a panel's data.

- [ ] **Step 4: Update the specs**

Plan 2 needed ten spec updates for the policy record's tabs and found five on the first pass. Budget for that here: `staff-clients`, `staff-party-kyc`, `staff-distribution` and `staff-group-schemes` all drill into a party.

- [ ] **Step 5: Verify and commit**

---

### Task 3: Twelve breadcrumbs

**Files:** the 12 files carrying `ArrowLeft`

Every one renders a ghost `Button` with a back arrow in a `px-6 pt-6` strip above the page bar. Every one already has a `PageHeader` and no `breadcrumb`.

- [ ] **Step 1: Convert each**

Labels come from the nav: `Individuals`, `Corporate/Group`, `Underwriting`, `Agents`, `Products`, `Policies`.

- [ ] **Step 2: Do the error branch too**

**This is the part three lanes have now missed.** Each record page renders a second branch when the record fails to load, and that branch keeps the old back link — so a record that fails to load appears with no heading, no page bar and the superseded affordance. Plan 4 fixed five of these; the same shape is in every file here. Give the error branch a `PageHeader` with the same breadcrumb.

- [ ] **Step 3: Delete the helper and the import**

Confirm `BackLink` and `ArrowLeft` are both gone from each file. A surviving `ArrowLeft` import means a caller was missed.

- [ ] **Step 4: Grep the specs before committing**

```
cd frontend && grep -rn "All policies\|All clients\|All agents\|All products\|All cases" e2e/
```

- [ ] **Step 5: Verify and commit**

---

### Task 4: Eighteen buttons keep their names

**Files:** the 16 files in the inventory with a non-zero renaming count

- [ ] **Step 1: Convert each to `pending`**

`disabled={x.status === 'loading'}` plus a ternary label becomes `pending={x.status === 'loading'}` with a fixed label.

- [ ] **Step 2: Watch for a label that is not a tense**

Plan 4 found one: `Retire`/`Restore` looked like a label swap and was not — the label is decided by the *account*, and one expression was carrying both "which act is on offer" and "is a request running". Those must come apart, not collapse. Read each ternary before replacing it.

- [ ] **Step 3: Grep for the gerunds first**

```
cd frontend && grep -rn "ing…'" e2e/ src/
```

- [ ] **Step 4: Verify and commit**

---

### Task 5: The convertible error blocks

**Files:** the files with a raw `role="alert"` rendering an `ApiError`

- [ ] **Step 1: Convert only what `InlineError` can take**

It takes an `ApiError`. A block rendering `error.detail ?? error.title` off a store resource converts. A block rendering a form's own validation string does not — leave `PublishVersionForm`'s four `rowMessage` alerts and `FreeCoverLimitEditor`'s alone, and say so in the commit so the next reader does not "finish the job".

- [ ] **Step 2: Leave `BeneficiariesPanel` entirely**

Its matches are a comment defending a decision, and it already uses `InlineError`.

- [ ] **Step 3: Verify and commit**

---

### Task 6: The two scheme records

**Files:**
- Modify: `frontend/src/features/policies/GroupSchemePage.tsx` (818)
- Modify: `frontend/src/features/policies/CreditLifeSchemePage.tsx` (706)

Both are large records in the `new-business` group that no lane has structured.

- [ ] **Step 1: Read them before deciding a shape**

`CreditLifeSchemePage` already carries a stat row, and `Panel`'s doc says a page with one takes no `emphasis` — it would be a third size above body and break the Two-Peaks Rule. That constraint decides more than a preference here.

- [ ] **Step 2: Choose tabs, a section bar, or neither, and justify it in the commit**

The member roll is the register these pages exist for. If everything else is facts about the scheme, this is a `DetailLayout` with a rail and no navigation — the shape plan 4 gave the treaty. **Do not add a bar for symmetry**; plan 4's commit says why, and the reasoning applies unchanged.

- [ ] **Step 3: Verify and commit**

---

### Task 7: The last screens join the axe sweep

**Files:** `frontend/e2e/staff-a11y.spec.ts`

- [ ] **Step 1: Add the remaining static routes and the party record**

`/staff/clients/organisations`, `/staff/agents`, `/staff/notifications/messages`, `/staff/notifications/templates`, `/staff/audit-log`. The party record is derived from the live table, as the policy, claim and treaty records already are — and it is the fourth record shape once task 2 lands.

- [ ] **Step 2: The baseline stays `{}`**

Fix violations; never record them. This check caught `nested-interactive` on the claim record in plan 3 — a defect that had passed review, introduced by an accessibility fix that traded one violation for another. It has that power only while it is empty.

- [ ] **Step 3: Commit**

---

### Task 8: Make the three defects unrepeatable

**Files:**
- Modify: `frontend/src/test/designGuards.test.ts`

**This is the task that matters most, and it exists because I missed the same sweep three times.** Plan 2 left 15 raw alerts and 6 renaming buttons in its own lane. Plan 4 shipped a commit claiming a lane was clear while one of its screens had both. Both times the sweep was a grep over a directory list assembled by hand.

`designGuards.test.ts` already turns design drift into a failing test with four rules. Three more make this class of miss impossible.

- [ ] **Step 1: No button renames itself mid-request**

Fail on `status === 'loading' ?` inside a `<Button>`. The message should name `pending` and say why: a control whose accessible name changes mid-request is a different control to a screen reader and to every locator.

- [ ] **Step 2: No hand-rolled request error**

Fail on a raw `role="alert"` element that renders `error.detail`. Scope it to that pair so form-validation alerts stay legal, and say so in the rule's own comment.

- [ ] **Step 3: No hand-rolled back link**

Fail on `ArrowLeft` in `src/features`. The breadcrumb is the console's way back.

- [ ] **Step 4: Prove each guard fails**

Reintroduce one instance of each defect, watch the guard catch it, restore. A guard written after the last offender is removed passes trivially — which is the exact vacuity trap this project has recorded sixteen shapes of.

- [ ] **Step 5: Commit**

---

### Task 9: Lane review and merge

- [ ] **Step 1: Re-run the inventory script, not a grep**

It must report zero on all three columns. If a number is non-zero, the lane is not done — that is the whole point of having built it.

- [ ] **Step 2: Read the diff for the four shapes previous lanes produced**

A panel that stopped mounting; double padding; substring name collisions; an a11y fix that creates a different violation.

- [ ] **Step 3: Full verification, batched**

```
cd frontend && npx tsc -b && npx eslint src && npx vitest run
```

Then Playwright in feature-area batches, plus `--project=agents` separately.

- [ ] **Step 4: Screenshot the party record and both scheme records, desktop and phone, one batched round**

- [ ] **Step 5: Merge `--no-ff`**

---

## What this plan deliberately does not do

- **No new structural shape.** Tabs, a section bar, and a two-column record with neither are enough; the fourth option is always "this record needs none".
- **No softening of a role gate or of separation of duties**, which are backend rules the console only mirrors.
- **No conversion of form-validation alerts to `InlineError`**, which takes an `ApiError` and would be wrong for them.
- **No customer portal.** Still out of scope, still needs backend authorization work the "no API changes" rule forbids.
