# Console redesign, plan 1 of 5 — the foundation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the staff + agent console the tokens, primitives and shell that the four screen-lane plans (2–5) build on — Frappe "Espresso" structure, this project's own contrast values — without changing any screen's behaviour.

**Architecture:** Additive token changes in `frontend/src/index.css`; revised and new primitives in `frontend/src/components/`; a guard test that makes design drift a failing unit test; shell changes in `AppShell.tsx`; a Ctrl+K go-to palette over the existing screen manifest; role-aware landing; lazy-loaded routes. No backend change. No API change. No new runtime dependency (cmdk, Radix Dialog and Radix Tabs are already installed).

**Tech Stack:** React 19, Vite 8, Tailwind v4 (`@theme inline`, `pointer-coarse:` variant), class-variance-authority, Radix (dialog, tabs), cmdk, React Router 7, Vitest 4 + Testing Library, Playwright 1.62 against the real seeded stack.

## Plan series

| Plan | Scope | Written |
|---|---|---|
| **1 (this)** | Guardrails, tokens, primitives, shell, palette, landing, lazy routes | now |
| 2 | Lane A: PoliciesPage, PolicyDetailPage **with tabs**, Beneficiaries, Invoices, Loans, the disabled-Endorse reason, PolicyDrawer slimmed | after plan 1 merges |
| 3 | Lane B: ClaimsPage, ClaimDetailPage **with SectionNav**, EvidencePanel, RegisterClaimPage | after plan 1 merges |
| 4 | Lane C: Arrears, Field receipts, Bank transfers, GL postings, Chart of accounts, Treaties, Returns | after plan 1 merges |
| 5 | Lane D: Clients, PartyDetailPage **with tabs**, Underwriting, Group/Credit-life schemes (absorbing the two raw tables), Products, Agents | after plan 1 merges |

Plans 2–5 are deliberately not written yet: every one of them consumes the exact component APIs this plan produces, and writing them first would mean guessing at code.

## Global Constraints

- **No backend change, no OpenAPI change, no generated-client change.** Frontend only.
- **Keep the theme.** Achromatic ink-on-paper plus the six status hues. No new hue, no new font, no brand colour, no logo. The ERPNext blue tile is NOT adopted.
- **Contrast floor is this project's, not Espresso's:** text ≥ 4.5:1, control boundaries and focus ≥ 3:1. The `--input` border (`oklch(0.66 0 0)`, 3.11:1) stays on every text field. Focus ring stays `--ring` (ink).
- **Minimum text size is 12px** (`text-xs`). `text-[10px]` and `text-[11px]` are banned after Task 4.
- **Touch targets:** every control reaches 44px under `pointer-coarse:`; desktop keeps 32/36px.
- **Money is a string end to end.** Only `lib/money.ts` parses it. No `parseFloat`/`Number()` on an amount.
- **Submit guards stay.** Every mutating button keeps `disabled` while its request is in flight; `lib/idempotency.ts` is untouched.
- **`CHOREOGRAPHY_NOT_IMPLEMENTED` handling** in ClaimSettlementPanel, LoansPanel, PolicyDetailPage, PolicyDrawer is preserved as-is.
- **Tenancy stays invisible.** No tenant input, display or switcher anywhere.
- **No fabricated numbers.** No analytics, trends or counts without an endpoint behind them.
- **Keycloak login theme (Split leaf, green) is out of scope** — do not touch `backend/keycloak/themes`.
- **Never run Prettier.** Match surrounding formatting by hand (single quotes, 2-space, ~100 cols).
- **Edit files with the Edit/Write tools**, not shell heredocs. A node script may do a pure pattern replacement and nothing else.
- **Never run vitest while Playwright is running**, and never run Playwright during a Maven build.
- **e2e locates by accessible name.** Any renamed label, heading or button breaks specs; grep `frontend/e2e` before renaming anything.

## Branch

`fd11e05` (lender-only commission) is on `credit-life-lender-only-commission` and not yet on `main`. Merge it first, then branch `console-redesign-1-foundation` from `main`. If it is not being merged yet, branch from `credit-life-lender-only-commission` instead and say so in the final merge message.

## File map

| File | Responsibility | Task |
|---|---|---|
| `frontend/package.json` | add `@axe-core/playwright`, `puppeteer` (dev) | 1 |
| `frontend/e2e/staff-a11y.spec.ts` (new) | axe over the list routes vs a committed baseline | 1 |
| `frontend/e2e/a11y-baseline.json` (new) | recorded violations, shrinks over the series | 1, 15 |
| `frontend/e2e/staff-visual-capture.spec.ts` (new) | opt-in screenshots for human review, never asserted | 1 |
| `frontend/src/test/designGuards.test.ts` (new) | drift as a failing test | 2, 4, 7 |
| `frontend/src/index.css` | new tokens, radius, tracking | 2 |
| 7 feature files | dead token classes fixed | 2 |
| `frontend/src/components/Receipt.tsx` | drop alpha text | 2 |
| `frontend/src/components/InlineError.tsx` (new) + 18 feature files | one error block | 3 |
| ~63 files | 10/11px → `text-xs` | 4 |
| `frontend/src/lib/status.ts` | acronym-safe humanise | 5 |
| `frontend/src/components/ui/button.tsx` | `secondary` default, `pending`, coarse sizes | 6 |
| `frontend/src/components/ui/input.tsx` | filled + bordered, coarse sizes | 7 |
| `frontend/src/components/ui/checkbox.tsx` (new) + 3 feature files | styled checkbox | 7 |
| `frontend/src/components/ui/badge.tsx` (new), `FilterChip.tsx`, `UnderwritingQueuePage.tsx` | one pill anatomy | 8 |
| `frontend/src/components/PageHeader.tsx`, `DataTable.tsx`, `DetailLayout.tsx`, `Panel.tsx` | sticky bar, band, rail that pins only when it fits | 9 |
| `frontend/src/components/RecordTabs.tsx`, `SectionNav.tsx` (new) | tabs (URL-synced) and section bar | 10 |
| `frontend/src/components/AppShell.tsx` | workspace header, raised active item, Go-to button | 11 |
| `frontend/src/components/CommandPalette.tsx` (new) | Ctrl+K | 12 |
| `frontend/src/screens.tsx`, `App.tsx`, `components/RealmHome.tsx` (new), 4 e2e setups | role-aware landing | 13 |
| `frontend/src/screens.tsx`, `AppShell.tsx` | lazy routes + Suspense | 14 |
| `DESIGN.md` | frontmatter synced to the CSS | 15 |

---

### Task 1: Guardrails — axe baseline and review screenshots

**Files:**
- Modify: `frontend/package.json`
- Create: `frontend/e2e/staff-a11y.spec.ts`, `frontend/e2e/a11y-baseline.json`, `frontend/e2e/staff-visual-capture.spec.ts`

**Interfaces:**
- Produces: `npm run test:e2e -- e2e/staff-a11y.spec.ts` fails on any axe violation not in the baseline, or on a count that grew. `A11Y_RECORD=1` rewrites the baseline. `CAPTURE=1` writes PNGs to `frontend/test-results/visual/`.

**Why a baseline rather than "zero violations":** the current count is unknown (axe has never run here). A zero-assertion spec would be red from day one and get skipped; a baseline makes every later task able to prove it did not add a violation, and Task 15 proves the series removed some. Screenshots are NOT asserted with `toHaveScreenshot`: the e2e suite mutates the shared seeded database (policies, claims and counts change every run), so a pixel diff against it would be flaky or — worse — pass by accident. They are captured for a human to compare.

- [ ] **Step 1: Add the dev dependencies**

Run (from `frontend/`): `npm install --save-dev @axe-core/playwright puppeteer`

Expected: both appear under `devDependencies`. Puppeteer downloads its own Chromium (~170 MB). Note in the commit message that Puppeteer only helps `detect.mjs` scan the unauthenticated realm picker — every console screen is behind Keycloak, which the detector cannot pass.

- [ ] **Step 2: Write the a11y spec**

Create `frontend/e2e/staff-a11y.spec.ts`:

```ts
import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';

/**
 * axe over the console's list screens, compared against a committed baseline.
 *
 * A baseline rather than an assertion of zero, because axe had never run on this console
 * when this spec was written and the true count was unknown. Zero would have been red from
 * the first run and skipped; a baseline means every change can prove it added nothing, and
 * the redesign series can prove it removed something. A rule that appears, or a count that
 * grows, fails. A count that shrinks passes and should be re-recorded (A11Y_RECORD=1).
 *
 * Admin, because ADMIN holds every staff role and so reaches every screen below.
 */
const ROUTES = [
  '/staff/policies',
  '/staff/claims',
  '/staff/underwriting',
  '/staff/clients/individuals',
  '/staff/arrears',
  '/staff/field-receipts',
  '/staff/gl-postings',
  '/staff/products',
];

const BASELINE = 'e2e/a11y-baseline.json';
type Counts = Record<string, number>;

test.use({ storageState: 'e2e/.auth/staff-admin.json' });

test('no accessibility violation beyond the recorded baseline', async ({ page }) => {
  test.slow();
  const found: Counts = {};

  for (const route of ROUTES) {
    await page.goto(route);
    await expect(page.locator('h1')).toBeVisible({ timeout: 30_000 });
    // Let the first data fetch land, so axe sees the table rather than a skeleton.
    await page.waitForLoadState('networkidle');

    const result = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
      .analyze();
    for (const violation of result.violations) {
      found[`${route} :: ${violation.id}`] = violation.nodes.length;
    }
  }

  if (process.env.A11Y_RECORD) {
    const sorted = Object.fromEntries(Object.entries(found).sort(([a], [b]) => a.localeCompare(b)));
    writeFileSync(BASELINE, `${JSON.stringify(sorted, null, 2)}\n`);
    return;
  }

  const baseline = JSON.parse(readFileSync(BASELINE, 'utf8')) as Counts;
  const regressions = Object.entries(found)
    .filter(([key, count]) => count > (baseline[key] ?? 0))
    .map(([key, count]) => `${key}: ${count} (baseline ${baseline[key] ?? 0})`);
  expect(regressions, 'new or growing axe violations').toEqual([]);
});
```

- [ ] **Step 3: Seed an empty baseline and run to see it fail**

Create `frontend/e2e/a11y-baseline.json` containing `{}` followed by a newline.

Prerequisite: the real stack is up (`docker compose -f ../backend/infra/docker-compose.yml up -d`, `../backend/scripts/seed-dev-data.sh`) and no Maven build is running.

Run: `npx playwright test e2e/staff-a11y.spec.ts --project=staff`
Expected: FAIL listing every current violation. **This failure is the proof the spec can fail** — if it passes against `{}`, axe found nothing and something is wrong with the spec (check the routes rendered an `h1`).

- [ ] **Step 4: Record the real baseline**

Run (PowerShell): `$env:A11Y_RECORD='1'; npx playwright test e2e/staff-a11y.spec.ts --project=staff; Remove-Item Env:A11Y_RECORD`
Then run without the variable: `npx playwright test e2e/staff-a11y.spec.ts --project=staff`
Expected: PASS. Open `e2e/a11y-baseline.json` and paste its rule list into the commit message — it is the "before" figure.

