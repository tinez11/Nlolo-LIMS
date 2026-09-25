# Console redesign, plan 2 of 5 — lane A: policies

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put plan 1's primitives onto the policy surfaces — the register, the record, and the four panels that hang off it — so the policy screens are the worked example the other three lanes copy.

**Architecture:** No new components. This lane *consumes* `PageHeader` (breadcrumb + status), `RecordTabs`, `Button pending`, `InlineError`, `Badge`, `DataTable`'s band, and `DetailLayout`'s rail. It is the first lane deliberately: it is the only one that exercises `RecordTabs` and the breadcrumb, and plans 3–5 are written against whatever this one proves.

**Tech Stack:** unchanged from plan 1.

## Prerequisite

Plan 1 (`console-redesign-1-foundation`) must be merged to `main` first. Branch `console-redesign-2-policies` from `main`.

## Global Constraints

Everything in plan 1's Global Constraints still binds. Re-read that section; it is not repeated here. The ones this lane touches most:

- **No backend change, no OpenAPI change.** Money stays a string through `lib/money.ts`.
- **`CHOREOGRAPHY_NOT_IMPLEMENTED` handling must survive** — it lives in `PolicyDetailPage.tsx` and `PolicyDrawer.tsx`, both edited here.
- **The disabled Surrender button stays disabled.** `POST /policies/{n}/surrender` really does return 501.
- **e2e locates by accessible name.** Eight specs touch these screens: `staff-policies`, `staff-beneficiaries`, `staff-billing`, `staff-policy-loans`, `staff-role-gates`, `staff-group-schemes`, `staff-credit-life-scheme`, `staff-credit-life-invoices`. Grep before renaming anything, and budget the spec updates into the task that causes them.
- **Never run vitest while Playwright is running.** Never edit `frontend/src` while a Playwright run is in flight — the dev server hot-reloads under the tests.
- **`--no-deps` reuses expired Keycloak cookies.** Re-run with deps before believing a failure. See the memory entry; it has cost two diagnostic cycles already.

## File map

| File | Responsibility | Task |
|---|---|---|
| `features/policies/PoliciesPage.tsx` | register: toolbar, banded table, bold first column | 1 |
| `features/policies/PolicyDetailPage.tsx` | record: breadcrumb, status in the bar, tabs | 2, 3 |
| `features/policies/PolicyDrawer.tsx` | preview slimmed to a summary, not a second record | 4 |
| `features/policies/BeneficiariesPanel.tsx`, `BeneficiaryRow.tsx` | `Button pending`, field grid | 5 |
| `features/policies/InvoicesPanel.tsx` | status tabs, not a pager | 5 |
| `features/policies/LoansPanel.tsx` | `pending`, zero-cash-value copy kept | 5 |
| `features/policies/SubmissionsPanel.tsx` | 6 raw `<button>`s → `Button` | 5 |
| 8 e2e specs | tab-aware navigation | 2, 3, 5 |

---

### Task 1: The policy register

**Files:**
- Modify: `frontend/src/features/policies/PoliciesPage.tsx`
- Test: `frontend/e2e/staff-policies.spec.ts` (existing, should stay green unchanged)

**Interfaces:**
- Consumes: `PageHeader`, `FilterChip bare`, `DataTable`, `Pager`, `Badge`.
- Produces: the toolbar arrangement plans 3–5 copy — filters left, search right, count in the header byline.

- [ ] **Step 1: Bold the identifying column**

In the `columns` array, the `policyNumber` cell renderer wraps its value in `font-medium`. Espresso bolds the first column because it is the one a person scans; the rest stay regular. Do NOT bold a first column that is a date or a type — on this screen it is the policy number, which identifies the row.

- [ ] **Step 2: Move the search box out of the filter row on narrow widths**

The current row is `flex flex-wrap items-center gap-1.5` with the search `ml-auto w-full max-w-55`. At phone width the chips wrap and the search lands under them at full width, which is correct — verify it, do not change it. If it does not, give the wrapper `gap-y-2`.

- [ ] **Step 3: Run the spec**

