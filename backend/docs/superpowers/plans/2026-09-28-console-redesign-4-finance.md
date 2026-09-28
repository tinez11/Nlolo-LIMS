# Console redesign, plan 4 of 5 — lane C: finance

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the seven screens in the console's own `finance` nav group onto the primitives plans 1–3 proved, and carry render tests into the lane from task 1 instead of discovering the need for them through e2e a third time.

**Architecture:** No new components. This lane consumes `PageHeader` (breadcrumb + status), `Button pending`, `InlineError`, `DetailLayout`, `Panel` and `FilterChip bare`. It adds no third structural shape: the two that exist — tabs for separate jobs, a section bar for one scroll — are enough, and the one record here that needs either gets the bar.

**Tech Stack:** unchanged from plan 1.

## Prerequisite

Plan 3 is merged (`b7f29be`), and the dev database was reset on 2026-09-28 (`dc7f0d8` fixed the MinIO bucket gap that reset exposed). Branch `console-redesign-4-finance` from `main`.

## Scope, and why this is the boundary

The lane is **the `finance` group in `screens.tsx`**, verbatim: Arrears, Bank transfers, Chart of accounts, Field receipts, GL postings, Regulatory returns, Treaties. Using the console's own grouping rather than a directory listing means the lane matches what a finance officer already has in their sidebar.

`distribution` (Agents, commission plans, commission statements) is its own nav group and belongs to plan 5 with clients — even though commission is money. Four of its files still swap button labels and two hand-roll error blocks; those are listed here only so plan 5 does not have to rediscover them.

## Global Constraints

Plan 1's Global Constraints bind. The ones this lane touches most:

- **No backend change, no OpenAPI change.** Money stays a string through `lib/money.ts`. **Never compute a total on the client** — `ChartOfAccountsPage`'s trial balance uses the server's own `balanced` verdict, and its comment says why. Do not "simplify" that into a comparison of two decimal strings.
- **The append-only ledger stays append-only.** `journal_entry` and `gl_posting` have no UPDATE or DELETE path by design; nothing in this lane may offer an edit affordance on a posting.
- **e2e locates by accessible name.** Seven specs drive these screens, 33 tests in total: `staff-finaccounting` (14), `staff-regulatory-returns` (4), `staff-arrears` (3), `staff-field-receipts` (3), `staff-bank-transfers` (3), `staff-reinsurance` (3), `staff-billing` (3). Grep before renaming anything.
- **Watch the 60s budget.** Two tests in this suite have already been found sitting on it (`staff-claim-evidence` at 39.8s, `staff-party-kyc` at 51.6s, both now `test.slow()`). If a task makes a finance screen do more work on arrival, measure the affected spec alone before and after.
- **Never run vitest while Playwright is running**, and never edit `frontend/src` mid-run.
- **A control run must match spec-for-spec.** If something here looks like a regression, read `feedback-e2e-budget-failures-look-like-regressions` before spending an hour on it.

## File map

| File | Lines | Responsibility | Task |
|---|---|---|---|
| `features/finaccounting/ChartOfAccountsPage.tsx` | 802 | split; `pending` ×3; `InlineError` | 2, 4, 5 |
| `features/billing/FieldReceiptsPage.tsx` | 380 | register parity | 6 |
| `features/payments/EftExecutionPage.tsx` | 332 | register parity | 6 |
| `features/billing/ArrearsPage.tsx` | 303 | register parity | 6 |
| `features/finaccounting/GlPostingsPage.tsx` | 256 | register parity | 6 |
| `features/reinsurance/TreatyDetailPage.tsx` | 213 | record rail it never had; breadcrumb | 3, 5 |
| `features/reinsurance/CreateTreatyPage.tsx` | 191 | breadcrumb; `pending` | 3, 4 |
| `features/regreporting/RegulatoryReturnsPage.tsx` | 183 | register parity | 6 |
| `features/reinsurance/TreatiesPage.tsx` | 165 | register parity | 6 |
| `features/finaccounting/GlPostingDetailPage.tsx` | 106 | breadcrumb | 3 |
| `features/regreporting/RegulatoryReturnDetailPage.tsx` | 102 | breadcrumb | 3 |
| `features/reinsurance/RecoveriesPanel.tsx` | 91 | `pending`; `InlineError` | 4 |
| `features/policies/PolicyDetailPage.tsx` | — | plan 2's missed error branch | 3 |
| `test/renderScreen.tsx` *(new)* | — | the harness task 1 builds | 1 |