- [ ] **Step 5: Write the capture spec**

Create `frontend/e2e/staff-visual-capture.spec.ts`:

```ts
import { expect, test } from '@playwright/test';

/**
 * Screenshots for a HUMAN to compare before and after the redesign. Never asserted.
 *
 * Not `toHaveScreenshot`: the suite writes to the shared seeded database, so policy numbers,
 * counts and dates move between runs, and a pixel diff would either flake or be tuned until
 * it proves nothing. Opt-in so it never slows the ordinary run.
 */
const ROUTES = ['policies', 'claims', 'underwriting', 'clients/individuals', 'arrears'];
const VIEWPORTS = [
  { name: 'desktop', width: 1440, height: 900 },
  { name: 'phone', width: 390, height: 844 },
];

test.use({ storageState: 'e2e/.auth/staff-admin.json' });

test('capture review screenshots', async ({ page }) => {
  test.skip(!process.env.CAPTURE, 'set CAPTURE=1 to write review screenshots');
  test.slow();
  for (const viewport of VIEWPORTS) {
    await page.setViewportSize(viewport);
    for (const route of ROUTES) {
      await page.goto(`/staff/${route}`);
      await expect(page.locator('h1')).toBeVisible({ timeout: 30_000 });
      await page.waitForLoadState('networkidle');
      await page.screenshot({
        path: `test-results/visual/${viewport.name}-${route.replace(/\//g, '_')}.png`,
        fullPage: true,
      });
    }
  }
});
```

- [ ] **Step 6: Capture the "before" set**

Run (PowerShell): `$env:CAPTURE='1'; npx playwright test e2e/staff-visual-capture.spec.ts --project=staff; Remove-Item Env:CAPTURE`
Expected: 10 PNGs in `frontend/test-results/visual/`. Copy the folder to `frontend/test-results/visual-before/` (it is git-ignored; it is only for review).

- [ ] **Step 7: Commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/e2e/staff-a11y.spec.ts frontend/e2e/a11y-baseline.json frontend/e2e/staff-visual-capture.spec.ts
git commit -m "test(console): an axe baseline over the list screens, and opt-in review screenshots"
```

---

### Task 2: Tokens, and drift as a failing test

**Files:**
- Create: `frontend/src/test/designGuards.test.ts`
- Modify: `frontend/src/index.css`, `frontend/src/components/Receipt.tsx:70,79`, `frontend/src/components/ui/button.tsx:16`
- Modify (dead classes): `features/underwriting/UnderwritingQueuePage.tsx:128,135`, `features/distribution/OnboardAgentPage.tsx:155`, `features/party/PartyDetailPage.tsx:220`, `features/policies/IssuePolicyPage.tsx:490,543`, `features/underwriting/OpenUnderwritingCasePage.tsx:230`, `features/policies/PolicyDetailPage.tsx:179,188`

**Interfaces:**
- Produces Tailwind utilities: `bg-control`, `bg-control-hover`, `hover:bg-control-hover`, `bg-band`, `shadow-raise`. Radius scale becomes `rounded-sm` 6px / `rounded-md` 8px / `rounded-lg` 10px.
- Produces `designGuards.test.ts` with an exported-by-convention `RULES` array later tasks append to.

- [ ] **Step 1: Write the failing guard test**

Create `frontend/src/test/designGuards.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import css from '@/index.css?raw';

/**
 * Design drift as a failing test.
 *
 * Every rule here was a real finding, and every one was invisible to the rest of the suite:
 * a class naming a token that does not exist compiles, renders nothing, and passes every
 * behavioural test -- nine of them shipped that way (`bg-muted`, `text-fg-muted`). The
 * rules read source, not rendered output, so they catch the drift at the line it is written.
 */
const SOURCES = import.meta.glob('/src/**/*.tsx', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>;

/** Comments removed, so prose ABOUT a class (there is plenty) is not read as a use of it. */
function code(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');
}

const files = Object.entries(SOURCES).filter(([path]) => !path.endsWith('.test.tsx'));

/** Every colour token the theme defines, read from index.css so the two cannot disagree. */
const TOKENS = new Set(
  [...css.matchAll(/--color-([a-z0-9-]+):/g)].map((m) => m[1]),
).add('transparent').add('current').add('inherit').add('white').add('black');

/** Utilities that share a colour prefix but are not colours. */
const NOT_COLOURS: Record<string, Set<string>> = {
  text: new Set(['left', 'right', 'center', 'justify', 'start', 'end', 'xs', 'sm', 'base', 'lg',
    'xl', '2xl', '3xl', '4xl', 'wrap', 'nowrap', 'balance', 'pretty', 'ellipsis', 'clip']),
  bg: new Set(['clip', 'fixed', 'local', 'scroll', 'cover', 'contain', 'center', 'no-repeat',
    'repeat', 'none']),
  border: new Set(['t', 'b', 'l', 'r', 'x', 'y', 's', 'e', 'collapse', 'separate', 'dashed',
    'dotted', 'solid', 'double', 'none', 'hidden']),
  ring: new Set(['inset', 'offset']),
};

function offenders(pattern: RegExp, except: (path: string) => boolean = () => false): string[] {
  const found: string[] = [];
  for (const [path, source] of files) {
    if (except(path)) continue;
    code(source).split('\n').forEach((line, index) => {
      if (pattern.test(line)) found.push(`${path}:${index + 1}`);
    });
  }
  return found;
}

describe('design guards', () => {
  it('names only colour tokens the theme defines', () => {
    const unknown: string[] = [];
    const utility = /(?<![\w-])(text|bg|border|ring)-([a-z][a-z0-9-]*)(?:\/\d+)?(?![\w-])/g;
    for (const [path, source] of files) {
      for (const [, prefix, name] of code(source).matchAll(utility)) {
        if (NOT_COLOURS[prefix]?.has(name)) continue;
        if (!TOKENS.has(name)) unknown.push(`${path}: ${prefix}-${name}`);
      }
    }
    expect([...new Set(unknown)]).toEqual([]);
  });

  it('never dims text with an alpha colour', () => {
    // `text-status-success-fg/80` at 11px was ~4.49:1 -- a pass on the page, a fail in the
    // arithmetic. Alpha on TEXT hides a contrast failure; a solid token cannot.
    expect(offenders(/(?<![\w-])text-[a-z-]+\/\d+/)).toEqual([]);
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run (from `frontend/`): `npx vitest run src/test/designGuards.test.ts`
Expected: FAIL. The first test lists at least `bg-muted` (6 sites) and `text-fg-muted` (3 sites); the second lists `Receipt.tsx` lines 70 and 79. If `bg-control` shows up it is because Step 3 has not run yet — that is expected too.

- [ ] **Step 3: Add the tokens**

In `frontend/src/index.css`, inside `:root` after `--selected: oklch(0.955 0 0);` add:

```css
  /* The filled ground of a control -- Espresso's #f3f3f3. A GROUND, never a boundary: at
     1.11:1 against paper it cannot identify a field on its own (WCAG 1.4.11), which is why
     text fields keep their --input border on top of it. Buttons need no boundary: their
     label identifies them. */
  --control: oklch(0.965 0 0);
  --control-hover: oklch(0.935 0 0);
  /* A table header row and an empty-state ground: one step above the page. */
  --band: oklch(0.982 0 0);
  --shadow-raise: 0 0 0 1px oklch(0 0 0 / 6%), 0 1px 2px oklch(0 0 0 / 10%);
```

Change `--radius: 0.375rem;` to `--radius: 0.5rem;`.

Inside `.dark` after `--selected: oklch(1 0 0 / 8%);` add:

```css
  --control: oklch(1 0 0 / 7%);
  --control-hover: oklch(1 0 0 / 11%);
  --band: oklch(0.165 0 0);
  --shadow-raise: 0 0 0 1px oklch(1 0 0 / 8%), 0 1px 2px oklch(0 0 0 / 40%);
```

Inside `@theme inline`, after `--color-selected: var(--selected);` add:

```css
  --color-control: var(--control);
  --color-control-hover: var(--control-hover);
  --color-band: var(--band);
  --shadow-raise: var(--shadow-raise);
```

In `@layer base`, inside the `body` rule, after `font-variant-numeric: tabular-nums;` add:

```css
    /* Espresso's body tracking. Headings keep their own tracking-tight. */
    letter-spacing: 0.01em;
```

(0.01em, not Espresso's 0.02em: Espresso sets it on InterVariable at weight 420, and this console loads static Inter at 400, where 0.02em reads loose at 14px.)

- [ ] **Step 4: Fix the dead classes and the alpha text**

Each is a one-token replacement with the Edit tool:
- `UnderwritingQueuePage.tsx:128` and `:135`: `bg-muted` → `bg-control` (Task 8 turns these into `Badge`).
- `OnboardAgentPage.tsx:155`, `PartyDetailPage.tsx:220`, `IssuePolicyPage.tsx:490`, `OpenUnderwritingCasePage.tsx:230`: `bg-muted` → `bg-surface-muted`.
- `IssuePolicyPage.tsx:543`, `PolicyDetailPage.tsx:179`, `PolicyDetailPage.tsx:188`: `text-fg-muted` → `text-muted-foreground`.
- `Receipt.tsx:70` and `:79`: `text-status-success-fg/80` → `text-status-success-fg`.
- `button.tsx:16`: `text-white hover:opacity-90 dark:text-status-danger-bg` → `text-background hover:opacity-90` (`--background` is paper in light and near-black in dark, which is exactly the pair the danger ground needs in each theme).

- [ ] **Step 5: Run the guard and the whole unit suite**

Run: `npx vitest run src/test/designGuards.test.ts ; npm run typecheck ; npm run lint ; npx vitest run`
Expected: guard PASS; typecheck, lint and the full vitest suite green. If the colour rule lists a name that IS a real non-colour utility (it will print `prefix-name`), add it to `NOT_COLOURS` — do not add a colour name there.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/index.css frontend/src/test/designGuards.test.ts frontend/src/components frontend/src/features
git commit -m "fix(console): nine classes named tokens that do not exist; add control, band and raise tokens, and make drift a failing test"
```

---

### Task 3: One inline error block

**Files:**
- Create: `frontend/src/components/InlineError.tsx`, `frontend/src/components/InlineError.test.tsx`
- Modify (20 sites): `features/billing/FieldReceiptsPage.tsx:322`, `features/claims/ClaimAssessmentPanel.tsx:132`, `features/claims/ClaimReopenPanel.tsx:68`, `features/claims/ClaimSettlementPanel.tsx:349`, `features/claims/RegisterClaimPage.tsx:522`, `features/distribution/CommissionPlanPanel.tsx:237`, `features/distribution/OnboardAgentPage.tsx:170`, `features/party/EditClientPage.tsx:155,202`, `features/party/OnboardCustomerPage.tsx:123,205`, `features/payments/EftExecutionPage.tsx:322`, `features/policies/BeneficiariesPanel.tsx:252`, `features/policies/IssuePolicyPage.tsx:634`, `features/products/CreateProductPage.tsx:148`, `features/products/PublishVersionForm.tsx:810`, `features/reinsurance/CreateTreatyPage.tsx:169`, `features/underwriting/DecisionPanel.tsx:180`, `features/underwriting/OpenUnderwritingCasePage.tsx:408`, `features/underwriting/UnderwritingCaseDetailPage.tsx:334`

