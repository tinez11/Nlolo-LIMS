# Console redesign, plan 3 of 5 — lane B: claims

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the claims surfaces the structure plan 2 gave the policy surfaces, using the *other* structural primitive — a section bar over one scroll, not tabs — and finish the job plan 1 started by actually wiring `SectionNav` to a page.

**Architecture:** `SectionNav` and `Panel id` were built in plan 1 and have **zero production call sites**. This lane adopts them, and adopting them exposes three defects plan 1 could not have seen: the bar publishes no height, so everything else that sticks stacks behind it; `Panel`'s scroll margin hardcodes `3.5rem` for a bar that is ~34px; and the bar has no notion of which section you are in. Fixing those is task 1. Everything after it is the claims lane consuming plan 1's primitives the way plan 2's policy lane did.

**Tech Stack:** unchanged from plan 1.

## Prerequisite

Plan 2 (`console-redesign-2-policies`) is merged (`4e1217d`). Branch `console-redesign-3-claims` from `main`.

## Global Constraints

Plan 1's Global Constraints all still bind; re-read that section. The ones this lane touches most:

- **No backend change, no OpenAPI change.** Money stays a string through `lib/money.ts`.
- **Preserve every role gate exactly.** `ClaimDetailPage` gates five panels on `staffRoles(identity)` and on claim status. A staff.underwriter seeing none of them is correct behaviour, not a bug, and `staff-role-gates.spec.ts` asserts it.
- **The contestability copy is load-bearing** and was written deliberately, including the separate sentence for scheme members. Move it, never reword it.
- **e2e locates by accessible name.** Six specs drive these screens: `staff-claims`, `staff-claims-adjudication`, `staff-claim-evidence`, `staff-claim-policy-chooser`, `staff-credit-life-claim`, `staff-role-gates`. Grep before renaming anything, and budget the spec updates into the task that causes them.
- **Never run vitest while Playwright is running**, and never edit `frontend/src` mid-run — the dev server hot-reloads under the tests.
- **`--no-deps` reuses expired Keycloak cookies.** Re-run with deps before believing a failure.
- **Seven specs are red for a database reason, not a code one.** A party stuck `REJECTED` breaks the KYC round trip and every "VERIFIED party" picker. The user is resetting the database themselves. Do not diagnose those as regressions.

## Why a section bar and not tabs

Settled in the proposal and restated here because it is the one decision this lane must not drift from: **an assessor reads the evidence while writing the findings.** Tabs would put the death certificate behind a click from the form that cites it. The policy record gets tabs because its registers are separate jobs done by separate people; the claim record stays one scroll and the bar only makes the fifth section one click away instead of a long drag.

## File map

| File | Responsibility | Task |
|---|---|---|
| `components/SectionNav.tsx` | publishes its height, marks the current section | 1 |
| `components/sectionSpy.ts` *(new)* | pure "which section am I in" function | 1 |
| `components/Panel.tsx` | scroll margin reads the real bar height | 1 |
| `components/DetailLayout.tsx` | pinned rail clears the section bar too | 1 |
| `components/railPin.ts` | unchanged logic, new caller-supplied offset | 1 |
| `features/claims/ClaimDetailPage.tsx` | sections array, breadcrumb, status slot | 1, 2 |
| `components/ConfirmAct.tsx` | stops renaming its own confirm button | 3 |
| `features/claims/ClaimAssessmentPanel.tsx`, `ClaimReopenPanel.tsx` | `Button pending` | 3 |
| `features/claims/EvidencePanel.tsx` | dropzone reachable and announced | 4 |
| `features/claims/RegisterClaimPage.tsx` | one long form becomes three groups | 5 |
| `features/claims/ClaimsPage.tsx` | register parity with `PoliciesPage` | 6 |
| `e2e/staff-a11y.spec.ts` | the claim record joins the sweep | 7 |

---

### Task 1: The section bar lands, and the sticky stack makes room for it

**Files:**
- Create: `frontend/src/components/sectionSpy.ts`
- Create: `frontend/src/components/sectionSpy.test.ts`
- Modify: `frontend/src/components/SectionNav.tsx`
- Modify: `frontend/src/components/SectionNav.test.tsx`
- Modify: `frontend/src/components/Panel.tsx`
- Modify: `frontend/src/components/DetailLayout.tsx`
- Modify: `frontend/src/features/claims/ClaimDetailPage.tsx`