---

### Task 1: A way to render a screen in a test, and proof it works

**Files:**
- Create: `frontend/src/test/renderScreen.tsx`
- Create: `frontend/src/features/finaccounting/ChartOfAccountsPage.test.tsx`

**This task comes first on purpose.** Three lanes have now shipped with test files that cover only pure functions, and the claims lane paid for it: four e2e failures cost four control runs to explain, partly because no unit test could say whether a claims screen still rendered. A lane that adds render coverage at the end adds it after the bugs.

- [ ] **Step 1: Write the harness**

`frontend/src/test/renderScreen.tsx`. A screen needs three things a bare `render()` does not give it: a router, an auth identity, and a store that is not carrying another test's state.

```tsx
import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { vi } from 'vitest';

/**
 * Render a whole screen the way the app mounts it.
 *
 * Feature screens read three things a bare `render()` leaves undefined: the router (every one
 * of them links somewhere), the OIDC identity (role gates decide what is even on the page),
 * and a zustand store. Without a harness each test wires those three by hand, which is the
 * friction that produced three lanes of pure-function-only tests.
 *
 * It does NOT mock the store. The store is where the screen's real behaviour lives -- a test
 * that mocks it asserts on its own fixture. Set state directly with `useXStore.setState` in
 * the test, exactly as `BeneficiariesPanel.test.tsx` already does.
 */
export function renderScreen(ui: ReactElement, { route = '/' }: { route?: string } = {}) {
  return render(<MemoryRouter initialEntries={[route]}>{ui}</MemoryRouter>);
}

/** The roles this identity holds. Call before rendering; the mock is module-level. */
export function asStaff(...roles: string[]) {
  vi.mocked(readIdentity).mockReturnValue({
    realmRoles: roles,
  } as ReturnType<typeof readIdentity>);
}
```

Check `@/auth/claims`'s real exports before writing `asStaff` — mirror `readIdentity`'s actual return shape rather than the sketch above, and mock `react-oidc-context`'s `useAuth` alongside it if the screen calls it directly.

- [ ] **Step 2: Write the first screen test, and watch it fail for the right reason**

`ChartOfAccountsPage` first because it is the biggest screen on the platform and the one task 5 splits — a test that passes before and after the split is what makes that split safe.

```tsx
it('renders the register with the accounts the store holds', () => {
  useFinaccountingStore.setState({ accounts: { status: 'success', data: [...], error: null } });
  renderScreen(<ChartOfAccountsPage />);
  expect(screen.getByRole('heading', { name: 'Chart of accounts' })).toBeInTheDocument();
  expect(screen.getByText('1000')).toBeInTheDocument();
});
```

Read the store's real slice shape first (`src/store/`) — invent nothing.

- [ ] **Step 3: Assert the trial balance shows the SERVER's verdict**

The one assertion this screen most needs, and the one a refactor is most likely to break:

```tsx
it('reports the server\'s own balanced verdict, never a comparison done here', () => {
  useFinaccountingStore.setState({
    trialBalance: { status: 'success', error: null,
      data: { totalDebit: {...}, totalCredit: {...}, balanced: false } },
  });
  renderScreen(<ChartOfAccountsPage />);
  expect(screen.getByText('OUT OF BALANCE')).toBeInTheDocument();
});
```

Give it debits and credits that are EQUAL while `balanced` is `false`. A client-side comparison would say "In balance" and the test would catch it; equal-and-balanced proves nothing.

- [ ] **Step 4: Run, then verify non-vacuously**

```
cd frontend && npx vitest run src/features/finaccounting
```

Then break it deliberately — change the rendered heading, re-run, confirm failure, restore. Plan 3's first attempt at exactly this check matched nothing and passed while proving nothing; do not skip the restore-and-confirm.

- [ ] **Step 5: Commit**

```
git commit -m "test(console): a screen can be rendered in a unit test, and the ledger's balance verdict is pinned"
```

---

### Task 2: Split `ChartOfAccountsPage`

**Files:**
- Modify: `frontend/src/features/finaccounting/ChartOfAccountsPage.tsx`
- Create: `frontend/src/features/finaccounting/AccountForms.tsx`