**Interfaces:**
- Produces: `InlineError({ error, children?, className? })` where `error: Pick<ApiError, 'title' | 'detail' | 'traceId'>`. Renders `role="alert"`.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/components/InlineError.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { InlineError } from './InlineError';

describe('InlineError', () => {
  it('announces the detail, falling back to the title', () => {
    const { rerender } = render(
      <InlineError error={{ title: 'Conflict', detail: 'The policy has lapsed.', traceId: null }} />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('The policy has lapsed.');
    rerender(<InlineError error={{ title: 'Conflict', detail: null, traceId: null }} />);
    expect(screen.getByRole('alert')).toHaveTextContent('Conflict');
  });

  it('prints the trace id at full strength, labelled, and selectable', () => {
    render(<InlineError error={{ title: 'Conflict', detail: null, traceId: 'abc123' }} />);
    const trace = screen.getByText('abc123');
    expect(trace).toHaveClass('select-all');
    expect(trace.className).not.toMatch(/opacity|text-\[1[01]px\]/);
    expect(screen.getByRole('alert')).toHaveTextContent('Reference abc123');
  });

  it('renders extra lines a caller needs below the message', () => {
    render(
      <InlineError error={{ title: 'Conflict', detail: null, traceId: null }}>
        <p>Nothing was saved.</p>
      </InlineError>,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('Nothing was saved.');
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `npx vitest run src/components/InlineError.test.tsx`
Expected: FAIL — cannot resolve `./InlineError`.

- [ ] **Step 3: Implement**

Create `frontend/src/components/InlineError.tsx`:

```tsx
import type { ReactNode } from 'react';
import type { ApiError } from '@/lib/apiError';
import { cn } from '@/lib/cn';

/**
 * A rejected action, shown where the action was taken.
 *
 * Hand-copied into twenty call sites before this file, each printing the trace id at 10px
 * and 80% opacity on the danger ground -- the one string a support call needs to close was
 * the least legible text on the screen. It is labelled, full strength and `select-all` here,
 * so a staff member can read it out or copy it in one click.
 *
 * `ErrorPanel` is the other error surface and stays separate: it replaces a whole region
 * whose READ failed. This one sits beside a form whose WRITE failed, and the form stays.
 */
export function InlineError({
  error,
  children,
  className,
}: {
  error: Pick<ApiError, 'title' | 'detail' | 'traceId'>;
  children?: ReactNode;
  className?: string;
}) {
  return (
    <div
      role="alert"
      className={cn('rounded-md bg-status-danger-bg px-3 py-2 text-sm text-status-danger-fg', className)}
    >
      <p>{error.detail ?? error.title}</p>
      {children}
      {error.traceId && (
        <p className="mt-1 text-xs">
          Reference <span className="font-mono select-all">{error.traceId}</span>
        </p>
      )}
    </div>
  );
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `npx vitest run src/components/InlineError.test.tsx`
Expected: PASS, 3 tests.

- [ ] **Step 5: Migrate the 20 sites**

The common shape (this is `BeneficiariesPanel.tsx:246-255`):

```tsx
        <div
          role="alert"
          className="mt-2 rounded-md bg-status-danger-bg px-3 py-2 text-xs text-status-danger-fg"
        >
          {saving.error.detail ?? saving.error.title}
          {saving.error.traceId && (
            <span className="ml-2 font-mono text-[10px] opacity-80">({saving.error.traceId})</span>
          )}
        </div>
```

becomes

```tsx
        <InlineError error={saving.error} className="mt-2" />
```

with `import { InlineError } from '@/components/InlineError';` added to the file's imports (alphabetical within the `@/components` group, as the files already order them).

At each site: open the file at the listed line, read the whole block, and keep **only the outer layout classes** (`mt-*`, `mb-*`, `mx-*`) in `className`. Any other content in the block — a second sentence, a list of field errors, a link — moves into `children` unchanged. Three sites are known to differ and must be read, not pattern-matched: `FieldReceiptsPage.tsx:322`, `EftExecutionPage.tsx:322` and `UnderwritingCaseDetailPage.tsx:334` (multi-line), and `CreateProductPage.tsx:148` / `DecisionPanel.tsx:180` (`mt-1.5 … select-all`, the trace on its own line — `InlineError` already does this).

- [ ] **Step 6: Check no copy survives, and that e2e never matched the old parentheses**

Run: `npx vitest run src/test/designGuards.test.ts` and grep:
`grep -rn "opacity-80" frontend/src/features frontend/src/components --include=*.tsx` → expected: no trace-id matches.
`grep -rn "traceId" frontend/e2e` → read each hit. If a spec asserts the old `(traceId)` form, update it to `Reference <id>`.

- [ ] **Step 7: Full unit run and commit**

Run: `npm run typecheck ; npm run lint ; npx vitest run`
Expected: green.

```bash
git add frontend/src
git commit -m "fix(console): one inline error block, and a trace id someone can actually read out"
```

---

### Task 4: A 12px floor

**Files:**
- Modify: every `.tsx` under `frontend/src` containing `text-[10px]` or `text-[11px]` (63 files at the time of writing)
- Modify: `frontend/src/test/designGuards.test.ts`

- [ ] **Step 1: Add the failing rule**

Append inside the `describe` in `designGuards.test.ts`:

```ts
  it('sets no text below 12px', () => {
    // Espresso's floor, and WCAG's practical one: 188 runs sat at 10-11px, carrying trace
    // ids, field notes, stat captions and the em dash that means "absent".
    expect(offenders(/text-\[(?:[0-9]|1[01])(?:\.\d+)?px\]/)).toEqual([]);
  });
```

- [ ] **Step 2: Run it to verify it fails**

Run: `npx vitest run src/test/designGuards.test.ts`
Expected: FAIL with ~160 `path:line` entries.

- [ ] **Step 3: Replace — a pure pattern replacement, nothing else**

Run from `frontend/`:

```bash
node -e "const fs=require('fs'),p=require('path');const walk=d=>fs.readdirSync(d,{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(p.join(d,e.name)):e.name.endsWith('.tsx')?[p.join(d,e.name)]:[]);let n=0;for(const f of walk('src')){const s=fs.readFileSync(f,'utf8');const t=s.replace(/text-\[1[01]px\]/g,'text-xs');if(t!==s){fs.writeFileSync(f,t);n++;}}console.log(n,'files')"
```

Expected: prints roughly `63 files`.

- [ ] **Step 4: Resolve collisions by hand**

The replacement can leave two sizes on one element (`text-xs … text-xs`) or put `text-xs` beside a `text-sm` that used to be the larger sibling. Run:
`grep -rnE "text-xs[^\"']*text-xs|text-xs[^\"']*text-sm|text-sm[^\"']*text-xs" src --include=*.tsx`
and remove the duplicate at each hit with the Edit tool. Where a caption and its neighbour are now both 12px and the hierarchy relied on the difference (the nav group captions in `AppShell.tsx` are uppercase and keep their `tracking-wide`, so they still read as captions), keep the caption in `text-subtle-foreground` and the neighbour in `text-muted-foreground` — hierarchy by ink, not by a smaller size.

- [ ] **Step 5: Verify**

Run: `npx vitest run src/test/designGuards.test.ts ; npm run typecheck ; npm run lint ; npx vitest run`
Expected: green. If any unit test asserted a `text-[11px]` class, update it to `text-xs`.

- [ ] **Step 6: Commit**

```bash
git add frontend/src
git commit -m "fix(console): nothing on the console is set below 12px"
```

---

### Task 5: Acronyms survive a status label

**Files:**
- Modify: `frontend/src/lib/status.ts:273-277`
- Test: `frontend/src/lib/status.test.ts` (create if absent; if present, append)

- [ ] **Step 1: Write the failing test**

```ts
import { describe, expect, it } from 'vitest';
import { humanizeStatus } from './status';

describe('humanizeStatus', () => {
  it('sentence-cases an ordinary literal', () => {
    expect(humanizeStatus('SETTLEMENT_REQUESTED')).toBe('Settlement requested');
  });

  it('keeps the acronyms staff read as acronyms', () => {
    expect(humanizeStatus('WITHIN_FCL')).toBe('Within FCL');
    expect(humanizeStatus('ABOVE_FCL')).toBe('Above FCL');
    expect(humanizeStatus('KYC_PENDING')).toBe('KYC pending');
    expect(humanizeStatus('EFT_REJECTED')).toBe('EFT rejected');
  });

  it('does not uppercase an acronym hiding inside a longer word', () => {
    expect(humanizeStatus('GLOBAL')).toBe('Global');
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `npx vitest run src/lib/status.test.ts`
Expected: FAIL — `Within fcl` received.

- [ ] **Step 3: Implement**

Replace the function at `status.ts:273-277` with:

```ts
/**
 * Acronyms a staff member reads as acronyms. "Within fcl" is what the generic lower-casing
 * made of WITHIN_FCL on the member roll -- a free cover limit is never written in lower case
 * anywhere in the business. Whole words only, so GLOBAL does not become "GLobal".
 */
const ACRONYMS = new Set(['FCL', 'KYC', 'EFT', 'GL', 'TIRA', 'ID', 'NIDA', 'TIN', 'SMS']);

/** Turn `SETTLEMENT_REQUESTED` into `Settlement requested` for display. */
export function humanizeStatus(value: string): string {
  const words = value.split('_').map((word, index) => {
    if (ACRONYMS.has(word)) return word;
    const lower = word.toLowerCase();
    return index === 0 ? lower.charAt(0).toUpperCase() + lower.slice(1) : lower;
  });
  return words.join(' ');
}
```

- [ ] **Step 4: Run to verify it passes, then the whole suite**

Run: `npx vitest run src/lib/status.test.ts ; npx vitest run`
Expected: PASS. Then grep e2e for any lower-case acronym the old function produced:
`grep -rniE "within fcl|above fcl|kyc pending|eft " frontend/e2e` — update any hit to the new casing.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/lib frontend/e2e
git commit -m "fix(console): a free cover limit is FCL, not fcl"
```

---

### Task 6: Button — filled secondary, a real pending state, touch sizes

**Files:**
- Modify: `frontend/src/components/ui/button.tsx`
- Test: `frontend/src/components/ui/button.test.tsx` (create)

**Interfaces:**
- Produces: `Button` props gain `pending?: boolean`. Variants: `primary | secondary | outline | ghost | danger`; **default becomes `secondary`**. Sizes `sm | md | icon`, each lifted to 44px under `pointer-coarse:`.
- `pending` sets `disabled`, `aria-busy="true"` and prepends a spinner. Plans 2–5 replace `disabled={x.status === 'loading'}` with `pending={x.status === 'loading'}` screen by screen; this task changes no call site.

- [ ] **Step 1: Write the failing test**

```tsx
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Button } from './button';

describe('Button', () => {
  it('defaults to the filled secondary style', () => {
    render(<Button>Save</Button>);
    expect(screen.getByRole('button', { name: 'Save' })).toHaveClass('bg-control');
  });

  it('is disabled and busy while pending, and keeps its name', () => {
    render(<Button pending>Save</Button>);
    const button = screen.getByRole('button', { name: 'Save' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('aria-busy', 'true');
  });

  it('stays disabled when disabled even if not pending', () => {
    render(<Button disabled>Save</Button>);
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Save' })).not.toHaveAttribute('aria-busy');
  });

  it('reaches 44px on a coarse pointer at every size', () => {
    render(
      <>
        <Button size="sm">A</Button>
        <Button size="md">B</Button>
        <Button size="icon" aria-label="C" />
      </>,
    );
    expect(screen.getByRole('button', { name: 'A' })).toHaveClass('pointer-coarse:h-11');
    expect(screen.getByRole('button', { name: 'B' })).toHaveClass('pointer-coarse:h-11');
    expect(screen.getByRole('button', { name: 'C' })).toHaveClass('pointer-coarse:size-11');
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `npx vitest run src/components/ui/button.test.tsx`
Expected: FAIL — no `bg-control`, no `aria-busy`, no coarse classes.

- [ ] **Step 3: Implement**

Replace `frontend/src/components/ui/button.tsx` with:

```tsx
import { Slot } from '@radix-ui/react-slot';
import { cva, type VariantProps } from 'class-variance-authority';
import { Loader2 } from 'lucide-react';
import type { ComponentProps } from 'react';
import { cn } from '@/lib/cn';

const button = cva(
  'inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-md text-sm font-medium transition-colors disabled:pointer-events-none disabled:opacity-50 aria-busy:cursor-progress [&_svg]:size-4 [&_svg]:shrink-0',
  {
    variants: {
      variant: {
        primary: 'bg-accent text-accent-foreground hover:opacity-90',
        // Espresso's default button: a filled ground, no border. A button needs no drawn
        // boundary to be identified -- its label does that -- so the 3:1 rule that keeps a
        // border on every text field does not reach it.
        secondary: 'bg-control text-foreground hover:bg-control-hover',
        outline: 'border border-border-strong bg-surface hover:bg-hover',
        ghost: 'hover:bg-hover',
        // Reserved for genuinely destructive actions, so the colour keeps meaning.
        danger: 'bg-status-danger-fg text-background hover:opacity-90',
      },
      size: {
        // Desktop density stays; a finger gets 44px. `pointer-coarse` rather than a width
        // breakpoint, because a touch laptop at 1440px needs the target as much as a phone.
        sm: 'h-8 px-2.5 text-[13px] pointer-coarse:h-11',
        md: 'h-9 px-3.5 pointer-coarse:h-11',
        icon: 'size-8 pointer-coarse:size-11',
      },
    },
    defaultVariants: { variant: 'secondary', size: 'md' },
  },
);

export type ButtonProps = ComponentProps<'button'> &
  VariantProps<typeof button> & {
    asChild?: boolean;
    /**
     * The request this button started is in flight. Disables it -- the submit guard every
     * mutating form on the console relies on, now in one place -- and says so to assistive
     * tech, where a bare `disabled` only says "unavailable".
     */
    pending?: boolean;
  };

export function Button({
  className,
  variant,
  size,
  asChild = false,
  pending = false,
  disabled,
  children,
  ...props
}: ButtonProps) {
  if (asChild) {
    return (
      <Slot className={cn(button({ variant, size }), className)} {...props}>
        {children}
      </Slot>
    );
  }
  return (
    <button
      className={cn(button({ variant, size }), className)}
      disabled={disabled || pending}
      aria-busy={pending || undefined}
      {...props}
    >
      {pending && <Loader2 className="animate-spin" aria-hidden />}
      {children}
    </button>
  );
}
```

- [ ] **Step 4: Run to verify it passes, then everything**

Run: `npx vitest run src/components/ui/button.test.tsx ; npm run typecheck ; npm run lint ; npx vitest run`
Expected: green. If a test asserted `border-border` on a default button, it was asserting the old default — update it to `bg-control`.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/ui
git commit -m "feat(console): a filled secondary button by default, a pending state that says it is busy, and 44px on touch"
```

---

### Task 7: Filled fields that keep their border, and a real checkbox

**Files:**
- Modify: `frontend/src/components/ui/input.tsx:34-51`
- Create: `frontend/src/components/ui/checkbox.tsx`, `frontend/src/components/ui/checkbox.test.tsx`
- Modify: `features/claims/ClaimAssessmentPanel.tsx:123-126`, `features/claims/RegisterClaimPage.tsx:459-462`, `features/policies/BeneficiaryRow.tsx:121-124`
- Modify: `frontend/src/test/designGuards.test.ts`

**Interfaces:**
- Produces: `Checkbox(props: Omit<ComponentProps<'input'>, 'type'>)` and `CheckboxField(props & { label: ReactNode })`. Both forward `ref` (React 19 ref-as-prop), so `{...register('x')}` works unchanged.

- [ ] **Step 1: Write the failing tests**

Create `frontend/src/components/ui/checkbox.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createRef } from 'react';
import { describe, expect, it } from 'vitest';
import { CheckboxField } from './checkbox';

describe('CheckboxField', () => {
  it('is named by its label and toggles from a click on the words', async () => {
    render(<CheckboxField label="Flag for fraud review" />);
    const box = screen.getByRole('checkbox', { name: 'Flag for fraud review' });
    await userEvent.click(screen.getByText('Flag for fraud review'));
    expect(box).toBeChecked();
  });

  it('forwards its ref, so react-hook-form register() still works', () => {
    const ref = createRef<HTMLInputElement>();
    render(<CheckboxField label="Permanent" ref={ref} />);
    expect(ref.current).toBeInstanceOf(HTMLInputElement);
  });

  it('gives a finger 44px to hit', () => {
    render(<CheckboxField label="Revocable" />);
    expect(screen.getByText('Revocable').closest('label')).toHaveClass('pointer-coarse:min-h-11');
  });
});
```

Append to `designGuards.test.ts` inside the `describe`:

```ts
  it('draws every checkbox with the Checkbox primitive', () => {
    expect(
      offenders(/type="checkbox"/, (path) => path.endsWith('/components/ui/checkbox.tsx')),
    ).toEqual([]);
  });
```

- [ ] **Step 2: Run to verify they fail**

Run: `npx vitest run src/components/ui/checkbox.test.tsx src/test/designGuards.test.ts`
Expected: FAIL — module missing; guard lists the three feature files.

- [ ] **Step 3: Implement the checkbox**

Create `frontend/src/components/ui/checkbox.tsx`:

```tsx
import type { ComponentProps, ReactNode } from 'react';
import { cn } from '@/lib/cn';

export type CheckboxProps = Omit<ComponentProps<'input'>, 'type'>;

/**
 * A native checkbox, themed rather than replaced.
 *
 * Native on purpose: it keeps keyboard, form and react-hook-form behaviour for free, and
 * `accent-color` paints the checked state in ink in both themes. The three it replaces were
 * bare OS defaults at ~13px -- one of them the fraud flag on a claim assessment.
 */
export function Checkbox({ className, ...props }: CheckboxProps) {
  return (
    <input
      type="checkbox"
      className={cn(
        'size-4 shrink-0 cursor-pointer rounded-sm accent-[var(--color-accent)] disabled:cursor-not-allowed disabled:opacity-50',
        className,
      )}
      {...props}
    />
  );
}

/** A checkbox and the words that name it, as one 44px target on touch. */
export function CheckboxField({
  label,
  className,
  ...props
}: CheckboxProps & { label: ReactNode }) {
  return (
    <label
      className={cn(
        'inline-flex min-h-8 cursor-pointer items-center gap-2 text-sm text-foreground pointer-coarse:min-h-11',
        className,
      )}
    >
      <Checkbox {...props} />
      {label}
    </label>
  );
}
```

- [ ] **Step 4: Migrate the three sites**

`ClaimAssessmentPanel.tsx:123-126`:

```tsx
        <label className="flex items-center gap-1.5 text-xs text-muted-foreground">
          <input type="checkbox" {...register('fraudIndicator')} />
          Flag for fraud review
        </label>
```
→
```tsx
        <CheckboxField label="Flag for fraud review" {...register('fraudIndicator')} />
```

`RegisterClaimPage.tsx:459-462` → `<CheckboxField label="Permanent" {...register('details.permanent')} />`

`BeneficiaryRow.tsx:121-124` → `<CheckboxField label="Revocable" {...revocableField} />` (keep the comment above it; it is still true).

Add `import { CheckboxField } from '@/components/ui/checkbox';` to each file.

- [ ] **Step 5: Fill the fields, keep the border**

In `input.tsx`, in the `field` cva base string, change `'w-full rounded-md border border-input bg-surface transition-colors '` to `'w-full rounded-md border border-input bg-control transition-colors focus-visible:bg-surface '`, and change the two sizes to:

```ts
        md: 'h-9 px-2.5 text-sm pointer-coarse:h-11',
        sm: 'h-8 px-2 text-xs pointer-coarse:h-11',
```

Replace the comment above the cva with:

```ts
  // Espresso's filled ground WITH this console's 3:1 --input border. Espresso drops the
  // border; its #f3f3f3 ground is 1.11:1 against paper and cannot identify a field alone
  // (WCAG 1.4.11). The field turns to paper on keyboard focus so the ink ring reads on it.
```

- [ ] **Step 6: Verify**

Run: `npx vitest run ; npm run typecheck ; npm run lint`
Expected: green.

- [ ] **Step 7: e2e for the three migrated forms**

Run: `npx playwright test e2e/staff-claims-adjudication.spec.ts e2e/staff-claims.spec.ts e2e/staff-beneficiaries.spec.ts --project=staff`
Expected: PASS — the checkboxes keep their accessible names.

- [ ] **Step 8: Commit**

```bash
git add frontend/src
git commit -m "feat(console): filled fields that keep their border, and a checkbox that is not an OS default"
```

---

### Task 8: One pill anatomy

**Files:**
- Create: `frontend/src/components/ui/badge.tsx`, `frontend/src/components/ui/badge.test.tsx`
- Modify: `frontend/src/components/FilterChip.tsx`, `features/underwriting/UnderwritingQueuePage.tsx:127-137`, `frontend/src/components/AppShell.tsx` (nav count span)

**Interfaces:**
- Produces: `Badge({ children, className? })` — neutral only. Status colour stays exclusively in `StatusBadge` (the Stamp Rule: colour reports state).

- [ ] **Step 1: Failing test**

```tsx
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Badge } from './badge';

describe('Badge', () => {
  it('is a neutral pill at the 12px floor', () => {
    render(<Badge>Group scheme</Badge>);
    const pill = screen.getByText('Group scheme');
    expect(pill).toHaveClass('rounded-full', 'bg-control', 'text-xs');
    expect(pill.className).not.toMatch(/status-/);
  });
});
```

Run: `npx vitest run src/components/ui/badge.test.tsx` → FAIL (module missing).

- [ ] **Step 2: Implement**

```tsx
import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';

/**
 * A neutral tag -- "Group scheme", "Evidence · POL-…", a count beside a nav item.
 *
 * Deliberately colourless and deliberately not StatusBadge: a tag says what KIND of thing a
 * row is, never what STATE it is in, and the Stamp Rule keeps colour for state. Same shape
 * and size as StatusBadge so the two sit on one baseline in a row.
 */
export function Badge({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <span
      className={cn(
        'inline-flex items-center rounded-full bg-control px-2 py-0.5 text-xs font-medium whitespace-nowrap text-muted-foreground',
        className,
      )}
    >
      {children}
    </span>
  );
}
```

Run the test → PASS.

- [ ] **Step 3: Migrate**

`UnderwritingQueuePage.tsx:127-137`: replace both `<span className="rounded bg-control px-1.5 py-0.5 text-xs font-medium text-muted-foreground">…</span>` with `<Badge>…</Badge>`, keeping their text and the comment between them. Add `import { Badge } from '@/components/ui/badge';`.

`AppShell.tsx`, the nav count: replace

```tsx
                        <span
                          className="shrink-0 rounded-full bg-selected px-1.5 text-xs font-medium text-muted-foreground tabular-nums"
```

with `<Badge className="shrink-0 px-1.5 py-0 tabular-nums">` and its closing `</span>` with `</Badge>`. The `title` attribute must move to a wrapping element, because `Badge` takes no arbitrary props: wrap as `<span className="shrink-0" title={badges[item.badge]!.title}><Badge …>…</Badge></span>`, keeping the `sr-only` child inside the Badge.

`FilterChip.tsx`: change the class list to

```tsx
        'min-h-8 rounded-full px-3 text-[13px] transition-colors pointer-coarse:min-h-11',
        mono && 'font-mono',
        active ? 'bg-accent text-accent-foreground' : 'bg-control hover:bg-control-hover',
```

(An active filter is now ink-filled, not a faint ring: Espresso's selected filter pill, and the only way an active chip reads at a glance beside three inactive ones. `aria-pressed` still carries the state for assistive tech.)

- [ ] **Step 4: Verify**

Run: `npx vitest run ; npm run typecheck ; npm run lint`
Then: `npx playwright test e2e/staff-policies.spec.ts e2e/staff-group-schemes.spec.ts --project=staff` (both use filter chips).
Expected: green.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(console): one neutral pill, and filter chips a finger can hit"
```

---

### Task 9: A sticky page bar, a banded table, and a rail that pins only when it fits

**Files:**
- Modify: `frontend/src/components/PageHeader.tsx`, `frontend/src/components/DataTable.tsx:57-62,76-80`, `frontend/src/components/DetailLayout.tsx`, `frontend/src/components/Panel.tsx`
- Create: `frontend/src/components/railPin.ts`, `frontend/src/components/railPin.test.ts`, `frontend/src/components/PageHeader.test.tsx`

**Interfaces:**
- `PageHeader` gains optional `breadcrumb?: { label: string; to: string }[]` and `status?: ReactNode`; it becomes `sticky top-0` and publishes its height as the CSS variable `--pagebar-h` on `document.documentElement`. Every existing call site keeps working unchanged.
- `Panel` gains optional `id?: string` (an anchor target for `SectionNav`, with scroll margin below the sticky bar).
- `shouldPin(railHeight: number, viewportHeight: number, offset: number): boolean` in `railPin.ts`.

- [ ] **Step 1: Failing tests**

`frontend/src/components/railPin.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { shouldPin } from './railPin';

describe('shouldPin', () => {
  it('pins a rail that fits under the bar with room to spare', () => {
    expect(shouldPin(500, 900, 120)).toBe(true);
  });
  it('does not pin a rail taller than the space below the bar', () => {
    // Pinned, its last rows would be permanently out of reach -- the reason the rail grew its
    // own scrollbar, and the third scrollbar on the screen is what this replaces.
    expect(shouldPin(800, 900, 120)).toBe(false);
  });
  it('treats an unmeasured rail as not pinnable', () => {
    expect(shouldPin(0, 900, 120)).toBe(false);
  });
});
```

`frontend/src/components/PageHeader.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { PageHeader } from './PageHeader';

describe('PageHeader', () => {
  it('keeps the title as the one h1, and renders a breadcrumb trail as navigation', () => {
    render(
      <MemoryRouter>
        <PageHeader
          title="POL-123"
          breadcrumb={[{ label: 'Policies', to: '/staff/policies' }]}
          status={<span>Active</span>}
        />
      </MemoryRouter>,
    );
    expect(screen.getByRole('heading', { level: 1, name: 'POL-123' })).toBeInTheDocument();
    const trail = screen.getByRole('navigation', { name: 'Breadcrumb' });
    expect(trail).toContainElement(screen.getByRole('link', { name: 'Policies' }));
    expect(screen.getByText('Active')).toBeInTheDocument();
  });

  it('is sticky', () => {
    const { container } = render(<MemoryRouter><PageHeader title="Policies" /></MemoryRouter>);
    expect(container.firstElementChild).toHaveClass('sticky', 'top-0');
  });
});
```

Run: `npx vitest run src/components/railPin.test.ts src/components/PageHeader.test.tsx` → FAIL.

- [ ] **Step 2: Implement `railPin.ts`**

```ts
/**
 * Whether the record rail can be pinned beside the work without hiding any of it.
 *
 * Space below the page bar, less a 2rem margin top and bottom. A rail that fits is pinned,
 * so an assessor keeps the claimant in view while writing; one that does not scrolls with
 * the page, instead of growing the nested scrollbar it used to.
 */
export function shouldPin(railHeight: number, viewportHeight: number, offset: number): boolean {
  if (railHeight <= 0) return false;
  return railHeight <= viewportHeight - offset - 32;
}
```

- [ ] **Step 3: Implement `PageHeader.tsx`**

Replace the component (keep the file's leading comment and the `count` doc comment verbatim) with:

```tsx
import { ChevronRight } from 'lucide-react';
import { useEffect, useRef, type ReactNode } from 'react';
import { Link } from 'react-router-dom';

export function PageHeader({
  title,
  description,
  count,
  actions,
  breadcrumb,
  status,
}: {
  title: ReactNode;
  description?: ReactNode;
  count?: ReactNode;
  actions?: ReactNode;
  /** Where this page sits, oldest first. The current page is the title, never a crumb. */
  breadcrumb?: { label: string; to: string }[];
  /** A StatusBadge beside the title -- Espresso's "Active" / "Not saved" pill. */
  status?: ReactNode;
}) {
  const bar = useRef<HTMLDivElement>(null);

  // Publishes the bar's height so the sticky things below it (tabs, the section bar, the
  // record rail) stop UNDER it rather than behind it. A variable rather than a fixed offset
  // because the bar's height depends on whether a description wraps.
  useEffect(() => {
    const node = bar.current;
    if (!node) return;
    const publish = () =>
      document.documentElement.style.setProperty('--pagebar-h', `${node.offsetHeight}px`);
    publish();
    const observer = new ResizeObserver(publish);
    observer.observe(node);
    return () => {
      observer.disconnect();
      document.documentElement.style.removeProperty('--pagebar-h');
    };
  }, []);

  return (
    <div
      ref={bar}
      className="sticky top-0 z-20 flex flex-wrap items-start justify-between gap-4 border-b border-border bg-background px-6 pt-4 pb-3"
    >
      <div className="min-w-0">
        {breadcrumb && breadcrumb.length > 0 && (
          <nav aria-label="Breadcrumb" className="mb-1">
            <ol className="flex flex-wrap items-center gap-1 text-xs text-muted-foreground">
              {breadcrumb.map((crumb) => (
                <li key={crumb.to} className="flex items-center gap-1">
                  <Link to={crumb.to} className="rounded-sm hover:text-foreground hover:underline">
                    {crumb.label}
                  </Link>
                  <ChevronRight className="size-3" aria-hidden />
                </li>
              ))}
            </ol>
          </nav>
        )}
        <div className="flex flex-wrap items-center gap-2">
          <h1 className="text-xl font-semibold tracking-tight">{title}</h1>
          {status}
        </div>
        {description && <p className="mt-1 text-sm text-muted-foreground">{description}</p>}
        {count && <p className="mt-1 text-xs text-muted-foreground">{count}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}
```

Pages add `pt-5` or `mt-5` spacing below the bar in plans 2–5 where they need it; `DetailLayout` gets it in Step 5.

- [ ] **Step 4: Band the table**

In `DataTable.tsx`, the header row `<tr className="border-b border-border">` → `<tr className="border-b border-border bg-band">`. The header cell classes keep `text-muted-foreground` (7:1 on the band). No other change: plans 2–5 bold the first column per screen, because on some screens the first column is a date, not a name.

- [ ] **Step 5: `DetailLayout` pins only when it fits**

Replace the component body with:

```tsx
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { shouldPin } from './railPin';

export function DetailLayout({ record, children }: { record?: ReactNode; children: ReactNode }) {
  const rail = useRef<HTMLDivElement>(null);
  const [pinned, setPinned] = useState(false);

  useEffect(() => {
    const node = rail.current;
    if (!node) return;
    const measure = () => {
      const offset =
        Number.parseFloat(
          getComputedStyle(document.documentElement).getPropertyValue('--pagebar-h'),
        ) || 0;
      setPinned(shouldPin(node.offsetHeight, window.innerHeight, offset));
    };
    const observer = new ResizeObserver(measure);
    observer.observe(node);
    window.addEventListener('resize', measure);
    return () => {
      observer.disconnect();
      window.removeEventListener('resize', measure);
    };
  }, []);

  return (
    <div className="grid gap-5 px-6 pt-5 pb-8 lg:grid-cols-[minmax(0,1fr)_320px]">
      <div className="min-w-0 space-y-5">{children}</div>
      {record && (
        <div
          ref={rail}
          className={cn(
            'space-y-5 lg:self-start',
            pinned && 'lg:sticky lg:top-[calc(var(--pagebar-h,0px)+1rem)]',
          )}
        >
          {record}
        </div>
      )}
    </div>
  );
}
```

`setPinned` is called from the observer and resize callbacks, never synchronously in the effect body, which is what this console's lint rule requires. Rewrite the file's leading comment: keep the WHAT-GOES-WHERE list verbatim, and replace the paragraph about the height cap and `overflow-y-auto` with one explaining that the rail now pins only when it fits (see `railPin.ts`) and otherwise scrolls with the page, because the inner scroll was the third scrollbar on the screen.

- [ ] **Step 6: `Panel` takes an anchor id**

In `Panel.tsx` add `id?: string` to the props and put it on the `<section>`:

```tsx
    <section
      id={id}
      className={cn(
        'scroll-mt-[calc(var(--pagebar-h,0px)+3.5rem)] rounded-lg border bg-surface',
        emphasis ? 'border-border-strong' : 'border-border',
      )}
    >
```

(3.5rem clears the SectionNav bar that sits under the page bar on the claim page.)

- [ ] **Step 7: Verify**

Run: `npx vitest run ; npm run typecheck ; npm run lint`
Then the full e2e suite: `npx playwright test`
Expected: green. The sticky header changes no accessible name. If a spec clicked an element that is now covered by the sticky bar, Playwright reports "element intercepts pointer events" — fix by scrolling the target into view in that spec (`locator.scrollIntoViewIfNeeded()`), never by removing the stickiness.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/components
git commit -m "feat(console): the page bar stays on screen, tables get a header band, and the record rail stops growing its own scrollbar"
```

---

### Task 10: Record tabs and a section bar

**Files:**
- Create: `frontend/src/components/RecordTabs.tsx`, `frontend/src/components/RecordTabs.test.tsx`, `frontend/src/components/SectionNav.tsx`, `frontend/src/components/SectionNav.test.tsx`

**Interfaces:**
- `RecordTabs({ tabs: TabDef[]; label: string; param?: string })`, `TabDef = { value: string; label: string; count?: number; content: ReactNode }`. The active tab lives in the URL as `?tab=<value>` (absent means the first tab). Inactive tabs are unmounted, so their data loads on first open. Used by plans 2 (policy) and 5 (party).
- `SectionNav({ sections: { id: string; label: string }[]; label: string })`: a sticky row of in-page anchor links to `Panel id`s. Used by plan 3 (claim).

- [ ] **Step 1: Failing tests**

`RecordTabs.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { RecordTabs } from './RecordTabs';

const TABS = [
  { value: 'overview', label: 'Overview', content: <p>Overview body</p> },
  { value: 'billing', label: 'Billing', count: 12, content: <p>Billing body</p> },
];

function Where() {
  const location = useLocation();
  return <output data-testid="where">{location.search}</output>;
}

function renderAt(url: string) {
  render(
    <MemoryRouter initialEntries={[url]}>
      <RecordTabs tabs={TABS} label="Policy sections" />
      <Where />
    </MemoryRouter>,
  );
}

describe('RecordTabs', () => {
  it('opens on the first tab with no parameter, and mounts only that tab', () => {
    renderAt('/p');
    expect(screen.getByRole('tab', { name: 'Overview' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByText('Overview body')).toBeInTheDocument();
    expect(screen.queryByText('Billing body')).not.toBeInTheDocument();
  });

  it('opens the tab the URL names', () => {
    renderAt('/p?tab=billing');
    expect(screen.getByText('Billing body')).toBeInTheDocument();
  });

  it('falls back to the first tab for a name it does not know', () => {
    renderAt('/p?tab=nonsense');
    expect(screen.getByText('Overview body')).toBeInTheDocument();
  });

  it('writes the chosen tab into the URL, and removes it for the first tab', async () => {
    renderAt('/p?x=1');
    await userEvent.click(screen.getByRole('tab', { name: /Billing/ }));
    expect(screen.getByTestId('where')).toHaveTextContent('?x=1&tab=billing');
    await userEvent.click(screen.getByRole('tab', { name: 'Overview' }));
    expect(screen.getByTestId('where')).toHaveTextContent('?x=1');
  });

  it('says what a count counts', () => {
    renderAt('/p');
    expect(screen.getByRole('tab', { name: 'Billing, 12 items' })).toBeInTheDocument();
  });
});
```

`SectionNav.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { SectionNav } from './SectionNav';

describe('SectionNav', () => {
  it('is a named navigation of in-page links', () => {
    render(
      <SectionNav
        label="Claim sections"
        sections={[
          { id: 'claim-decision', label: 'Decision' },
          { id: 'claim-evidence', label: 'Evidence' },
        ]}
      />,
    );
    const nav = screen.getByRole('navigation', { name: 'Claim sections' });
    expect(nav).toHaveClass('sticky');
    expect(screen.getByRole('link', { name: 'Evidence' })).toHaveAttribute('href', '#claim-evidence');
  });
});
```

Run: `npx vitest run src/components/RecordTabs.test.tsx src/components/SectionNav.test.tsx` → FAIL.

- [ ] **Step 2: Implement `RecordTabs.tsx`**

```tsx
import * as TabsPrimitive from '@radix-ui/react-tabs';
import type { ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';

export interface TabDef {
  value: string;
  label: string;
  /** Only a count the page ALREADY HOLDS. Never fetch to fill a tab label. */
  count?: number;
  content: ReactNode;
}

/**
 * The sections of a record page, one at a time.
 *
 * For records with many independent registers -- a policy's beneficiaries, invoices, loans,
 * messages and cessions are separate jobs done by separate people -- where one long stack
 * made the fourth panel a scroll away and the header actions scroll off. Not for a claim:
 * an assessor reads the evidence WHILE writing findings, so the claim page keeps one scroll
 * and a SectionNav.
 *
 * The tab is in the URL, so Back works, a tab can be linked to, and a test can open one
 * directly. The first tab is the one the page exists to act on, and carries no parameter.
 * Inactive tabs are unmounted (Radix's default), so a tab's data loads when it is opened.
 */
export function RecordTabs({
  tabs,
  label,
  param = 'tab',
}: {
  tabs: TabDef[];
  label: string;
  param?: string;
}) {
  const [params, setParams] = useSearchParams();
  const first = tabs[0]?.value ?? '';
  const requested = params.get(param);
  const active = tabs.some((tab) => tab.value === requested) ? (requested as string) : first;

  return (
    <TabsPrimitive.Root
      value={active}
      onValueChange={(value) => {
        setParams((current) => {
          const next = new URLSearchParams(current);
          if (value === first) next.delete(param);
          else next.set(param, value);
          return next;
        });
      }}
    >
      <TabsPrimitive.List
        aria-label={label}
        className="sticky top-[var(--pagebar-h,0px)] z-10 flex gap-5 overflow-x-auto border-b border-border bg-background px-6"
      >
        {tabs.map((tab) => (
          <TabsPrimitive.Trigger
            key={tab.value}
            value={tab.value}
            aria-label={tab.count === undefined ? undefined : `${tab.label}, ${tab.count} items`}
            className="-mb-px inline-flex min-h-10 shrink-0 items-center gap-1.5 border-b-2 border-transparent text-sm text-muted-foreground transition-colors hover:text-foreground data-[state=active]:border-foreground data-[state=active]:font-medium data-[state=active]:text-foreground pointer-coarse:min-h-11"
          >
            {tab.label}
            {tab.count !== undefined && (
              <span className="rounded-full bg-control px-1.5 text-xs tabular-nums" aria-hidden>
                {tab.count}
              </span>
            )}
          </TabsPrimitive.Trigger>
        ))}
      </TabsPrimitive.List>
      {tabs.map((tab) => (
        <TabsPrimitive.Content key={tab.value} value={tab.value} className="focus-visible:outline-none">
          {tab.content}
        </TabsPrimitive.Content>
      ))}
    </TabsPrimitive.Root>
  );
}
```

(Inactive tab text is `--muted-foreground`, about 7:1. Espresso's `text-light` is 4.17:1 and fails.)

- [ ] **Step 3: Implement `SectionNav.tsx`**

```tsx
/**
 * Jump links to the sections of a page that must stay one scroll.
 *
 * The claim page's answer to the policy page's tabs: an assessor needs the event details,
 * the evidence and their own findings on one surface, so nothing is hidden -- the bar only
 * makes the fourth section one click away instead of a long scroll. Targets are `Panel id`s,
 * which carry the scroll margin that clears this bar and the page bar above it.
 */
export function SectionNav({
  sections,
  label,
}: {
  sections: { id: string; label: string }[];
  label: string;
}) {
  return (
    <nav
      aria-label={label}
      className="sticky top-[var(--pagebar-h,0px)] z-10 flex gap-1 overflow-x-auto border-b border-border bg-background px-6 py-1.5"
    >
      {sections.map((section) => (
        <a
          key={section.id}
          href={`#${section.id}`}
          className="shrink-0 rounded-md px-2.5 py-1 text-sm text-muted-foreground transition-colors hover:bg-hover hover:text-foreground pointer-coarse:py-2.5"
        >
          {section.label}
        </a>
      ))}
    </nav>
  );
}
```

- [ ] **Step 4: Verify and commit**

Run: `npx vitest run src/components/RecordTabs.test.tsx src/components/SectionNav.test.tsx ; npm run typecheck ; npm run lint`
Expected: PASS.

```bash
git add frontend/src/components
git commit -m "feat(console): record tabs that live in the URL, and a section bar for pages that must stay one scroll"
```

---

### Task 11: The shell

**Files:**
- Modify: `frontend/src/components/AppShell.tsx`

**Interfaces:**
- Consumes: `Badge` (Task 8).
- Produces: `AppShell` holds `paletteOpen` state and renders a "Go to…" button with the accessible name `Go to a screen`. Task 12 mounts the palette on that state.

- [ ] **Step 1: Workspace header**

Replace the `<div className="min-w-0">…</div>` in the sidebar header with:

```tsx
          <div className="flex min-w-0 items-center gap-2.5">
            {/* A monogram in ink, not a logo: several insurers share this console, so the
                mark belongs to the platform and carries no tenant's colour. */}
            <span
              className="grid size-8 shrink-0 place-items-center rounded-md bg-accent text-xs font-semibold text-accent-foreground"
              aria-hidden
            >
              LP
            </span>
            <div className="min-w-0">
              <p className="truncate text-sm font-semibold tracking-tight">Life Platform</p>
              <p className="truncate text-xs text-muted-foreground">{config.label} console</p>
            </div>
          </div>
```

- [ ] **Step 2: The Go-to button**

Add `Search` to the lucide import. Add `const [paletteOpen, setPaletteOpen] = useState(false);` beside `navOpen`. Directly after the header `<div>` and before `<nav>`, insert:

```tsx
        <div className="shrink-0 px-2 pb-2">
          <button
            type="button"
            onClick={() => setPaletteOpen(true)}
            aria-label="Go to a screen"
            className="flex w-full items-center gap-2 rounded-md bg-control px-3 py-1.5 text-sm text-muted-foreground transition-colors hover:bg-control-hover hover:text-foreground pointer-coarse:min-h-11"
          >
            <Search className="size-4 shrink-0" aria-hidden />
            <span className="flex-1 text-left">Go to…</span>
            <kbd className="font-sans text-xs">Ctrl K</kbd>
          </button>
        </div>
```

Add the shortcut to the component (an event handler, so the lint rule against synchronous setState in an effect body is not engaged):

```tsx
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setPaletteOpen(true);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
```

Until Task 12 lands, `paletteOpen` is unused. Either run Tasks 11 and 12 back to back, or reference it once as `data-palette-open={paletteOpen || undefined}` on the root `<div>` so lint does not flag it, and remove that attribute in Task 12.

- [ ] **Step 3: Nav items: Espresso proportions, a raised active item**

Replace the `NavLink` className function with:

```tsx
                      className={({ isActive }) =>
                        cn(
                          'flex items-center gap-2.5 rounded-md px-3 py-2 text-sm transition-colors pointer-coarse:min-h-11',
                          // Raised AND weighted. Espresso marks the active item with a white
                          // pill on #f8f8f8, which is 1.06:1 -- the shadow carries it alone.
                          // Weight is the second signal that survives a dim screen.
                          isActive
                            ? 'bg-surface font-medium text-foreground shadow-raise'
                            : 'text-muted-foreground hover:bg-hover hover:text-foreground',
                        )
                      }
```

The group caption `<p>` keeps its classes (it is `text-xs` since Task 4).

- [ ] **Step 4: Verify**

Run: `npm run typecheck ; npm run lint ; npx vitest run`
Then: `npx playwright test e2e/staff-bank-transfers.spec.ts e2e/agents-my-profile.spec.ts`
Expected: green. Nav links keep their names.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/AppShell.tsx
git commit -m "feat(console): a workspace header, a raised active item, and a Go-to button"
```

---

### Task 12: Ctrl+K go-to palette

**Files:**
- Create: `frontend/src/components/CommandPalette.tsx`, `frontend/src/components/CommandPalette.test.tsx`, `frontend/src/components/jump.ts`, `frontend/src/components/jump.test.ts`
- Modify: `frontend/src/components/AppShell.tsx`

**Interfaces:**
- `resolveJump(query: string): string | null` returns a realm-relative path for an exact policy number (`POL-` followed by letters or digits, case-insensitive), otherwise `null`.
- `CommandPalette({ realm, groups, open, onOpenChange })`, where `groups` is the value `navFor(realm, identity)` already returns in AppShell.

- [ ] **Step 1: Failing tests**

`jump.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { resolveJump } from './jump';

describe('resolveJump', () => {
  it('opens an exact policy number, whatever the case and padding', () => {
    expect(resolveJump('  pol-7k2q9  ')).toBe('policies/POL-7K2Q9');
  });
  it('declines anything that is not an exact reference', () => {
    // No search endpoint exists, so the palette must not pretend a name is findable.
    expect(resolveJump('Juma')).toBeNull();
    expect(resolveJump('POL-')).toBeNull();
    expect(resolveJump('POL-12 34')).toBeNull();
  });
});
```

`CommandPalette.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { FileText, ScrollText } from 'lucide-react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import { CommandPalette } from './CommandPalette';

beforeAll(() => {
  // cmdk scrolls the active item into view; jsdom has no layout, so no scrollIntoView.
  Element.prototype.scrollIntoView = vi.fn();
});

const GROUPS = [
  {
    label: 'Policies & claims',
    items: [
      { group: 'policies-claims' as const, label: 'Policies', icon: FileText, to: 'policies' },
      { group: 'policies-claims' as const, label: 'Claims', icon: ScrollText, to: 'claims' },
    ],
  },
];

function Where() {
  return <output data-testid="where">{useLocation().pathname}</output>;
}

function renderPalette() {
  const onOpenChange = vi.fn();
  render(
    <MemoryRouter initialEntries={['/staff/policies']}>
      <Routes>
        <Route
          path="*"
          element={
            <>
              <CommandPalette realm="staff" groups={GROUPS} open onOpenChange={onOpenChange} />
              <Where />
            </>
          }
        />
      </Routes>
    </MemoryRouter>,
  );
  return { onOpenChange };
}

describe('CommandPalette', () => {
  it('lists the screens it was given, and goes to the one chosen', async () => {
    const { onOpenChange } = renderPalette();
    await userEvent.type(screen.getByRole('combobox'), 'clai');
    await userEvent.keyboard('{Enter}');
    expect(screen.getByTestId('where')).toHaveTextContent('/staff/claims');
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it('offers to open an exact policy number', async () => {
    renderPalette();
    await userEvent.type(screen.getByRole('combobox'), 'pol-7k2q9');
    await userEvent.click(screen.getByRole('option', { name: /Open policy POL-7K2Q9/ }));
    expect(screen.getByTestId('where')).toHaveTextContent('/staff/policies/POL-7K2Q9');
  });

  it('says plainly that it does not search records', async () => {
    renderPalette();
    await userEvent.type(screen.getByRole('combobox'), 'Juma Rajabu');
    expect(screen.getByText(/does not search names or records/)).toBeInTheDocument();
  });
});
```

Run: `npx vitest run src/components/jump.test.ts src/components/CommandPalette.test.tsx` → FAIL.

- [ ] **Step 2: Implement `jump.ts`**

```ts
/**
 * An exact reference the palette can open without a search endpoint.
 *
 * Only policy numbers: they are the one identifier on the platform whose shape alone says
 * what it is. Claims, parties and cases are bare UUIDs -- a pasted UUID could be any of
 * them, and guessing would land a staff member on the wrong record's 404.
 */
export function resolveJump(query: string): string | null {
  const reference = query.trim().toUpperCase();
  return /^POL-[A-Z0-9]+$/.test(reference) ? `policies/${reference}` : null;
}
```

- [ ] **Step 3: Implement `CommandPalette.tsx`**

```tsx
import * as Dialog from '@radix-ui/react-dialog';
import { Command } from 'cmdk';
import { FileText } from 'lucide-react';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { Realm } from '@/auth/realms';
import type { NavItem } from '@/screens';
import { resolveJump } from './jump';

/**
 * Ctrl+K: go to any screen this person can reach, or open a policy by its number.
 *
 * Built from the same `navFor` result as the sidebar, so the palette can never offer a
 * screen the role cannot see. It does NOT search records: there is no search endpoint, and
 * a box that matched only screen names while looking like a search would mislead.
 */
export function CommandPalette({
  realm,
  groups,
  open,
  onOpenChange,
}: {
  realm: Realm;
  groups: { label: string; items: NavItem[] }[];
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const jump = resolveJump(query);

  const go = (to: string) => {
    onOpenChange(false);
    setQuery('');
    navigate(`/${realm}/${to}`);
  };

  const item =
    'flex min-h-9 cursor-pointer items-center gap-2.5 rounded-md px-3 text-sm aria-selected:bg-selected pointer-coarse:min-h-11';

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-foreground/25" />
        <Dialog.Content
          aria-describedby={undefined}
          className="fixed top-[15vh] left-1/2 z-50 w-[min(36rem,calc(100vw-2rem))] -translate-x-1/2 overflow-hidden rounded-lg border border-border bg-surface shadow-raise"
        >
          <Dialog.Title className="sr-only">Go to</Dialog.Title>
          <Command label="Go to" loop>
            <Command.Input
              value={query}
              onValueChange={setQuery}
              placeholder="Go to a screen, or paste a policy number"
              className="h-11 w-full border-b border-border bg-transparent px-4 text-sm outline-none placeholder:text-muted-foreground"
            />
            <Command.List className="max-h-80 overflow-y-auto p-1 [&_[cmdk-group-heading]]:px-3 [&_[cmdk-group-heading]]:pt-2 [&_[cmdk-group-heading]]:pb-1 [&_[cmdk-group-heading]]:text-xs [&_[cmdk-group-heading]]:text-subtle-foreground">
              <Command.Empty className="px-3 py-6 text-center text-sm text-muted-foreground">
                No screen matches. This does not search names or records — paste a policy
                number to open one.
              </Command.Empty>
              {jump && (
                <Command.Group heading="Open">
                  <Command.Item
                    forceMount
                    value={`open ${query}`}
                    onSelect={() => go(jump)}
                    className={item}
                  >
                    <FileText className="size-4 shrink-0" aria-hidden />
                    Open policy {query.trim().toUpperCase()}
                  </Command.Item>
                </Command.Group>
              )}
              {groups.map((group) => (
                <Command.Group key={group.label} heading={group.label}>
                  {group.items.map((entry) => (
                    <Command.Item
                      key={entry.to}
                      // The label alone. Adding the group name would make "clai" match
                      // Policies too, via "Policies & claims". Labels are unique per realm.
                      value={entry.label}
                      onSelect={() => go(entry.to)}
                      className={item}
                    >
                      <entry.icon className="size-4 shrink-0" aria-hidden />
                      {entry.label}
                    </Command.Item>
                  ))}
                </Command.Group>
              ))}
            </Command.List>
          </Command>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
```

- [ ] **Step 4: Mount it in AppShell**

After `</aside>` in `AppShell.tsx`, add:

```tsx
      <CommandPalette realm={realm} groups={groups} open={paletteOpen} onOpenChange={setPaletteOpen} />
```

with `import { CommandPalette } from './CommandPalette';`. If Task 11 added `data-palette-open`, remove it.

- [ ] **Step 5: Verify**

Run: `npx vitest run src/components/jump.test.ts src/components/CommandPalette.test.tsx ; npm run typecheck ; npm run lint ; npx vitest run`
Expected: PASS. If the empty-state test fails because cmdk shows nothing for an unmatched query, check that `Command.Empty` renders inside `Command.List` (cmdk requires that).

- [ ] **Step 6: One e2e check through the real shell**

Append to `frontend/e2e/staff-policies.spec.ts`:

```ts
test('Ctrl+K goes to a screen', async ({ page }) => {
  await page.goto('/staff/policies');
  await expect(page.getByRole('heading', { name: 'Policies', exact: true })).toBeVisible({
    timeout: 30_000,
  });
  await page.keyboard.press('Control+K');
  await page.getByRole('combobox').fill('claims');
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/\/staff\/claims/);
});
```

Run: `npx playwright test e2e/staff-policies.spec.ts --project=staff` → PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/components frontend/e2e/staff-policies.spec.ts
git commit -m "feat(console): Ctrl+K goes to any screen the role can reach, or opens a policy by number"
```

---

### Task 13: Land on your own work

**Files:**
- Modify: `frontend/src/screens.tsx` (add `homeFor`), `frontend/src/App.tsx:33-36`, `frontend/src/screens.test.ts`
- Create: `frontend/src/components/RealmHome.tsx`
- Modify: `frontend/e2e/auth.setup.ts`, `auth-senior.setup.ts`, `auth-assessor.setup.ts`, `auth-manager.setup.ts`, `auth-finance.setup.ts`

**Interfaces:**
- `homeFor(realm: Realm, identity: TokenIdentity): string | null` returns a path relative to the realm root, which may carry a query string.

| Roles | Lands on |
|---|---|
| ADMIN (any others) | `policies` (unchanged) |
| UNDERWRITER / SENIOR_UNDERWRITER | `underwriting` |
| CLAIMS_MANAGER | `claims?status=SETTLEMENT_REQUESTED` |
| CLAIMS_ASSESSOR | `claims?status=REGISTERED` |
| FINANCE_OFFICER | `arrears` |
| anyone else, and every non-staff realm | `REALM_HOME[realm]` |

The claim statuses are the ones `navBadges.ts` already counts for the same two queues (lines 71 and 79), and `ClaimsPage` already reads `?status=` (line 42).

- [ ] **Step 1: Failing test**

Add `homeFor` to the existing `@/screens` import at the top of `screens.test.ts` (one import per module, because lint flags a duplicate), then append:

```ts
const as = (...roles: string[]) =>
  ({ roles: ['REALM_STAFF', ...roles] }) as unknown as Parameters<typeof homeFor>[1];

describe('homeFor', () => {
  it('lands each staff role on its own queue', () => {
    expect(homeFor('staff', as('UNDERWRITER'))).toBe('underwriting');
    expect(homeFor('staff', as('UNDERWRITER', 'SENIOR_UNDERWRITER'))).toBe('underwriting');
    expect(homeFor('staff', as('CLAIMS_ASSESSOR'))).toBe('claims?status=REGISTERED');
    expect(homeFor('staff', as('CLAIMS_MANAGER'))).toBe('claims?status=SETTLEMENT_REQUESTED');
    expect(homeFor('staff', as('FINANCE_OFFICER'))).toBe('arrears');
  });

  it('keeps an admin, and a role with no queue, on policies', () => {
    expect(homeFor('staff', as('ADMIN', 'UNDERWRITER', 'FINANCE_OFFICER'))).toBe('policies');
    expect(homeFor('staff', as('CUSTOMER_SERVICE_REP'))).toBe('policies');
  });

  it('leaves other realms on their fixed home', () => {
    expect(homeFor('agents', as())).toBe(REALM_HOME.agents);
  });

  it('only ever lands on a screen that exists', () => {
    const paths = new Set(SCREENS.staff.map((s) => s.path));
    for (const roles of [['UNDERWRITER'], ['CLAIMS_ASSESSOR'], ['CLAIMS_MANAGER'], ['FINANCE_OFFICER'], []]) {
      const home = homeFor('staff', as(...roles)) ?? '';
      expect(paths, home).toContain(home.split('?')[0]);
    }
  });
});
```

Run: `npx vitest run src/screens.test.ts` → FAIL (`homeFor` is not exported).

- [ ] **Step 2: Implement `homeFor`**

In `screens.tsx`, after `REALM_HOME`:

```tsx
/**
 * Where a signed-in person lands: their own queue, not a register of everyone's.
 *
 * Every staff member used to land on Policies, which is nobody's work -- a policy in force
 * is not a task. ADMIN keeps Policies because it holds every role, and no one queue is its
 * job. The claim statuses are the SAME filters the nav badges count, so the number beside
 * "Claims" is exactly the list they land on.
 */
export function homeFor(realm: Realm, identity: ReturnType<typeof readIdentity>): string | null {
  if (realm !== 'staff') return REALM_HOME[realm];
  const roles = staffRoles(identity);
  if (roles.ADMIN) return 'policies';
  if (roles.UNDERWRITER) return 'underwriting';
  if (roles.CLAIMS_MANAGER) return 'claims?status=SETTLEMENT_REQUESTED';
  if (roles.CLAIMS_ASSESSOR) return 'claims?status=REGISTERED';
  if (roles.FINANCE_OFFICER) return 'arrears';
  return REALM_HOME.staff;
}
```

`readIdentity` is currently imported as a type only (`type readIdentity`). `ReturnType<typeof readIdentity>` works with that import; leave it as it is.

- [ ] **Step 3: `RealmHome` and App**

Create `frontend/src/components/RealmHome.tsx`:

```tsx
import { useAuth } from 'react-oidc-context';
import { Navigate } from 'react-router-dom';
import { readIdentity } from '@/auth/claims';
import type { Realm } from '@/auth/realms';
import { homeFor } from '@/screens';

/** The realm index. It renders inside RequireAuth, so the token is already in hand. */
export function RealmHome({ realm }: { realm: Realm }) {
  const auth = useAuth();
  const home = homeFor(realm, readIdentity(auth.user?.access_token));
  return home ? <Navigate to={home} replace /> : null;
}
```

In `App.tsx`, change `{home && <Route index element={<Navigate to={home} replace />} />}` to `{home && <Route index element={<RealmHome realm={realm} />} />}`. Add `import { RealmHome } from '@/components/RealmHome';` and remove `Navigate` from the react-router import if nothing else uses it (the `*` route does, so it probably stays).

Before continuing, check for an import cycle: `RealmHome` imports `@/screens`, and `screens` does not import `RealmHome`.

- [ ] **Step 4: Unit verify**

Run: `npx vitest run src/screens.test.ts ; npm run typecheck ; npm run lint`
Expected: PASS.

- [ ] **Step 5: Update the sign-in setups**

In each setup, change only the landing assertion. It currently reads `await expect(page.getByRole('heading', { name: 'Policies' })).toBeVisible();`:
- `auth.setup.ts` (`staff.underwriter`): `{ name: 'Underwriting', exact: true }`
- `auth-senior.setup.ts` (`staff.senior`): `{ name: 'Underwriting', exact: true }`
- `auth-assessor.setup.ts` (`staff.assessor`): `{ name: 'Claims', exact: true }`
- `auth-manager.setup.ts` (`staff.manager`): `{ name: 'Claims', exact: true }`
- `auth-finance.setup.ts` (`staff.finance`): `{ name: 'Arrears', exact: true }`
- `auth-admin.setup.ts`: unchanged (Policies).

Use `exact: true`, because a substring match would also hit a heading like "Claims awaiting…".

Then find specs that navigate to the bare realm root and assume Policies:
`grep -rn "goto('/staff')" frontend/e2e/*.spec.ts`
The known hits are both in `staff-bank-transfers.spec.ts`. They click a nav link next, so they are unaffected. Any new hit must `goto('/staff/policies')` instead.

- [ ] **Step 6: Full e2e**

Run: `npx playwright test`
Expected: green. A setup failure means the landing heading did not match. Read the screenshot in `test-results/`.

- [ ] **Step 7: Commit**

```bash
git add frontend/src frontend/e2e
git commit -m "feat(console): each staff role lands on its own queue instead of the policy register"
```

---

### Task 14: Lazy routes

**Files:**
- Modify: `frontend/src/screens.tsx:24-63` (the page imports), `frontend/src/components/AppShell.tsx` (`<main>`)

- [ ] **Step 1: Record the before figure**

Run: `npm run build` and then `ls -la dist/assets/*.js`
Expected: one chunk of about 921 KB. Write the exact size down for the commit message.

- [ ] **Step 2: Convert the page imports**

In `screens.tsx`, replace every `import { XPage } from '@/features/…/XPage';` (lines 24–63, 40 imports) with a lazy declaration, keeping the same order:

```tsx
const AuditLogPage = lazy(() =>
  import('@/features/audit/AuditLogPage').then((m) => ({ default: m.AuditLogPage })),
);
```

The `.then` mapping is needed because every page is a named export. Add `lazy` to the `react` import: `import { lazy, type ReactNode } from 'react';`. `RedirectPreservingQuery` stays a static import, because it is a tiny router helper, not a page.

- [ ] **Step 3: Suspense in the shell**

In `AppShell.tsx`, wrap `{children}` inside `<main>`:

```tsx
          <Suspense fallback={<LoadingBlock />}>{children}</Suspense>
```

Add `Suspense` to the react import and `import { LoadingBlock } from './states';`.

- [ ] **Step 4: Verify**

Run: `npm run typecheck ; npm run lint ; npx vitest run ; npm run build ; ls dist/assets/*.js | wc -l ; ls -la dist/assets/index-*.js`
Expected: green, more than 30 JS chunks, and the entry chunk well under the before figure. If `react-refresh/only-export-components` complains about `screens.tsx`, it was already exporting non-components, so check whether the rule was already suppressed there before adding anything.

Then: `npx playwright test`
Expected: green. Specs wait on headings, which appear after the chunk loads.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "perf(console): each screen loads on first visit, so an agent no longer downloads finance"
```

Put the before and after entry-chunk sizes in the commit body.

---

### Task 15: DESIGN.md, the after figures, and the merge gate

**Files:**
- Modify: `DESIGN.md` (frontmatter and the Colors, Typography, Components sections), `frontend/e2e/a11y-baseline.json`

- [ ] **Step 1: Sync DESIGN.md frontmatter to the CSS**

- `colors`: set `subtle-ink` to `"oklch(0.55 0 0)"`. Add `input: "oklch(0.66 0 0)"`, `control: "oklch(0.965 0 0)"`, `control-hover: "oklch(0.935 0 0)"` and `band: "oklch(0.982 0 0)"`.
- `rounded`: set `sm: "0.375rem"`, `md: "0.5rem"`, `lg: "0.625rem"`.
- `typography`: set `micro`, `eyebrow` and `mono` to `fontSize: "0.75rem"`. The floor is 12px.
- `components`: `button-outline` becomes `button-secondary` with `backgroundColor: "{colors.control}"`. Keep `button-outline` with a `{colors.rule-strong}` border note. Add `pointerCoarseHeight: "44px"` to `button-primary`, `button-secondary`, `button-sm`, `button-icon` and `input-text`. Set `input-text` `backgroundColor: "{colors.control}"`. Set `nav-item` `padding: "8px 12px"`. Set `nav-item-active` `backgroundColor: "{colors.paper}"` and add `shadow: "raise"`.

In the prose, add a short "Espresso, adapted" paragraph under Overview. It says the structure (sticky page bar, sticky record tabs, banded table, filled controls, raised active item, 8px radius) comes from Frappe Espresso, and that its contrast values do not. List the four measured failures this console refuses: 1.11:1 fields, a 1.66:1 focus ring, 4.17:1 light text, and 2.85:1 checkbox borders.

- [ ] **Step 2: The after figures**

Run: `npx playwright test e2e/staff-a11y.spec.ts --project=staff`
Expected: PASS. Then re-record the baseline:
`$env:A11Y_RECORD='1'; npx playwright test e2e/staff-a11y.spec.ts --project=staff; Remove-Item Env:A11Y_RECORD`
Compare `git diff frontend/e2e/a11y-baseline.json`. Entries should have fallen or vanished, and **none may have appeared**. If one appeared, stop and fix it before recording.

Capture the after screenshots with `CAPTURE=1` (as in Task 1 Step 6) and put them next to `visual-before/` for review.

- [ ] **Step 3: Merge gate**

Run, stopping on the first failure:
`npm run typecheck ; npm run lint ; npx vitest run ; npm run build`
Then, with vitest finished and no Maven running: `npx playwright test`
Expected: all green. Report the vitest count, the Playwright count, the a11y baseline diff and the bundle sizes in the final message. Do not run the backend suite, because nothing in the backend changed.

- [ ] **Step 4: Commit**

```bash
git add DESIGN.md frontend/e2e/a11y-baseline.json
git commit -m "docs(console): DESIGN.md matches the CSS again, and the a11y baseline records what the foundation removed"
```

- [ ] **Step 5: Final whole-branch review**

Dispatch the final review of the whole branch before merging. On this project it has found real systemic bugs in the seams between correct tasks every time. Expect a fix cycle. Then merge `--no-ff` to `main` locally (the repo has no remote).