**Interfaces:**
- Produces: `--sectionbar-h` on `document.documentElement`, and `activeSection(tops, scrollTop, offset, atBottom): string | null`.
- Consumes: `Panel id`, `PageHeader`'s `--pagebar-h`, `shouldPin` from `railPin.ts`.

- [ ] **Step 1: Write the failing test for the spy**

`frontend/src/components/sectionSpy.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { activeSection } from './sectionSpy';

const TOPS = [
  { id: 'assess', top: 0 },
  { id: 'evidence', top: 800 },
  { id: 'payment', top: 1600 },
];

describe('activeSection', () => {
  it('is the first section before any scrolling', () => {
    expect(activeSection(TOPS, 0, 100, false)).toBe('assess');
  });

  it('is the section whose top has passed under the bars', () => {
    expect(activeSection(TOPS, 750, 100, false)).toBe('evidence');
  });

  it('does not advance until the next top clears the bars', () => {
    // 650 + 100 = 750, which is still short of evidence's 800.
    expect(activeSection(TOPS, 650, 100, false)).toBe('assess');
  });

  it('is the last section at the bottom of the page, however short it is', () => {
    // The real bug this argument exists for: a short last section never reaches
    // the top of the viewport, so without this the bar can never mark it.
    expect(activeSection(TOPS, 900, 100, true)).toBe('payment');
  });

  it('is null when the page has no sections', () => {
    expect(activeSection([], 0, 100, false)).toBeNull();
  });
});
```

- [ ] **Step 2: Run it and watch it fail**

```
cd frontend && npx vitest run src/components/sectionSpy.test.ts
```

Expected: FAIL, `Failed to resolve import "./sectionSpy"`.

- [ ] **Step 3: Write the spy**

`frontend/src/components/sectionSpy.ts`:

```ts
/**
 * Which section the reader is looking at: the last one whose top has passed under the
 * sticky bars.
 *
 * A pure function rather than an IntersectionObserver callback, for the same reason
 * `railPin.ts` is one -- the interesting behaviour is a rule about numbers, and a rule about
 * numbers can be tested without a layout engine. jsdom reports every offset as 0, so an
 * observer-based version would have no test at all.
 *
 * @param tops each section's document-space top, in document order
 * @param scrollTop the window's current scroll position
 * @param offset how much is covered by sticky furniture -- the page bar plus this bar
 * @param atBottom whether the page is scrolled as far as it goes. A last section shorter
 *   than the viewport never reaches the top of it, so without this it could never be marked,
 *   and the bar would say "Evidence" while the reader looked at Payment.
 */
export function activeSection(
  tops: { id: string; top: number }[],
  scrollTop: number,
  offset: number,
  atBottom: boolean,
): string | null {
  if (tops.length === 0) return null;
  if (atBottom) return tops[tops.length - 1]!.id;

  let active = tops[0]!.id;
  for (const section of tops) {
    // +1 absorbs the sub-pixel difference between a fractional offsetTop and an integer
    // scroll position, which otherwise leaves a section one pixel short of its own anchor.
    if (section.top - offset <= scrollTop + 1) active = section.id;
  }
  return active;
}
```

- [ ] **Step 4: Run it and watch it pass**

```
cd frontend && npx vitest run src/components/sectionSpy.test.ts
```

Expected: PASS, 5 tests.

- [ ] **Step 5: Make `SectionNav` publish its height and mark the current section**

Replace `frontend/src/components/SectionNav.tsx` entirely:

```tsx
import { useEffect, useRef, useState } from 'react';
import { cn } from '@/lib/cn';
import { activeSection } from './sectionSpy';

/**
 * Jump links to the sections of a page that must stay one scroll.
 *
 * The claim page's answer to the policy page's tabs: an assessor needs the event details, the
 * evidence and their own findings on one surface, so nothing is hidden -- the bar only makes
 * the fifth section one click away instead of a long scroll. Targets are `Panel id`s, which
 * carry the scroll margin that clears this bar and the page bar above it.
 *
 * It publishes its own height as `--sectionbar-h`, exactly as `PageHeader` publishes
 * `--pagebar-h` and for the same reason: two things now stick to the top of the viewport, and
 * everything below them -- the panels' scroll margin, the pinned record rail -- has to stop
 * under BOTH. Until this bar had a call site, `Panel` guessed 3.5rem for it and was wrong by
 * 22px, which nothing noticed because nothing rendered it.
 */
export function SectionNav({
  sections,
  label,
}: {
  sections: { id: string; label: string }[];
  label: string;
}) {
  const bar = useRef<HTMLElement>(null);
  const [current, setCurrent] = useState<string | null>(sections[0]?.id ?? null);

  useEffect(() => {
    const node = bar.current;
    if (!node) return;
    const publish = () =>
      document.documentElement.style.setProperty('--sectionbar-h', `${node.offsetHeight}px`);
    publish();
    const observer = new ResizeObserver(publish);
    observer.observe(node);
    return () => {
      observer.disconnect();
      document.documentElement.style.removeProperty('--sectionbar-h');
    };
  }, []);

  useEffect(() => {
    const ids = sections.map((section) => section.id);
    let frame = 0;
    const measure = () => {
      frame = 0;
      const tops = ids
        .map((id) => ({ id, node: document.getElementById(id) }))
        .filter((entry): entry is { id: string; node: HTMLElement } => entry.node !== null)
        .map((entry) => ({ id: entry.id, top: entry.node.getBoundingClientRect().top + window.scrollY }));
      const offset = (bar.current?.offsetHeight ?? 0) + barTop();
      const atBottom = window.scrollY + window.innerHeight >= document.body.scrollHeight - 2;
      setCurrent(activeSection(tops, window.scrollY, offset, atBottom));
    };
    // Coalesced to one measurement per frame: scroll fires far more often than the answer
    // can change, and this reads layout, which forces a reflow every time it runs.
    const onScroll = () => {
      if (frame === 0) frame = requestAnimationFrame(measure);
    };
    measure();
    window.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', onScroll, { passive: true });
    return () => {
      if (frame !== 0) cancelAnimationFrame(frame);
      window.removeEventListener('scroll', onScroll);
      window.removeEventListener('resize', onScroll);
    };
  }, [sections]);

  return (
    <nav
      ref={bar}
      aria-label={label}
      className="sticky top-[var(--pagebar-h,0px)] z-10 flex gap-1 overflow-x-auto border-b border-border bg-background px-6 py-1.5"
    >
      {sections.map((section) => (
        <a
          key={section.id}
          href={`#${section.id}`}
          // aria-current, not aria-selected: these are links to places on this page, not the
          // options of a widget. A screen reader says "current page" on the one you are in,
          // which is the same thing the weight change says to everybody else.
          aria-current={section.id === current ? true : undefined}
          className={cn(
            'shrink-0 rounded-md px-2.5 py-1 text-sm transition-colors hover:bg-hover hover:text-foreground pointer-coarse:py-2.5',
            section.id === current
              ? 'bg-control font-medium text-foreground'
              : 'text-muted-foreground',
          )}
        >
          {section.label}
        </a>
      ))}
    </nav>
  );
}

/** The page bar's height, read from the custom property `PageHeader` publishes. */
function barTop(): number {
  return (
    Number.parseFloat(
      getComputedStyle(document.documentElement).getPropertyValue('--pagebar-h'),
    ) || 0
  );
}
```

- [ ] **Step 6: Point `Panel`'s scroll margin at the real height**

In `frontend/src/components/Panel.tsx`, the `<section>` className:

```
'scroll-mt-[calc(var(--pagebar-h,0px)+3.5rem)] rounded-lg border bg-surface',
```

becomes

```
'scroll-mt-[calc(var(--pagebar-h,0px)+var(--sectionbar-h,0px)+1rem)] rounded-lg border bg-surface',
```

and the `id` prop's doc comment gains a sentence:

```
   * margin below clears the sticky page bar and the section bar under it, so a jumped-to
   * panel lands below both rather than behind them. Both heights are read from the custom
   * properties those two bars publish -- this used to hardcode 3.5rem for a bar that had no
   * call site and turned out to be 34px, which would have left every jump 22px low.
