# Role Dashboards (Phase 3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Draw the three charts phase 2's data makes honest, from one shared chart component rather than three hand-rolled SVGs.

**Architecture:** Frontend, plus one small server-side change in Task 2 (grouping accepted and loaded decisions, which the browser may not add). Every series already exists as a monthly aggregate from phase 2 (`decisionsByMonth`, `settlementsByMonth`, `receivablesAging`) or phase 1 (`monthlyPremiumCollections`), so no endpoint changes and no new arithmetic anywhere. Phase 1's `PremiumsChart` is generalised into a `BarChart` the four charts share.

**Tech Stack:** React + TypeScript, inline SVG (no charting library), Tailwind v4, Vitest + React Testing Library, Playwright.

**Depends on:** phase 1 and phase 2, both merged. **Do not start this plan before phase 2 is merged** — three of the four charts would have nothing to draw.

## Global Constraints

Phase 1's constraints all bind. The ones this phase lives or dies by:

- **Every point on a chart is a value a report or a module aggregate returned.** Nothing is summed, averaged, interpolated or extrapolated in the browser. A missing month is drawn as missing, never as zero and never as a line through the gap.
- **Series are ink and greys.** The six status hues stay reserved for state, so a chart can never make the Stamp Rule lie. Where two series must be told apart beyond value, they differ by fill, outline and legend — not by hue.
- **A direction is not a verdict.** No series, bar or delta is green or red.
- **The month in progress is an outline**, never a solid bar beside complete months.
- **Every chart carries a described image.** `role="img"` with an `aria-label` naming the series, the period and the latest real value — a chart nobody can see still has to say what it shows.
- **No charting library.** The CSP admits cdnjs, but a bar chart of twelve points does not earn 90KB and a second design system. Inline SVG, as `PremiumsChart` already does.
- **A chart is never the only way to read a figure.** Each sits under or beside the KPI card holding the same number, and links to the list or report behind it.

## What is being drawn, and from what

| Chart | Dashboard | Series | Source | Added by |
|---|---|---|---|---|
| Premiums collected | Finance | this year, last year | `monthlyPremiumCollections` | phase 1 — **re-pointed at the shared component**, not rebuilt |
| Decisions by outcome | Underwriting | accepted, declined (+ loaded, postponed) per month | `UnderwritingApi.decisionsByMonth` | phase 2 Task 2 |
| Benefits paid | Claims | settled amount per month, TZS | `ClaimsApi.settlementsByMonth` | phase 2 Task 4 |
| Receivables aging | Finance | five buckets, one period | `BillingApi.receivablesAging` | phase 2 Task 5 |

The first three are time series over months and share one component. The fourth is a **categorical** bar — five named buckets, not a timeline — and gets its own small component rather than being forced through a monthly API.

**Deliberately not charted**, and why, so nobody adds them later without a reason: policies in force, lapse rate, persistency and new business all have real reports with their own screens; a dashboard copy would be a second rendering of a number whose home is one click away, and would have to be kept in step with it.

## File Structure

- Create `frontend/src/components/charts/BarChart.tsx` — the shared monthly bar chart: one or two series, an outlined month-in-progress, a described image, a legend.
- Create `frontend/src/components/charts/BarChart.test.tsx`.
- Create `frontend/src/components/charts/BucketBars.tsx` + test — the categorical bar for aging.
- Modify `frontend/src/features/dashboard/PremiumsChart.tsx` — becomes a thin adapter over `BarChart`; its own tests stay green unchanged, which is the proof the refactor preserved behaviour.
- Create `frontend/src/features/dashboard/DecisionsChart.tsx` + test.
- Create `frontend/src/features/dashboard/BenefitsPaidChart.tsx` + test.
- Modify `frontend/src/features/dashboard/UnderwritingDashboardPanel.tsx`, `ClaimsDashboardPanel.tsx`, `FinanceDashboardPanel.tsx` and their tests.
- Modify `DESIGN.md` — one paragraph on the chart vocabulary.

---

### Task 1: The shared bar chart

Phase 1's `PremiumsChart` is the only chart in the console and was written for one job. Three more are coming, so the drawing moves out before it is copied.

**Files:**
- Create: `frontend/src/components/charts/BarChart.tsx`
- Test: `frontend/src/components/charts/BarChart.test.tsx`
- Modify: `frontend/src/features/dashboard/PremiumsChart.tsx`

**Interfaces:**
- Produces:

```ts
export interface Series {
  /** Shown in the legend and in the described image. */
  label: string;
  /** 'ink' is the subject; 'muted' is what it is compared against. Never a hue. */
  tone: 'ink' | 'muted';
  /** One entry per month present. A month absent from every series is drawn as a gap. */
  points: { month: string; value: number; display: string }[];
  /** The month drawn as an outline because it is still running. */
  partialMonth?: string;
}
export function BarChart({ series, months, description }: {
  series: Series[];
  /** Every month the x-axis shows, oldest first, as "YYYY-MM". The caller decides the window. */
  months: string[];
  description: string;
}): ReactElement;
```

`value` is for height only; `display` is the string shown in the tooltip and the described image, exactly as the server sent it. The component never formats a number and never parses `display`.

- [ ] **Step 1: Write the failing test**

```tsx
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { BarChart } from './BarChart';

const months = ['2026-08', '2026-09', '2026-10'];
const series = [{
  label: '2026', tone: 'ink' as const, partialMonth: '2026-10',
  points: [{ month: '2026-08', value: 49.1, display: 'TZS 49,100,000.00' },
    { month: '2026-09', value: 50.2, display: 'TZS 50,204,310.00' },
    { month: '2026-10', value: 22.6, display: 'TZS 22,600,000.00' }],
}];

describe('BarChart', () => {
  it('draws one bar per point and names each by its own display value', () => {
    render(<BarChart series={series} months={months} description="Premiums by month." />);

    expect(screen.getByTitle('2026, Sep 2026: TZS 50,204,310.00').closest('rect')).toBeInTheDocument();
  });

  /** A month still running is an outline: a solid half-month beside complete ones reads as a collapse. */
  it('outlines the month in progress', () => {
    render(<BarChart series={series} months={months} description="Premiums by month." />);

    expect(screen.getByTitle(/Oct 2026 to date/).closest('rect')).toHaveAttribute('data-partial', 'true');
    expect(screen.getByTitle(/Sep 2026:/).closest('rect')).not.toHaveAttribute('data-partial');
  });

  /** A month with no value is a GAP, not a zero -- the platform said nothing, so the chart says nothing. */
  it('leaves a month with no value empty', () => {
    render(<BarChart months={['2026-08', '2026-09']} description="x" series={[{ label: '2026', tone: 'ink',
      points: [{ month: '2026-09', value: 1, display: 'TZS 1.00' }] }]} />);

    expect(screen.queryByTitle(/Aug 2026/)).not.toBeInTheDocument();
  });

  it('describes itself, and labels its series', () => {
    render(<BarChart series={series} months={months} description="Premiums by month, 2026." />);

    expect(screen.getByRole('img', { name: 'Premiums by month, 2026.' })).toBeInTheDocument();
    expect(screen.getByText('2026')).toBeInTheDocument();
  });

  /** Two series sit side by side in each month, the compared one in grey -- never a second hue. */
  it('draws a second series beside the first', () => {
    render(<BarChart months={months} description="x" series={[series[0]!,
      { label: '2025', tone: 'muted', points: [{ month: '2026-09', value: 45, display: 'TZS 45,000,000.00' }] }]} />);

    expect(screen.getByTitle('2025, Sep 2026: TZS 45,000,000.00')).toBeInTheDocument();
    expect(screen.getByText('2025')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run (from `frontend/`): `npx vitest run src/components/charts/BarChart.test.tsx`
Expected: FAIL — `Failed to resolve import "./BarChart"`.

- [ ] **Step 3: Implement**

Lift the geometry from `frontend/src/features/dashboard/PremiumsChart.tsx` — read it first and keep its viewBox, slot and bar-width arithmetic, its `data-partial` attribute and its legend markup. What changes: the months come from the `months` prop instead of a hard-coded twelve, the series are a list instead of two named arrays, a bar's title is `` `${series.label}, ${monthName} ${year}${partial ? ' to date' : ''}: ${point.display}` ``, and nothing inside the component knows what a premium is.

Keep these exactly as phase 1 has them, because each is a decision already taken:
- `fill-foreground` for `tone: 'ink'`, `fill-border-strong` for `tone: 'muted'`, and `fill-none stroke-foreground stroke-[1.5]` with `data-partial` for the month in progress.
- Axis labels `text-xs` — the design guards refuse anything below 12px, inside a drawing or not.
- `role="img"` with the caller's `description` as the label.
- A legend of one swatch per series plus the "to date" outline when any series has a `partialMonth`.

- [ ] **Step 4: Re-point `PremiumsChart` at it**

`PremiumsChart` keeps its exported signature and its own test file unchanged — that is the proof this refactor preserved behaviour. Its body becomes: build `months` from the union of both years' points (twelve months of the current year, oldest first), map each `MonthPoint` to `{ month, value: Number(amount.amount), display: formatMoney(amount) }`, and render `BarChart`. The comment explaining that parsing to a number is *drawing, not arithmetic* moves with it.

Run: `npx vitest run src/features/dashboard/PremiumsChart.test.tsx src/components/charts/BarChart.test.tsx`
Expected: `PremiumsChart.test.tsx` 3 passed **with no edits to it**, `BarChart.test.tsx` 5 passed. If a `PremiumsChart` test needed changing, the refactor changed behaviour — fix the component, not the test.

- [ ] **Step 5: Commit**

```bash
git checkout main && git pull && git checkout -b role-dashboards-phase-3
git add frontend/src/components/charts frontend/src/features/dashboard/PremiumsChart.tsx backend/docs/superpowers/plans/2026-10-15-role-dashboards-phase-3.md
git commit -m "refactor(dashboard): one bar chart the dashboards share

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Decisions by outcome, on the underwriting dashboard