Run: `npx playwright test e2e/staff-policies.spec.ts --project=staff`
Expected: PASS, unchanged. This task renames nothing, so a failure here is a real regression.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/features/policies/PoliciesPage.tsx
git commit -m "feat(console): the policy register scans on its identifying column"
```

---

### Task 2: The policy record's header

**Files:**
- Modify: `frontend/src/features/policies/PolicyDetailPage.tsx:100-145`

**Interfaces:**
- Produces: the header arrangement plans 3–5 copy — breadcrumb replaces `BackLink`, status moves out of `actions` into `status`.

- [ ] **Step 1: Breadcrumb replaces the back link**

Delete the `<div className="px-6 pt-6"><BackLink /></div>` block above `PageHeader` and pass instead:

```tsx
breadcrumb={[{ label: 'Policies', to: `/${realm}/policies` }]}
```

`realm` is already a prop on this page (it takes `realm="agents"` from the manifest). Check whether `BackLink` is still used elsewhere in the file before removing its import; if not, remove it.

**Watch:** `staff-policies.spec.ts` and `staff-role-gates.spec.ts` may click a back affordance. Grep for `All policies` — that is `BackLink`'s accessible name — and repoint any hit at the breadcrumb link named `Policies`.

- [ ] **Step 2: Status moves to its own slot**

`{policy?.status && <StatusBadge kind="policy" value={policy.status} />}` is currently the first child of `actions`. Move it to `status={policy?.status ? <StatusBadge kind="policy" value={policy.status} /> : undefined}`. It then sits beside the title, which is where a record's state belongs, and `actions` holds only actions.

- [ ] **Step 3: Verify**

Run: `npm run typecheck ; npm run lint ; npx vitest run`
Then: `npx playwright test e2e/staff-policies.spec.ts e2e/staff-role-gates.spec.ts --project=staff`

- [ ] **Step 4: Commit**

```bash
git commit -am "feat(console): a policy record says where it sits and what state it is in"
```

---

### Task 3: The policy record's tabs

**Files:**
- Modify: `frontend/src/features/policies/PolicyDetailPage.tsx:168-232`
- Modify: `frontend/e2e/staff-beneficiaries.spec.ts`, `staff-billing.spec.ts`, `staff-policy-loans.spec.ts`, and any other spec that reaches a panel now behind a tab

**Interfaces:**
- Consumes: `RecordTabs`, `TabDef` from `@/components/RecordTabs`.
- Produces: the tab set, and the `?tab=` values specs use to deep-link.

**Tab set** — act-first, so the tab a person came to use opens by default:

| Tab | `value` | Contents (existing panels, unmoved) |
|---|---|---|
| Overview | `overview` (default, no param) | Coverage, Lifecycle, and the two offer panels ("Not yet on cover", "Offer expired unpaid") |
| Beneficiaries | `beneficiaries` | `BeneficiariesPanel` |
| Billing | `billing` | `InvoicesPanel` |
| Loans | `loans` | `LoansPanel` |
| Messages | `messages` | `MessagesPanel` |
| Reinsurance | `reinsurance` | `CessionsPanel`, only when the page already renders it |

- [ ] **Step 1: Wrap the panel stack**

Replace the children of `<DetailLayout record={renderRecord()}>` with a single `<RecordTabs label="Policy sections" tabs={...} />`. Build the array in a `const tabs: TabDef[] = [...]` above the return, filtering out the tabs whose panel the page already renders conditionally — a tab that leads to nothing is worse than no tab.

**Do not add counts** unless the page already holds the number. It does not for any of these, so no tab carries one in this task.

- [ ] **Step 2: Keep the offer panels on Overview**

"Not yet on cover" and "Offer expired unpaid" are the state of the contract, not a register. They belong on Overview above Coverage, and they are exactly the thing a tab would hide from someone who needs it.

- [ ] **Step 3: Update the specs**

For each spec that opened a panel by scrolling, add a tab click or a `?tab=` URL. Prefer the URL in a spec that navigates anyway (`page.goto('/staff/policies/X?tab=billing')`) and the click where the spec is about the journey.

Run each one and read the failure before changing it — a spec may be asserting the panel's absence, which is a different fix.

- [ ] **Step 4: Verify**

Run: `npm run typecheck ; npm run lint ; npx vitest run`
Then: `npx playwright test e2e/staff-beneficiaries.spec.ts e2e/staff-billing.spec.ts e2e/staff-policy-loans.spec.ts e2e/staff-policies.spec.ts --project=staff`

- [ ] **Step 5: Screenshot it**

Run with `CAPTURE=1` and add `/staff/policies/<a real POL number>` to `staff-visual-capture.spec.ts`'s route list first. Look at it at both widths: the tab list must not wrap, and the sticky tabs must sit directly under the page bar with no gap or overlap.

- [ ] **Step 6: Commit**

```bash
git commit -am "feat(console): a policy record is six sections, not one ten-panel scroll"
```

---

### Task 4: The drawer stops being a second record

**Files:**
- Modify: `frontend/src/features/policies/PolicyDrawer.tsx`

The drawer currently re-renders much of what the detail page shows, including its own `CHOREOGRAPHY_NOT_IMPLEMENTED` handling. A preview exists to answer "is this the row I want?" — it should carry identity, status, sum assured, premium, and a link through. Anything that requires a decision belongs on the record.

- [ ] **Step 1: Read it against the detail page** and list what appears in both. Keep the identifying facts in the drawer, delete the rest.
- [ ] **Step 2:** The 501 handling stays in `PolicyDetailPage`. If the drawer no longer renders a surrender affordance, its copy of that handling goes with it — confirm by grep that no other caller depends on it.
- [ ] **Step 3:** `npx playwright test e2e/staff-policies.spec.ts --project=staff` — the preview test is in there.
- [ ] **Step 4: Commit.**

---

### Task 5: The four panels

**Files:**
- Modify: `BeneficiariesPanel.tsx`, `BeneficiaryRow.tsx`, `InvoicesPanel.tsx`, `LoansPanel.tsx`, `SubmissionsPanel.tsx`

- [ ] **Step 1: `Button pending` everywhere a form submits**

Replace `disabled={x.status === 'loading'}` with `pending={x.status === 'loading'}` and delete the hand-rolled label swap ("Saving…" / "Save") — `pending` renders the spinner and `aria-busy`. Keep the label constant; a button whose name changes mid-flight breaks the e2e locator and reads as a different control to a screen reader.

**This is the first adoption of `pending` in production.** Plan 1's Button docstring says so explicitly. Once this task lands, update that docstring to stop saying "not yet the practice".

- [ ] **Step 2: `InvoicesPanel` gets status tabs, not a pager**

`GET /policies/{n}/invoices` takes a `status` filter and **no** pagination — the list is bounded by construction (~12 months pre-generated). Use `FilterChip bare` with `StatusBadge kind="invoice"` labels, matching the register toolbars. Do not add a `Pager`.

- [ ] **Step 3: `SubmissionsPanel`'s six raw `<button>`s become `Button`**

They are at lines 194, 208, 217, 273, 337, 357. Each needs the right variant — read what it does first. A destructive one takes `danger`; a row-expander takes `ghost`.

- [ ] **Step 4: `LoansPanel` keeps its zero-cash-value copy verbatim.** The platform really does value every policy at TZS 0.00 and `reserveLoanValue` really does reject a positive request. Do not soften it into an empty state.

- [ ] **Step 5: Verify and commit.**

Run the full lane: `npx playwright test e2e/staff-beneficiaries.spec.ts e2e/staff-billing.spec.ts e2e/staff-policy-loans.spec.ts --project=staff`

---

### Task 6: Lane gate

- [ ] `npm run typecheck ; npm run lint ; npx vitest run ; npm run build`
- [ ] `npx playwright test` (full, with deps — ~55 min; expect the documented agents-cookie caveat)
- [ ] `npx playwright test e2e/staff-a11y.spec.ts --project=staff` — the baseline is `{}` and must stay `{}`. Add `/staff/policies/<POL>` to the a11y route list in this task, so the record page is covered too; re-record only if it goes DOWN.
- [ ] `CAPTURE=1` screenshots, compared against `visual-before/`.
- [ ] Dispatch the whole-branch review. It found ten real things on plan 1, including a P1 nobody would have seen in a screenshot.
- [ ] Merge `--no-ff` to `main` (the repo has no remote).