802 lines holding a page plus five private components (`ViewToggle`, `AccountRowActions`, `CreateAccountForm`, `UpdateAccountForm`, `DeleteAccountForm`). The page itself is well-reasoned and heavily commented — this is a split, not a rewrite.

- [ ] **Step 1: Move the three forms out**

`CreateAccountForm`, `UpdateAccountForm` and `DeleteAccountForm` (lines ~585–802) move to `AccountForms.tsx` verbatim, with their comments. Export each. `ViewToggle` and `AccountRowActions` stay — they are the page's own chrome and the page is their only caller.

- [ ] **Step 2: Verify nothing moved but the location**

```
cd frontend && npx tsc -b && npx eslint src && npx vitest run src/features/finaccounting
```

Task 1's tests must pass unchanged. If one needed editing, something moved that should not have.

- [ ] **Step 3: Commit**

---

### Task 3: Four breadcrumbs, and the one plan 2 missed

**Files:**
- Modify: `frontend/src/features/finaccounting/GlPostingDetailPage.tsx`
- Modify: `frontend/src/features/regreporting/RegulatoryReturnDetailPage.tsx`
- Modify: `frontend/src/features/reinsurance/TreatyDetailPage.tsx`
- Modify: `frontend/src/features/reinsurance/CreateTreatyPage.tsx`
- Modify: `frontend/src/features/policies/PolicyDetailPage.tsx`

- [ ] **Step 1: Replace each hand-rolled back link with a breadcrumb**

Each of the four renders a ghost `Button` with an `ArrowLeft` in a `px-6 pt-6` strip ABOVE the page bar — navigation doing a breadcrumb's job while pushing the sticky bar 44px down the page. Replace with `breadcrumb={[{ label: '…', to: '…' }]}` on the existing `PageHeader`, delete the `BackLink` helper and the now-unused `ArrowLeft` import.

Labels come from the nav: `GL postings`, `Regulatory returns`, `Treaties`.

- [ ] **Step 2: Fix `PolicyDetailPage`'s error branch**

Plan 2 gave the success branch a breadcrumb and left the ERROR branch rendering the old `BackLink` — so a policy that fails to load looks like a different console from one that loads. Give the error branch a `PageHeader` with the same breadcrumb, exactly as `ClaimDetailPage` does since plan 3:

```tsx
  if (detail.data === null && detail.status === 'error' && detail.error) {
    return (
      <>
        <PageHeader breadcrumb={[{ label: 'Policies', to: `/${realm}/policies` }]} title={policyNumber} />
        <div className="px-6 pt-6">
          <ErrorPanel error={detail.error} onRetry={() => void loadDetail(policyNumber)} />
        </div>
      </>
    );
  }
```

Then confirm `BackLink` and `ArrowLeft` are gone from the file — if either survives, a caller was missed.

- [ ] **Step 3: Check the specs**

```
cd frontend && grep -rn "All policies\|All treaties\|All postings\|All returns" e2e/
```

Any spec clicking one of those must move to the breadcrumb link in this commit.

- [ ] **Step 4: Verify and commit**

---

### Task 4: `pending`, and the last hand-rolled error blocks in this lane

**Files:**
- Modify: `frontend/src/features/finaccounting/ChartOfAccountsPage.tsx` (or `AccountForms.tsx` after task 2)
- Modify: `frontend/src/features/reinsurance/CreateTreatyPage.tsx`
- Modify: `frontend/src/features/reinsurance/RecoveriesPanel.tsx`

- [ ] **Step 1: Five buttons stop renaming themselves**

| file | label |
|---|---|
| `AccountForms.tsx` | `Create account`, `Rename`, `Delete account` |
| `CreateTreatyPage.tsx` | `Create treaty` |
| `RecoveriesPanel.tsx` | `Confirm recovery` |

Each becomes `pending={x.status === 'loading'}` with a fixed label. A control whose accessible name changes mid-request is a different control to a screen reader and to every locator.

- [ ] **Step 2: Two hand-rolled `role="alert"` blocks become `InlineError`**

`ChartOfAccountsPage` and `RecoveriesPanel`. `AgentDetailPage` and `CommissionStatementsPanel` have the same defect and are **plan 5's**, not this lane's — do not widen.

- [ ] **Step 3: Verify and commit**