```

- [ ] **Step 7: Make the pinned rail clear the section bar too**

In `frontend/src/components/DetailLayout.tsx`, `measure()` reads one custom property; it must read two:

```tsx
    const measure = () => {
      const style = getComputedStyle(document.documentElement);
      // Both sticky bars, not just the page bar. On the claim record a section bar sits under
      // the page bar, and a rail pinned to the page bar alone starts 34px behind it.
      const offset =
        (Number.parseFloat(style.getPropertyValue('--pagebar-h')) || 0) +
        (Number.parseFloat(style.getPropertyValue('--sectionbar-h')) || 0);
      setPinned(shouldPin(node.offsetHeight, window.innerHeight, offset));
    };
```

and the sticky class on the rail:

```tsx
            pinned &&
              'lg:sticky lg:top-[calc(var(--pagebar-h,0px)+var(--sectionbar-h,0px)+1rem)]',
```

`--sectionbar-h` is unset on every page that has no section bar, so `var(..., 0px)` keeps all of them byte-identical. Do not add a default to `:root` in `index.css` — an unset property is what makes the fallback fire, and a `0px` default there would also have to be removed by hand.

- [ ] **Step 8: Extend `SectionNav`'s test**

In `frontend/src/components/SectionNav.test.tsx`, add:

```tsx
  it('marks the first section as current before anything scrolls', () => {
    render(
      <SectionNav
        label="Claim sections"
        sections={[
          { id: 'assess', label: 'Assessment' },
          { id: 'evidence', label: 'Evidence' },
        ]}
      />,
    );
    expect(screen.getByRole('link', { name: 'Assessment' })).toHaveAttribute('aria-current', 'true');
    expect(screen.getByRole('link', { name: 'Evidence' })).not.toHaveAttribute('aria-current');
  });

  it('publishes its height so the panels and the rail can stop under it', () => {
    const { unmount } = render(
      <SectionNav label="Claim sections" sections={[{ id: 'assess', label: 'Assessment' }]} />,
    );
    // jsdom reports every offsetHeight as 0, so the VALUE proves nothing -- that the property
    // is set at all, and removed on unmount, is the contract worth holding. A wrong value is
    // visible in a screenshot; a leaked property is not.
    expect(document.documentElement.style.getPropertyValue('--sectionbar-h')).toBe('0px');
    unmount();
    expect(document.documentElement.style.getPropertyValue('--sectionbar-h')).toBe('');
  });
```

- [ ] **Step 9: Build the claim's sections array**

In `frontend/src/features/claims/ClaimDetailPage.tsx`, above the component, add a function that returns the sections *and their content together*, so the bar can never name a panel the page did not render — the failure mode a hand-written `sections` array beside a set of conditional panels invites:

```tsx
/**
 * The claim record's sections, in the order the person who opened the page needs them.
 *
 * One array rather than a `sections` list beside a stack of conditional panels: five of these
 * panels are gated on role and status, so two lists would drift the first time a gate changed,
 * and the bar would offer a jump to a panel that is not on the page. Returning content beside
 * the label makes that impossible to express.
 */
function claimSections(...): { id: string; label: string; content: ReactNode }[]
```

Its parameters are whatever the component already computes — `claim`, `claimId`, `roles`, `canAssess`, `canDecide`, `canReopen`, `canSeeReinsurance`. Build the array by pushing one entry per panel that survives its gate, reusing the **existing** `<Panel>` JSX verbatim, adding an `id` to each:

| Panel | id | label |
|---|---|---|
| Submit an assessment | `assessment` | Assessment |
| Decide settlement | `settlement` | Settlement |
| Reopen | `reopen` | Reopen |
| Assessments | `assessments` | History |
| Payment | `payment` | Payment |
| Evidence | `evidence` | Evidence |
| Reinsurance | `reinsurance` | Reinsurance |

Then in the body:

```tsx
{sections.length > 1 && <SectionNav label="Claim sections" sections={sections.map(({ id, label }) => ({ id, label }))} />}