**Files:**
- Create: `frontend/src/features/dashboard/DecisionsChart.tsx`, `DecisionsChart.test.tsx`
- Modify: `frontend/src/features/dashboard/UnderwritingDashboardPanel.tsx`, `UnderwritingDashboardPanel.test.tsx`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/dashboard/application/UnderwritingDashboard.java` and its test — widen the month range

**Interfaces:**
- Consumes: `decisions: DecisionMonth[]` on `UnderwritingDashboardView` (phase 2 Task 6), where `DecisionMonth = { month, outcome, cases, averageDays }`.
- Produces: `DecisionsChart({ decisions, months }: { decisions: DecisionMonth[]; months: string[] })`.

- [ ] **Step 1: Widen the range the composer asks for**

Phase 2's `UnderwritingDashboard` asks `decisionsByMonth` for the last complete month only, which is all its card needed. The chart needs twelve. In `UnderwritingDashboard.build()`, change the range to the twelve complete months ending with the last complete one, and add to its test:

```java
    @Test
    void readsTwelveCompleteMonthsOfDecisionsForTheChart() {
        // ... existing stubs ...
        dashboard.build();

        verify(underwriting).decisionsByMonth(
            argThat(from -> from.equals(YearMonth.from(TODAY).minusMonths(12))),
            argThat(to -> to.equals(YearMonth.from(TODAY).minusMonths(1))));
    }
```

`UnderwritingDashboard.build()` takes no date today; give it `build(LocalDate today)` like the finance one, with the controller passing the civil date — and update its other tests and the controller together.

- [ ] **Step 2: Write the failing chart test**

```tsx
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { DecisionsChart } from './DecisionsChart';

const decisions = [
  { month: '2026-08', outcome: 'ACCEPTED' as const, cases: 40, averageDays: '3.1' },
  { month: '2026-08', outcome: 'DECLINED' as const, cases: 6, averageDays: '2.0' },
  { month: '2026-09', outcome: 'ACCEPTED' as const, cases: 42, averageDays: '3.4' },
  { month: '2026-09', outcome: 'LOADED' as const, cases: 5, averageDays: '4.0' },
];

describe('DecisionsChart', () => {
  /** Accepted and loaded are both acceptances; the chart shows what was taken on against what was refused. */
  it('draws acceptances against declines', () => {
    render(<DecisionsChart decisions={decisions} months={['2026-08', '2026-09']} />);

    expect(screen.getByTitle('Accepted, Sep 2026: 47 cases')).toBeInTheDocument();
    expect(screen.getByTitle('Declined, Aug 2026: 6 cases')).toBeInTheDocument();
  });

  it('says so when nothing has been decided', () => {
    render(<DecisionsChart decisions={[]} months={['2026-08', '2026-09']} />);

    expect(screen.getByText('No decisions in this period.')).toBeInTheDocument();
  });
});
```

Note what the first test asserts: **47**, from 42 accepted plus 5 loaded. That is a sum, and the browser may not do it — not even of counts, because "the browser never adds" is only a rule while it has no exceptions. So the grouping happens **server-side**, in `UnderwritingDashboard`: the view gains `List<DecisionSeriesPoint> decisionSeries` with `record DecisionSeriesPoint(String month, long accepted, long declined)`, where `accepted` is ACCEPTED + LOADED for that month, built from `decisionsByMonth`'s rows. The fixture in this test is therefore `decisionSeries`, not raw `DecisionMonth` rows:

```tsx
const series = [
  { month: '2026-08', accepted: 40, declined: 6 },
  { month: '2026-09', accepted: 47, declined: 0 },
];
```

and `DecisionsChart` takes `{ series, months }`. Add the record, the composer change, a composer unit test asserting 42 ACCEPTED + 5 LOADED → 47, and the `DecisionSeriesPoint` schema in `openapi-dashboard.yaml`, all in this task. POSTPONED stays out of both numbers.

- [ ] **Step 3: Implement and place it**

`DecisionsChart` maps `decisionSeries` into two `Series` — "Accepted" (`ink`) and "Declined" (`muted`) — with no arithmetic, and renders `BarChart` with a description naming the latest month's figures. POSTPONED is in neither series: it is not a decision that closed a case, and a third bar per month would make the chart unreadable for a value the card beside it already shows.

Place it in `UnderwritingDashboardPanel`'s work column, under the queue panel, inside a `Panel` titled "Decisions" with the subtitle "By month, accepted against declined. Postponements are not shown." and an "Open the queue" action linking to `/staff/underwriting?status=DECIDED`.

Run: `npx vitest run src/features/dashboard src/components/charts src/test/designGuards.test.ts && npm run typecheck && npm run lint`

- [ ] **Step 4: Commit**

```bash
git add frontend/src/features/dashboard backend/src/main/java/tz/co/nlolo/lifeplatform/dashboard backend/src/test/java/tz/co/nlolo/lifeplatform/dashboard
git commit -m "feat(dashboard): decisions by outcome on the underwriting dashboard

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Benefits paid, on the claims dashboard