```
cd frontend && npx playwright test staff-finaccounting staff-reinsurance --project=staff
```

---

### Task 5: The treaty record gets the rail every other record has

**Files:**
- Modify: `frontend/src/features/reinsurance/TreatyDetailPage.tsx`

`TreatyDetailPage` has a `PageHeader` and no `DetailLayout` and no `Panel` — it is the only record on the platform that never adopted the two-column shape, so the treaty's own facts scroll away while a person reads its cessions.

- [ ] **Step 1: Put the facts in the rail**

Reinsurer, treaty type, retention, cession limit, status, effective dates — the answers needed *while* reading the cession and recovery tables. `DetailLayout`'s own doc is the rule: `record` is facts, `children` is the work, and **no mutating action goes in the rail.**

- [ ] **Step 2: Cessions and recoveries become the work column**

Both are tables that need width, which is the same reason plan 3 moved `RecoveriesPanel` out of the claim's 320px rail.

- [ ] **Step 3: No section bar unless it earns one**

Two panels is a scroll, not a navigation problem. Add `SectionNav` only if a third register appears. Resist the symmetry argument — plan 3's bar exists because a claim has up to seven sections, not because records have bars.

- [ ] **Step 4: Verify and commit**

---

### Task 6: Register parity across the six list screens

**Files:** `ArrearsPage`, `FieldReceiptsPage`, `EftExecutionPage`, `GlPostingsPage`, `TreatiesPage`, `RegulatoryReturnsPage`

- [ ] **Step 1: One toolbar shape**

Filters left, search right, count in the header byline as a `CountLine`. The chip row is `flex flex-wrap items-center gap-1.5 gap-y-2` — **`gap-y-2` is not optional**, it is what stops the search box landing hard against the chips when the row wraps, and it has now been missed twice.

- [ ] **Step 2: Status chips use `FilterChip bare` with a `StatusBadge` label**

`bare` exists because a filled chip wrapping a badge is a pill inside a pill — a regression caught in a screenshot, not a test, on seven screens.

- [ ] **Step 3: Bold the identifying column only**

The column a person scans — a posting reference, a treaty code — not a date and not a type.

- [ ] **Step 4: Verify and commit**

```
cd frontend && npx playwright test staff-arrears staff-field-receipts staff-bank-transfers staff-finaccounting staff-regulatory-returns staff-reinsurance --project=staff
```

---

### Task 7: The finance records join the axe sweep

**Files:** `frontend/e2e/staff-a11y.spec.ts`

- [ ] **Step 1: Add the chart of accounts and one treaty record**

`/staff/chart-of-accounts` is a static route and goes straight into `ROUTES`. The treaty record is derived from the live table, the way the policy and claim records already are.

- [ ] **Step 2: The baseline stays `{}`**

If a finance screen introduces a violation, **fix the violation**. Do not record it. The baseline caught `nested-interactive` on the claim record in plan 3 — a defect review had passed — and it only has that power while it is empty.

- [ ] **Step 3: Commit**

---

### Task 8: Lane review and merge

- [ ] **Step 1: Read the whole diff against `main`**

Look for the four things previous lanes produced:
1. **A panel that stopped mounting** (plan 2's invoice count).
2. **Double padding** where a component brings a gutter into a parent that already has one (plan 2's `RecordTabs`).
3. **Substring name collisions** — Playwright matches substrings by default.
4. **An a11y fix that creates a different violation** (plan 3's `role="button"` over a focusable input).

- [ ] **Step 2: Full verification**

```
cd frontend && npx tsc -b && npx eslint src && npx vitest run
cd frontend && npx playwright test
```

Expect the `agents` project to fail as a block if the run exceeds ~1h — its cookie idles out, which is a recorded trap and not a code fault. Re-run that project alone to confirm.

- [ ] **Step 3: Screenshot desktop and phone width in one batched round**

- [ ] **Step 4: Merge `--no-ff`**

---

## What this plan deliberately does not do

- **No third structural shape.** Tabs and a section bar are enough.
- **No `distribution` screens.** Agents, commission plans and commission statements are plan 5's, with clients. Their four label-swapping buttons and two hand-rolled error blocks are listed above only so plan 5 inherits the list rather than the search.
- **No client-side money arithmetic**, including anything that looks like a convenience total.
- **No edit affordance on a posting.** The ledger is append-only and the absence of that button is the feature.