<DetailLayout record={...}>
  {sections.map((section) => (
    <Fragment key={section.id}>{section.content}</Fragment>
  ))}
</DetailLayout>
```

`sections.length > 1` because a staff.underwriter sees only Evidence, and a bar with one link is furniture that jumps you where you already are.

- [ ] **Step 10: Verify**

```
cd frontend && npx tsc -b && npx eslint src && npx vitest run
```

Expected: clean, and the unit count rises by 7 (5 spy + 2 SectionNav).

- [ ] **Step 11: Commit**

```
git add frontend/src/components/sectionSpy.ts frontend/src/components/sectionSpy.test.ts frontend/src/components/SectionNav.tsx frontend/src/components/SectionNav.test.tsx frontend/src/components/Panel.tsx frontend/src/components/DetailLayout.tsx frontend/src/features/claims/ClaimDetailPage.tsx
git commit -m "feat(console): the claim record gets a section bar, and the sticky stack makes room for it"
```

---

### Task 2: The claim record says where it sits and what state it is in

**Files:**
- Modify: `frontend/src/features/claims/ClaimDetailPage.tsx`

**Interfaces:**
- Consumes: `PageHeader`'s `breadcrumb` and `status` props, added in plan 2.

- [ ] **Step 1: Replace the hand-rolled back link with the breadcrumb**

Delete the `BackLink` function and both of its render sites, including the `<div className="px-6 pt-6">` wrappers. It is a ghost-variant `Button` with an `ArrowLeft` pretending to be navigation, and it pushed the page bar 44px down the screen on every claim. The breadcrumb is what plan 2's policy record uses and it lives inside the bar:

```tsx
      <PageHeader
        breadcrumb={[{ label: 'Claims', to: '/staff/claims' }]}
        title={...}
        description={...}
        status={claim?.status && <StatusBadge kind="claim" value={claim.status} />}
      />
```

Note `status`, not `actions` — the badge is a fact about the record, not a control, and `actions` is where a button goes.

**The agents-realm mount matters here.** `ClaimsPage` takes a `showNewClaimAction` prop because it is mounted twice; check `screens.tsx` for whether `ClaimDetailPage` is reachable under `/agents/`. If it is, the crumb must be relative (`to=".."`) rather than the absolute `/staff/claims`, or an agent's breadcrumb walks them into the staff console and a 403.

- [ ] **Step 2: Humanise the title**

`title={claim?.claimType ?? 'Claim'}` renders `CRITICAL_ILLNESS` in an `<h1>`. Use the acronym-safe helper plan 2 added:

```tsx
import { humanizeStatus } from '@/lib/status';
...
title={claim ? humanizeStatus(claim.claimType) : 'Claim'}
```

Check `lib/status.ts`'s `ACRONYMS` set first — if a claim type contains an acronym that is not in it, add it there rather than special-casing here.

- [ ] **Step 3: Check the specs that read the heading**

```
cd frontend && grep -rn "getByRole('heading'\|locator('h1')" e2e/staff-claims*.spec.ts e2e/staff-credit-life-claim.spec.ts e2e/staff-role-gates.spec.ts
```

Any spec asserting on the raw enum must be updated in this commit, not a later one.

- [ ] **Step 4: Verify and commit**

```
cd frontend && npx tsc -b && npx eslint src && npx vitest run
git add frontend/src/features/claims/ClaimDetailPage.tsx
git commit -m "feat(console): a claim record says where it sits, and its type reads as English"
```

---

### Task 3: The claim panels adopt `pending`, and `ConfirmAct` stops renaming its own button

**Files:**
- Modify: `frontend/src/components/ConfirmAct.tsx`
- Modify: `frontend/src/components/ConfirmAct.test.tsx`
- Modify: `frontend/src/features/claims/ClaimAssessmentPanel.tsx`
- Modify: `frontend/src/features/claims/ClaimReopenPanel.tsx`
- Modify: `frontend/src/features/claims/RegisterClaimPage.tsx`

**Interfaces:**
- Consumes: `Button`'s `pending` prop.

> **This is the one cross-lane change in the plan.** `ConfirmAct` has ten call sites across billing, distribution, party, payments, policies and underwriting. It earns its place here because the settlement decision is a `ConfirmAct` and this lane cannot be consistent while the confirm button still renames itself — but it gets its own commit and a full e2e run, because its blast radius is not the claims lane.

- [ ] **Step 1: Swap the three label-swapping buttons**

Each is the same edit. `ClaimAssessmentPanel.tsx`:

```tsx
        <Button type="submit" size="sm" variant="primary" pending={submitting.status === 'loading'}>
          Submit assessment
        </Button>