**Files:**
- Create: `frontend/src/features/dashboard/BenefitsPaidChart.tsx`, `BenefitsPaidChart.test.tsx`
- Modify: `frontend/src/features/dashboard/ClaimsDashboardPanel.tsx`, `ClaimsDashboardPanel.test.tsx`
- Modify: `dashboard/application/ClaimsDashboard.java` and its test — twelve months

**Interfaces:**
- Consumes: `settlements: SettlementMonth[]` where `SettlementMonth = { month, currency, claims, totalApproved }`.
- Produces: `BenefitsPaidChart({ settlements, months }: { settlements: SettlementMonth[]; months: string[] })`.

- [ ] **Step 1: Write the failing test**

```tsx
describe('BenefitsPaidChart', () => {
  /** TZS only, and it says so: adding a USD settlement into a TZS bar would be a number in no currency. */
  it('draws the working currency and names any other', () => {
    render(<BenefitsPaidChart months={['2026-08', '2026-09']} settlements={[
      { month: '2026-08', currency: 'TZS', claims: 3, totalApproved: { amount: '12000000.00', currencyCode: 'TZS' } },
      { month: '2026-09', currency: 'TZS', claims: 2, totalApproved: { amount: '10000000.00', currencyCode: 'TZS' } },
      { month: '2026-09', currency: 'USD', claims: 1, totalApproved: { amount: '1000.00', currencyCode: 'USD' } }]} />);

    expect(screen.getByTitle('Benefits paid, Sep 2026: TZS 10,000,000.00')).toBeInTheDocument();
    expect(screen.getByText(/also paid in USD/)).toBeInTheDocument();
  });

  it('says so when nothing has been settled', () => {
    render(<BenefitsPaidChart settlements={[]} months={['2026-08']} />);

    expect(screen.getByText('No claims settled in this period.')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Implement and place it**

One `ink` series from the TZS rows, with a line under the chart naming any other currency present and its claim count. Place it in `ClaimsDashboardPanel`'s work column after "Where claims are", in a `Panel` titled "Benefits paid" with the subtitle "Approved amounts of claims settled each month, in TZS." and an action linking to `/staff/claims?status=SETTLED`.

Widen `ClaimsDashboard` to ask for twelve complete months, as Task 2 did for underwriting.

Run the same four checks as Task 2, then commit:

```bash
git add frontend/src/features/dashboard backend/src/main/java/tz/co/nlolo/lifeplatform/dashboard backend/src/test/java/tz/co/nlolo/lifeplatform/dashboard
git commit -m "feat(dashboard): benefits paid by month on the claims dashboard

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Receivables aging as bars

Phase 2 shipped the aging as a labelled list. The bars make the shape of the book visible at a glance; the list stays, because a bar cannot be read to the shilling.

**Files:**
- Create: `frontend/src/components/charts/BucketBars.tsx`, `BucketBars.test.tsx`
- Modify: `frontend/src/features/dashboard/ReceivablesAging.tsx` and its test

**Interfaces:**
- Produces: `BucketBars({ buckets, description }: { buckets: { label: string; value: number; display: string; count: number }[]; description: string })` — a horizontal bar per bucket, the label and the amount in text beside it.

- [ ] **Step 1: Write the failing test**