```

`ClaimReopenPanel.tsx`: same shape, label `Reopen claim`, state `reopening.status === 'loading'`.
`RegisterClaimPage.tsx`: same shape, label `Register claim`, state `registering.status === 'loading'`.

The point is not the spinner. A button whose accessible NAME changes mid-flight is a different control to a screen reader and to every locator that looks for it, and `aria-busy` says "working" where a bare `disabled` says only "unavailable".

- [ ] **Step 2: Stop `ConfirmAct` renaming its confirm button**

In `ConfirmAct.tsx`:

```tsx
        <Button
          ...
          pending={busy}
          disabled={confirmDisabled}
        >
          {confirmLabel}
        </Button>
```

`pending` already implies `disabled`, so `busy ||` comes out of the `disabled` expression. Leave the Cancel button's `disabled={busy}` exactly as it is — cancel is not the thing in flight, and a spinner on it would claim it is.

Keep the `busy` prop name. It is the word ten call sites already pass and its doc comment already distinguishes it from `confirmDisabled`; renaming it would be ten diffs for nothing.

- [ ] **Step 3: Fix the one test that asserts the old label**

`ConfirmAct.test.tsx:64` locates `getByRole('button', { name: 'Working…' })`. It now locates the real label. Assert the new contract rather than deleting the case:

```tsx
    const confirm = screen.getByRole('button', { name: 'Settle claim' });
    expect(confirm).toBeDisabled();
    expect(confirm).toHaveAttribute('aria-busy', 'true');
```

(Use whatever `confirmLabel` that test already renders.)

- [ ] **Step 4: Verify**

```
cd frontend && npx tsc -b && npx eslint src && npx vitest run
```

- [ ] **Step 5: Run the e2e specs this one can reach**

Not just the claims specs — `ConfirmAct` is shared:

```
cd frontend && npx playwright test staff-claims-adjudication staff-billing staff-policy-lifecycle staff-distribution staff-underwriting --project=staff
```

Expected: green, except anything the `REJECTED`-party database fault already breaks.

- [ ] **Step 6: Commit**

```
git add frontend/src/components/ConfirmAct.tsx frontend/src/components/ConfirmAct.test.tsx frontend/src/features/claims/
git commit -m "feat(console): a button in flight keeps its name, on the claim panels and in every confirm"
```

---

### Task 4: Evidence is reachable without a mouse, and says when it is uploading

**Files:**
- Modify: `frontend/src/features/claims/EvidencePanel.tsx`
- Test: `frontend/e2e/staff-claim-evidence.spec.ts` (existing, must stay green)

**Interfaces:**
- Consumes: `InlineError`.

The dropzone is the worst accessibility defect in the claims lane. `react-dropzone`'s `getRootProps()` returns a `div` with `role="presentation"` and `tabIndex={0}` — focusable, with no role and no accessible name — and the `<input type="file">` inside it is unlabelled. A keyboard user tabs onto a thing a screen reader announces as nothing at all.

- [ ] **Step 1: Give the input a real label and the region a name**

```tsx
      <div
        {...getRootProps()}
        aria-label="Attach evidence"
        className={cn(...)}
      >
        <input {...getInputProps()} aria-label="Attach evidence" disabled={attaching.status === 'loading'} />
```

Verify against the installed `react-dropzone` version that `getRootProps()` does not overwrite `aria-label` — spread order above puts ours last, which is what makes it win. If the root still reports `role="presentation"`, an element with a name and no role is no better; in that case drop the root's label and keep only the input's, since the input is the control axe and a screen reader both follow.

- [ ] **Step 2: Announce the upload**

The status line is a plain `<p>` that swaps text. Make it a live region so a screen reader hears the upload start and finish:

```tsx
        <p className="text-xs text-muted-foreground" aria-live="polite">
```

- [ ] **Step 3: Use `InlineError` for the failure**

```tsx
      {attaching.status === 'error' && attaching.error && <InlineError error={attaching.error} />}
```

replacing the hand-rolled `<p role="alert">`. That block is the 21st copy of the pattern plan 1 extracted; it loses nothing and gains the trace id, labelled and selectable.

- [ ] **Step 4: Fix the copy while here**

`This claim is SETTLED -- reopen it before attaching new evidence.` renders a literal double hyphen to a user. Make it an em dash, and lower-case the status so it reads as a sentence rather than a shout:

```
This claim is settled — reopen it before attaching new evidence.
```

Grep `staff-claim-evidence.spec.ts` for that string first.

- [ ] **Step 5: Verify**

```
cd frontend && npx tsc -b && npx eslint src && npx vitest run
cd frontend && npx playwright test staff-claim-evidence --project=staff
```

- [ ] **Step 6: Commit**

```
git add frontend/src/features/claims/EvidencePanel.tsx
git commit -m "fix(console): the evidence dropzone has a name, announces itself, and errors like everything else"
```

---

### Task 5: Registering a claim becomes three groups instead of fourteen fields

**Files:**
- Modify: `frontend/src/features/claims/RegisterClaimPage.tsx`

At 534 lines this is the longest form on the platform and it is one flat run of `FormField`s under a single `PageHeader`: claimant, policy, member, date of event, claim type, then four to six more that appear depending on the type, with nothing on screen saying a new group of questions has started.

- [ ] **Step 1: Group into three `Panel`s**

| Panel | Fields |
|---|---|
| `Who and which policy` | Claimant, Policy, Who died (scheme only) |
| `The event` | Date of event, Claim type, plus the `GatePanel` |
| `Details` — subtitle names the type, e.g. `What is known about the death` | the type-specific block |

Use `Panel`, not `<fieldset>`: this console has one grouping primitive and it is `Panel`, whose heading is an `<h2>` under the page's single `<h1>`. A `<fieldset>`/`<legend>` would introduce a second grouping idiom for one screen.

Do **not** mark any of them `emphasis`. The Panel doc is explicit that emphasis is one per page and only where the page exists to perform an act; here every group is equally the act.

- [ ] **Step 2: Keep the third panel out of the DOM until a type is chosen**

The type-specific block is already conditional. The panel wrapping it must be too — an empty titled box is worse than no box.

- [ ] **Step 3: Verify the specs still find every field**

Field labels do not change, so label-based locators survive. Prove it:

```
cd frontend && npx playwright test staff-claims staff-credit-life-claim staff-claim-policy-chooser --project=staff
```

- [ ] **Step 4: Commit**

```
git add frontend/src/features/claims/RegisterClaimPage.tsx
git commit -m "feat(console): registering a claim asks three groups of questions, not fourteen"
```

---

### Task 6: The claims register matches the policies register

**Files:**
- Modify: `frontend/src/features/claims/ClaimsPage.tsx`

- [ ] **Step 1: Let the filter row wrap without collision**

The chip row is `flex flex-wrap items-center gap-1.5`. `PoliciesPage`'s is `gap-1.5 gap-y-2`, with a comment explaining it: the chips are 32px tall, and at phone width the search box drops onto its own line hard against them. The claims register has *more* statuses than policies, so it wraps sooner. Add `gap-y-2` and carry the reason across.

- [ ] **Step 2: Replace the `title`-attribute contestability marker**

```tsx
            <span className="text-xs text-status-warning-fg" title="Falls inside the policy's contestability window">
              CR
            </span>
```

A `title` attribute is not reachable by keyboard, is not announced reliably, and never appears on touch. Two letters of yellow text carry the whole meaning otherwise. Replace with visually-hidden text that says the same thing, so the abbreviation stays compact for sighted scanners and complete for everyone else:

```tsx
            <span className="text-xs text-status-warning-fg">
              CR<span className="sr-only"> — falls inside the policy's contestability window</span>
            </span>