```tsx
describe('BucketBars', () => {
  it('draws a bar per bucket, including the empty ones', () => {
    render(<BucketBars description="Receivables by age." buckets={[
      { label: 'Not due', value: 100000, display: 'TZS 100,000.00', count: 1 },
      { label: '1–30 days', value: 0, display: 'TZS 0.00', count: 0 },
      { label: 'Over 90 days', value: 400000, display: 'TZS 400,000.00', count: 1 }]} />);

    expect(screen.getByRole('img', { name: 'Receivables by age.' })).toBeInTheDocument();
    // An empty bucket keeps its row: "nothing is this old" is the answer, not missing data.
    expect(screen.getByText('1–30 days')).toBeInTheDocument();
    expect(screen.getByText('TZS 0.00')).toBeInTheDocument();
  });

  /** Horizontal bars, so the longest is the oldest money and the eye goes to the bottom of the list. */
  it('scales each bar against the largest bucket', () => {
    const { container } = render(<BucketBars description="x" buckets={[
      { label: 'A', value: 50, display: '50', count: 1 }, { label: 'B', value: 100, display: '100', count: 1 }]} />);

    const widths = [...container.querySelectorAll('rect[data-bar]')].map((r) => Number(r.getAttribute('width')));
    expect(widths[1]).toBeCloseTo((widths[0] ?? 0) * 2, 1);
  });
});
```

- [ ] **Step 2: Implement and place it**

Horizontal bars in `fill-foreground`, one row per bucket, the amount right-aligned in text. No colour: an age is not a status, and a red "over 90" bar would be the Stamp Rule broken in the one place it is most tempting.

In `ReceivablesAging.tsx`, put the bars above the existing list. The list stays — the bars show shape, the list gives figures.

Run the four checks, then commit:

```bash
git add frontend/src/components/charts frontend/src/features/dashboard/ReceivablesAging.tsx frontend/src/features/dashboard/ReceivablesAging.test.tsx
git commit -m "feat(dashboard): receivables aging as bars beside its figures

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: The chart vocabulary, and the real stack

**Files:**
- Modify: `DESIGN.md`
- Create: `frontend/e2e/staff-dashboard-charts.spec.ts`

- [ ] **Step 1: Write the vocabulary into DESIGN.md**

Add to the Dashboard section:

```markdown
**Charts.** One `BarChart` serves every monthly chart (premiums, decisions, benefits paid) and one `BucketBars` the
categorical one (receivables aging). Rules, all of them already rules elsewhere in this file:

- Every point is a value a report or a module aggregate returned. Nothing is summed or averaged in the browser, and a
  month with no value is a gap, never a zero and never a line drawn through it.
- Series are ink and Rule Strong grey. A hue here would make colour mean something other than state (The Stamp Rule),
  and the one place it is most tempting — a red "over 90 days" — is exactly where it is refused.
- The month in progress is an ink outline, never a solid bar beside complete months.
- Every chart is a described image naming its series, its period and its latest real value, and sits beside the card
  or list holding the same number. A chart is never the only way to read a figure.
```

- [ ] **Step 2: An e2e that proves a chart drew**

```ts
import { expect, test } from '@playwright/test';

/** A chart is a described image: this asserts what a reader who cannot see it is told. */
test('the finance dashboard draws premiums by month', async ({ page }) => {
  await page.goto('/staff/dashboard?dashboard=finance');
  await expect(page.getByRole('img', { name: /Premiums collected by month/ })).toBeVisible();
});
```

Run: `npx playwright test e2e/staff-dashboard-charts.spec.ts`

- [ ] **Step 3: Look at them**

Open each dashboard at a desktop width and at ~400px, in both light and dark. Check: no bar escapes its box, no axis label collides, the month in progress is an outline on the premiums chart, and the aging bars scale against their largest bucket. Fix what that shows in one pass.

- [ ] **Step 4: Commit**

```bash
git add DESIGN.md frontend/e2e
git commit -m "docs(dashboard): the chart vocabulary

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

## Final whole-branch review

- [ ] `grep -rn "reduce\|\.map(Number" frontend/src/components/charts frontend/src/features/dashboard` — every hit is drawing geometry (a bar's height). None adds, averages or differences anything; the accepted+loaded grouping is server-side.
- [ ] No chart renders a zero for a month the server did not send.
- [ ] No series, bar or label uses a status hue. `grep -rn "status-" frontend/src/components/charts` returns nothing.
- [ ] Every chart has a `role="img"` and a label naming a real value.
- [ ] `PremiumsChart.test.tsx` passed throughout Task 1 without being edited.
- [ ] Merge as the repository does: `--no-ff` into `main`, push, push the branch as a backup.