```

Confirm `sr-only` exists in this project's Tailwind v4 build before using it; if not, use the `DataTable` caption's own visually-hidden idiom, whatever that file already does.

Then check that `CR` is in `lib/status.ts`'s `ACRONYMS` set — plan 2 added it there, so a status containing it is not mangled elsewhere.

- [ ] **Step 3: Verify and commit**

```
cd frontend && npx tsc -b && npx eslint src && npx vitest run
cd frontend && npx playwright test staff-claims --project=staff
git add frontend/src/features/claims/ClaimsPage.tsx
git commit -m "fix(console): the claims register wraps like the policies register, and CR says what it means"
```

---

### Task 7: The claim record joins the axe sweep

**Files:**
- Modify: `frontend/e2e/staff-a11y.spec.ts`
- Modify: `frontend/e2e/a11y-baseline.json` (only if the baseline legitimately changes)

- [ ] **Step 1: Find a claim record the way the spec finds a policy record**

The spec already derives the policy record from the live table rather than hardcoding a number, and says why: ids are minted per run and a literal would rot. Do the same, from the claims table. The claims table's first column is the claim TYPE, not an id, so the row's link/button target is what to read — check how `DataTable`'s `onRowActivate` renders before writing the locator, because the claims register opens a **drawer**, not a page. If no row exposes a navigable claim id, navigate via the drawer's own "Open full record" control, whatever `ClaimDrawer` calls it.

- [ ] **Step 2: Add it to the swept set**

```tsx
  for (const route of [...ROUTES, policyRecord, claimRecord]) {
```

- [ ] **Step 3: Run it**

```
cd frontend && npx playwright test staff-a11y --project=staff
```

Expected: **the baseline stays `{}`.** If the claim record introduces a violation, fix the violation — do not record it into the baseline. The baseline exists to hold the count that was already there before axe ran on this console; a new screen adding to it defeats the purpose.

- [ ] **Step 4: Commit**

```
git add frontend/e2e/staff-a11y.spec.ts
git commit -m "test(console): the claim record joins the axe sweep"
```

---

### Task 8: Lane review and merge

- [ ] **Step 1: Read the whole diff against `main`**

```
git diff main --stat && git diff main
```

Look specifically for the three things the plan-2 review caught, because this lane can produce all three:
1. **A panel that stopped mounting.** Task 1 rebuilds the panel stack from an array. Anything that fetched on mount inside a panel still mounts — sections are not tabs — but confirm it, because that regression was silent last time.
2. **Double padding.** `SectionNav` carries its own `px-6` and sits outside `DetailLayout`'s `px-6` grid, which is correct — but verify against a screenshot that the bar's links line up with the panel edges below them, the exact check that caught `RecordTabs`.
3. **Substring name matching.** "Assessment" and "Assessments" are both section labels, and Playwright matches substrings by default. Any new locator for either needs `exact: true`.

- [ ] **Step 2: Full suite**

```
cd frontend && npx tsc -b && npx eslint src && npx vitest run
cd frontend && npx playwright test
```

- [ ] **Step 3: Screenshot the claim record at desktop and phone width**

One batched round, both widths, per the craft floor: build fully, inspect once, fix everything it shows in one batch, confirm with at most one more round. Check the section bar's stickiness against the page bar, the pinned rail's top edge, and that a jump lands the panel below both bars rather than behind them.

- [ ] **Step 4: Merge**

```
git checkout main
git merge --no-ff console-redesign-3-claims
```

---

## What this plan deliberately does not do

- **No tabs on the claim record.** Settled above; restated so a later reader does not "fix" the inconsistency with plan 2.
- **No change to `ClaimSettlementPanel`'s decision logic.** It is 438 lines of genuine domain rules — payee resolution, scheme handling, product category gating. This lane restyles its shell and leaves its reasoning alone.
- **No `ClaimDrawer` rework.** Plan 2 slimmed `PolicyDrawer` to a summary; the claim drawer deserves the same and does not get it here, because the drawer is the register's component and this lane's weight is on the record. Carry it to plan 4 if the claims register still feels heavy after task 6.
- **No fix for the `ProductName` UUID flash.** Still pre-existing, still shared, still the wrong lane.
