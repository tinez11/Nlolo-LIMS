# Console polish — review findings and plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Plans B–D are gated on the decisions in §3; do not start a gated task until its decision is recorded in §3.

**Goal:** Fix the visible defects in the staff console, bring the code back into line with `DESIGN.md` where it has drifted, and add the interaction feedback and flow support the console lacks — without breaking the design's own settled rules.

**Source:** a review on 2026-10-09 of five screenshots (claims list, a group-funeral policy, a corporate client, a surrendered funeral policy's billing tab, the new-product form) plus a sweep of `frontend/src` (387 source files) and `DESIGN.md`.

**Architecture:** Frontend only. No backend, OpenAPI or generated-client change. Most work is in `frontend/src/components/` (shared primitives) and `frontend/src/index.css`; feature files change only where a defect lives in them.

**Tech stack:** React 19, Vite, Tailwind v4 (`@theme inline`), Radix (tabs, dialog, popover, tooltip), react-hook-form + zod, React Router 7 (`<BrowserRouter>` today), Vitest + Testing Library, Playwright against the real seeded stack.

---

## 1. Ratings

| Area | Score | One line |
|---|---|---|
| Tokens and colour | 9 | Every colour a token, both themes, contrast measured rather than asserted. |
| Accessibility basics | 8 | Skip link, real row buttons, URL-held tabs, errors as descriptions, an axe baseline. |
| Typography conformance | 6 | One family, but heading tiers, tracking, eyebrows and mono IDs have drifted from `DESIGN.md`. |
| Layout and overflow | 5 | Most visible defects live here: stray scrollbars, a table that wraps one word per line. |
| User flows | 6 | Good primitives (`ConfirmAct`, drawer previews, palette); long forms have no structure or unsaved-work guard. |
| Interaction feedback | 4 | No press state, spinner-heavy loading, validation only on submit, meaning hidden in hover-only `title`. |
| **Overall** | **6.5 / 10** | Strong foundations; the surface lets them down. |

---

## 2. Findings

### 2.1 Corrections to the first review

Two points from the conversational review were wrong and are **withdrawn**:

- **"Rows are too tall (~67px)."** The screenshots are taken at ~150% display scaling: the 224px sidebar measures ~335px. 67px ÷ 1.5 ≈ 44.7px, which is exactly the 44px row in `DESIGN.md` (*The 44px Row Rule*). Density is per spec.
- **"12px is too small; raise content to 14px."** `DESIGN.md` → *Typography → Hierarchy* records the compressed scale as "a confirmed decision, not drift", with 0.75rem as both workhorse and floor. At the user's 150% scaling it renders at ~18 physical pixels. Not a defect. See D1 if this is to be reopened.

### 2.2 Defects (visible in the screenshots, cause found in code)

| # | Defect | Cause | Where |
|---|---|---|---|
| F1 | A vertical scrollbar (▲▼) beside every record tab strip | `overflow-x-auto` forces the other axis to `auto` as well; each trigger's `-mb-px` overflows the list by 1px vertically | `components/RecordTabs.tsx:66`; same pattern in `components/SectionNav.tsx:92` |
| F2 | Sidebar scrolled sideways on the products screen (labels at x≈9 not 24, active pill touching the edge) | `nav` has `overflow-y-auto`, so `overflow-x` is `auto` too; some child is wider than the column | `components/AppShell.tsx:196` |
| F3 | Billing schedule wraps one word per line ("Oct / 8, / 2026 / – Oct / 7, / 2027"); "Paid by" cut off; totals wrap "TZS / 100,000.00" | No `whitespace-nowrap` on date or money cells; 10 columns in an ~980px panel | `features/documents/PaymentScheduleTable.tsx:47-64, 82-97` |
| F4 | A literal `--` in page copy ("publish a version - - a product") | The comment-style double hyphen used in a JSX string | `features/products/CreateProductPage.tsx:75`, plus a sweep (§ task A4) |
| F5 | Avatar initials wrong: "Daudi Assessor" → **SA**, "Halima Underwriter" → **SU** | Initials are taken from the username (`staff-assessor`), not the display name | `components/AppShell.tsx:354, 369` |
| F6 | The × that removes a benefit wraps onto its own line under the row | The benefit row wraps inside `max-w-xl`; the × is the last flex child | `features/products/PublishVersionForm.tsx:~830-892` |
| F7 | A bare "—" at the end of a "Full sum assured" benefit row | The intended meaning ("no amount needed") is shown as an absent-value dash, which `DESIGN.md` reserves for *absent*, not *not applicable* | `PublishVersionForm.tsx:879-881` |
| F8 | "Date of birth —" and "Nationality —" shown for a **Company** | Person-only fields rendered for every party type | `features/party/PartyDetailPage.tsx` (identity rail) |
| F9 | Nav labels truncated: "Corporate/G…", "Group funeral sche…"; roles "UNDERWRITER, FINANCE…" | 224px sidebar with a count badge; raw role enums joined with commas | `AppShell.tsx:263, 373`; `screens.tsx` |
| F10 | "CR" in amber next to a claim's status means nothing to a sighted reader | The meaning is in `sr-only` text only | `features/claims/ClaimsPage.tsx:126-138` |
| F11 | The schedule's Status column renders the raw literal with a tone class, not a `StatusBadge` | Bypasses the six-bucket component | `PaymentScheduleTable.tsx:63` |

### 2.3 Drift from `DESIGN.md` (typography)

Measured across `src/**/*.tsx`:

| Size | Uses | | Weight | Uses | | Tracking | Uses |
|---|---|---|---|---|---|---|---|
| `text-xs` | 813 | | `font-medium` | 297 | | `tracking-wide` | 11 |
| `text-sm` | 212 | | `font-normal` | 69 | | `tracking-tight` | 5 |
| `text-lg` | 5 | | `font-semibold` | 28 | | `tracking-[0.03em]` | 1 |
| `text-base` | 4 | | `font-bold` | 1 | | `tracking-wider` | 1 |
| `text-xl` / `2xl` | 2 / 1 | | | | | `leading-*` | 2 |
| `text-[13px]` | 2 | | | | | | |

- **T1 — Tiers exist only as prose.** `DESIGN.md` defines Display / Headline / Title / Body / Label / Micro / Eyebrow / Mono, each with a size, weight, line height and tracking. The code has no such tokens, so every site rebuilds a tier from raw utilities and gets it slightly different. `PageHeader`'s h1 uses `tracking-tight` (−0.025em), not the specified −0.015em.
- **T2 — Eyebrow has four spellings.** Spec: 0.75rem / 500 / +0.03em / uppercase. Code: `tracking-wide` ×11, `tracking-wider` ×1, `tracking-[0.03em]` ×1.
- **T3 — Recorded drift never fixed.** `ui/button.tsx` `sm` uses `text-[13px]` (spec: Body); `RealmPicker` h1 is `text-lg` (spec: Headline). Both are named as drift in `DESIGN.md` itself.
- **T4 — A faux-bold weight.** `font-bold` is used once, but `index.html` loads Inter 400/500/600 only, so the browser fakes the 700.
- **T5 — Mono IDs in six combinations.** `font-mono` appears 83 times as `text-xs`, `text-sm`, `font-medium` or no size. Spec: Mono at 0.75rem.
- **T6 — Font from Google's CDN.** A white-label console hosted per tenant fetches its only typeface from `fonts.googleapis.com`. That's a privacy, CSP and offline-dev concern, and it limits the console to static weights.
- **T7 — `DESIGN.md` itself is stale in places.** It says the radius is 6px (code: 8px since redesign plan 1), that the outline button is the default variant (code: `secondary`), and that nav-active takes the Selected ground (code: Paper + raise). Fix the document, not the code (task A9).

### 2.4 Interaction and flow gaps

- **I1 — No press state.** `active:` appears 4 times. `DESIGN.md` → *Buttons* already names this a "target gap": "a real `:active` state on buttons — a perceptible press".
- **I2 — Spinner-heavy loading.** `LoadingBlock` in 82 feature files versus `TableSkeleton` in 49 sites. `DESIGN.md` wants skeletons "so a table does not jump when data lands"; detail panels and rails don't have one.
- **I3 — A tab revisit re-shows the spinner.** Tabs are unmounted by design (`DESIGN.md` → *Two shapes for a record*), so returning to a tab refetches with an empty panel. See D4.
- **I4 — Validation on submit only.** All 44 `useForm` calls take the default `mode: 'onSubmit'`.
- **I5 — Meaning in hover-only `title`.** 362 `title=` attributes in features. They don't show on touch or reliably to keyboard users. `@radix-ui/react-tooltip` is a dependency with **zero** imports.
- **I6 — No unsaved-work guard.** `PublishVersionForm.tsx` is 1,522 lines and other long forms (scheme issue, underwriting) are similar. There is no `beforeunload` and no `useBlocker`. `useBlocker` needs a data router, and the app uses `<BrowserRouter>` (`App.tsx:29`).
- **I7 — The product form leaves half the screen empty** (`max-w-xl` inside a wide main column) and has no section index, although `SectionNav` already exists for exactly this shape.
- **I8 — Finance is one flat group of 21 items**, with reused icons: `Percent` ×3 (withholding rules, IFRS 17 engine, account charges), `ScrollText` ×4, `Users` ×5 across the console, `HandCoins` ×2. Icons that repeat can't help anyone find a destination.
- **I9 — Mutation outcomes not audited.** `Receipt` is used in 6 places across ~54 mutation call sites. `DESIGN.md` limits receipts to outcomes the screen does not otherwise reveal, which is right, but nobody has checked the other ~48 against that rule.
- **I10 — No staff home.** `RealmHome` redirects to the first permitted screen. The nav badges already count underwriting cases, arrears at lapse, overdue receipts and EFTs awaiting. See D6.
- **I11 — Motion and accessibility preferences are ignored.** `prefers-reduced-motion`, `prefers-reduced-transparency` and `prefers-contrast` each appear 0 times. The slide-over's 200ms slide plays for everyone.
- **I12 — Popovers grow from their centre**, not from the control that opened them (date picker, party picker).

---

## 3. Decisions needed before Plans B–D

Several earlier proposals collide with rules `DESIGN.md` already settled. Each row gives the recommendation; the user's choice goes in the last column before any gated task starts.

| # | Question | Recommendation | Decision |
|---|---|---|---|
| D1 | Reopen the compressed type scale (12px workhorse)? | **No.** Keep it; fix only the drift in §2.3. | **Keep the scale** (user, 2026-10-09) |
| D2 | Translucent, blurred sticky page and tab bars (an Apple-style "material")? | **No.** `DESIGN.md` says the console is "emphatically not … glassy" and flat "is law". Withdrawn. | **Declined** (user, 2026-10-09) |
| D3 | Springs and drag-to-dismiss for the slide-over and mobile nav? | **No.** `DESIGN.md` → *Motion*: "almost none, deliberately", and the console is desk-first. Take only the Apple principles that fit the Ledger: instant press feedback, popovers that grow from their trigger, reduced-motion handling. | **Declined** (user, 2026-10-09) |
| D4 | Keep the last response of each tab, so a revisit shows it at once while it refetches? | **Yes.** Tabs stay unmounted and still load when opened, so the *Two shapes* rule holds; only the empty-panel flash goes. | **Yes** (user, 2026-10-09) |
| D5 | Add a receipt to every mutation? | **No.** Audit the ~54 instead (task C4) and add receipts only where the outcome is invisible, per `DESIGN.md` → *Receipt*. Withdrawn. | **Audit, no blanket receipts** — the recommendation stands; not put to the user separately |
| D6 | A role-aware "Today" home built from the existing badge counts? | **Yes**, counts only (no trends, no new endpoints), each linking to its filtered list. | **Yes** (user, 2026-10-09) |
| D7 | Unsaved-work guard: move to `createBrowserRouter` (for `useBlocker`) or `beforeunload` only? | **Move to a data router.** `beforeunload` only covers closing the tab; the real loss is an in-app sidebar click. The route manifest in `screens.tsx` makes the move mostly mechanical. | **Data router** (user, 2026-10-09) |
| D8 | Split Finance into sub-groups (Collections, Payouts, Ledger, IFRS 17 & close, Reinsurance & regulatory)? | **Yes**, with a distinct icon per item. Changes nav headings; grep `e2e/` first. | **Yes** (user, 2026-10-09) |
| D9 | Widen the sidebar 224 → 240px to stop the truncation? | **Yes**, and update `DESIGN.md`. The alternative is shorter labels, which would lose "Corporate/Group" as the register's name. | **Yes, 240px** (user, 2026-10-09) |
| D10 | Validate on touch (`mode: 'onTouched'`, `reValidateMode: 'onChange'`)? | **Yes.** It is not addressed by `DESIGN.md`; errors would appear on blur instead of on submit. | **Yes** (user, 2026-10-09) |
| D11 | Replace "CR" with a visible word, e.g. a small "Contestable" warning badge? | **Yes.** It is a state, so a pill satisfies *Pill-for-State*. | **Yes** (user, 2026-10-09) |

Record each decision here and in `DESIGN.md` (task A9 or the gated task), so a declined item is not offered again.

---

## 4. Plan series

| Plan | Scope | Gated on |
|---|---|---|
| **A** | Defects F1–F11 and typography drift T1–T7. No behaviour change. | D9, D11 (tasks A6, A7 only) |
| **B** | Interaction feedback: press states, skeletons, tab cache, tooltips, validation timing, popover origin, accessibility preferences | D4, D10 |
| **C** | Flows: unsaved-work guard, product-form structure, Finance nav, receipt audit, Today home | D5–D8 |
| **D** | Withdrawn proposals, recorded so they are not re-offered | D1–D3 |

---

## 5. Global constraints

Carried over from the console redesign plans, still binding:

- **Frontend only.** No backend, OpenAPI or generated-client change.
- **Keep the Ledger.** Achromatic plus six status hues; colour means state only (*Stamp Rule*); no resting shadows (*Floating-Layer Rule*); two type peaks per screen (*Two-Peaks Rule*); uppercase only for group captions.
- **Contrast floor:** text ≥ 4.5:1, control boundaries and focus ≥ 3:1. The focus ring is never suppressed.
- **12px minimum text** (`designGuards.test.ts` keeps it).
- **Money is a string end to end.** Only `lib/money.ts` parses it.
- **Submit guards stay.** Mutating buttons stay disabled while in flight; the verb never changes while pending (guarded).
- **Never run Prettier.** Match the surrounding formatting by hand.
- **Edit with the Edit/Write tools**, not shell strings. A node script may do a pure pattern replacement and nothing else.
- **Never run vitest while Playwright is running**, and never run Playwright during a Maven build.
- **e2e locates by accessible name.** Grep `frontend/e2e` before renaming any label, heading, tab or button, and update the specs in the same commit.
- **Every new rule gets a guard** in `src/test/designGuards.test.ts` where it can be expressed as a scan, so drift fails a unit test.

## 6. Verification, every task

- [ ] `npm run typecheck` and `npm run lint` clean.
- [ ] `npx vitest run` green. Compare the test-file count with the previous run, so a skipped file can't pass silently.
- [ ] The affected Playwright specs run alone, with deps (never `--no-deps`).
- [ ] For visual tasks, `e2e/staff-visual-capture.spec.ts` at **1920×900 and at deviceScaleFactor 1.5**. That is the user's real setup, and it is where F1–F3 show.
- [ ] `e2e/staff-a11y.spec.ts`: the violation count does not grow against `a11y-baseline.json`.

---

## Plan A — defects and drift

### Task A1: Remove the tab-strip scrollbar (F1)

**Outcome (2026-10-09):** the tab strip's rule is now an inset shadow (`shadow-[inset_0_-1px_0_var(--color-border)]`) instead of `border-b` plus `-mb-px` triggers. Clipping the vertical axis alone would have cut the bottom pixel off the active underline. The triggers draw their focus ring inset (`focus-visible:-outline-offset-2`). `SectionNav` only needed `overflow-y-hidden`, since its links have 6px of padding to hold the ring. Probe: `scrollHeight` 40 = `clientHeight` 40. There's a guard for sticky sideways scrollers.

**Files:** `components/RecordTabs.tsx`, `components/SectionNav.tsx`, `components/RecordTabs.test.tsx`

- [ ] **Step 1:** Reproduce: open any policy at 1920×900, scale 1.5, and confirm the ▲▼ control at the right end of the tab strip. In devtools, confirm that `list.scrollHeight > list.clientHeight` by 1–2px.
- [ ] **Step 2:** Add `overflow-y-hidden` beside `overflow-x-auto` on `TabsPrimitive.List`, and the same on the `SectionNav` bar. Keep `-mb-px`, because it is what puts the active underline on the strip's rule.
- [ ] **Step 3:** Add a guard: a `className` containing `overflow-x-auto` together with `sticky` must also contain `overflow-y-hidden`. The plain `DataTable` wrapper is not sticky, so it is unaffected.
- [ ] **Step 4:** Re-capture. Check that the strip still scrolls sideways at a narrow width, the underline still sits on the rule, and the focus ring is still fully visible (it may need `outline-offset: -2px` on triggers now that vertical overflow is clipped).

### Task A2: Stop the sidebar scrolling sideways (F2) — NOT A DEFECT

**Outcome (2026-10-09):** not reproduced. Probed at 1280×600, scale 1.5, on `products/new`, `products` and `policies`: the nav's `scrollWidth` equals its `clientWidth` (223 = 223), and `scrollLeft` is 0. In screenshot 5 *everything* sits about 10px left (the logo, the go-to box, the breadcrumb, the right-hand scrollbar), which points to the screenshot's crop, not the nav. No code change. The steps below are kept for reference only.

**Files:** `components/AppShell.tsx`

- [ ] **Step 1:** On the products screen, run in the console: `[...document.querySelectorAll('#sidebar nav *')].filter(e => e.scrollWidth > e.clientWidth || e.getBoundingClientRect().right > document.querySelector('#sidebar nav').getBoundingClientRect().right)`. Record the culprit. Candidates are the `shadow-raise` active pill and a long badge.
- [ ] **Step 2:** Fix the culprit. Then add `overflow-x-hidden` to the `<nav>` as the backstop, because a sidebar must never scroll sideways.
- [ ] **Step 3:** Capture every staff role's sidebar at scale 1.5 and check that all labels start at the 24px rhythm.

### Task A3: A billing schedule that reads as a ledger (F3, F11)

**Outcome (2026-10-09), different from the steps below:** `nowrap` alone could not fix it. The user's 1920×900 screen at 150% is a **1280×600 CSS viewport**, where the record's work column is about 670px, and 10 columns cannot fit. The schedule went to **five columns**:
- **Due:** the due date, with "covers to …" underneath, or the full range when the cover doesn't start on the due date.
- **Amount due.**
- **Paid:** the amount, with "date · payer" and then the receipt underneath.
- **Status:** a `StatusBadge` of kind `invoice`, keeping the server's wording through a new `label` prop.
- **Balance.**

"No." was dropped from the screen (the PDF keeps it). Totals use an auto-fill grid instead of `sm:grid-cols-5`. `DataTable` gained `nowrap`, which `align: 'right'` implies. Verified at 1280×600 ×1.5: one line per premium, Balance visible, no horizontal scroll.

**Files:** `components/DataTable.tsx`, `features/documents/PaymentScheduleTable.tsx`, the tests beside both, `e2e/staff-billing.spec.ts`

- [ ] **Step 1:** Add a failing `DataTable` test: a column with `align: 'right'` renders its cells with `whitespace-nowrap`. A money figure must never break, anywhere, which is why this belongs in `DataTable` rather than in one table.
- [ ] **Step 2:** Add `nowrap?: boolean` to `Column<T>`, applied to the `th` and `td`; `align: 'right'` implies it. Implement until the test passes.
- [ ] **Step 3:** In the schedule, mark `due` and `paidOn` as `nowrap`. Render `covers` as two lines (`Oct 8, 2026` / `to Oct 7, 2027`) with each line `nowrap`.
- [ ] **Step 4:** Render `status` through `StatusBadge` with the billing domain's mapping. Check `lib/status.ts` / `statusBadge.ts` for the literals the schedule returns, and take them from the OpenAPI spec rather than typing them.
- [ ] **Step 5:** Add `whitespace-nowrap` to the totals `dd`.
- [ ] **Step 6:** Capture the screen-4 policy (POL-ABFE4139) at scale 1.5. The table should need a horizontal scrollbar only below `lg`. If it still overflows at 1920, mark `paidOn` as `secondary` as well, and say so in the commit.
- [ ] **Step 7:** Run `staff-billing.spec.ts` and every spec that opens the Billing tab (`grep -rln "name: 'Billing'" e2e`).

### Task A4: No `--` in visible copy (F4)

**Outcome (2026-10-09):** the guard found 21 sites, all real copy, all changed to `—`. **Trap:** eleven e2e locators matched the short form `'Rating table -- must cover'`, which a search on the full sentence missed. Two of them were `not.toBeVisible()` checks, which would have gone on passing without checking anything. Search on each sentence's *opening words* before changing copy.

**Files:** `test/designGuards.test.ts`, the sites it finds

- [ ] **Step 1:** Write the guard. Use the existing `code()` helper, which strips comments, and flag `/\s--\s/` that remains outside comments in `.tsx` files. Run it and record the real sites. The pre-guard sweep found about 21 candidates, most of them in comments.
- [ ] **Step 2:** Replace each real site with an em dash `—`. Where an em dash reads badly, rewrite the sentence instead (e.g. "Two steps: define the product, then publish a version. A product with no version is invisible everywhere else in this console.").
- [ ] **Step 3:** `grep -rn` the changed sentences in `e2e/` and update any spec that matches them.

### Task A5: Initials from the person's name (F5)

**Files:** `components/AppShell.tsx`, `auth/claims.test.ts`

- [ ] **Step 1:** Add a failing test: `initials(displayName({ name: 'Daudi Assessor', preferredUsername: 'staff-assessor' }))` is `'DA'`.
- [ ] **Step 2:** In `UserBlock`, render `initials(name)`. Keep `seed` (the username) for `avatarHue` only, so a person's colour stays stable if their display name is edited.

### Task A6: Labels that fit (F9) — gated on D9

**Files:** `components/AppShell.tsx`, `auth/claims.ts`, `DESIGN.md`

- [ ] **Step 1:** Change the sidebar `w-56` to `w-60`, and update `DESIGN.md` (`sidebar.width`, and *Layout → The shell*).
- [ ] **Step 2:** Render roles as humanised words joined with " · " ("Underwriter · Finance officer"), using the existing humanise helper. Put the full list in a Tooltip (task B5), or in `title` until B5 lands.
- [ ] **Step 3:** Capture the four longest labels at scale 1.5 next to their count badges. None may truncate.

### Task A7: Benefit rows that hold together (F6, F7)

**Files:** `features/products/PublishVersionForm.tsx`, its test

- [ ] **Step 1:** Make the benefit row one non-wrapping grid (`grid grid-cols-[1fr_1fr_7rem_auto]`) so the × always ends the row it removes. Below `sm`, stack the two selects and keep the amount and × on one line.
- [ ] **Step 2:** Replace the bare "—" for a full-sum-assured benefit with Micro text "No amount" in Subtle Ink. An em dash means *absent*, and this is *not needed*.
- [ ] **Step 3:** Check the same × placement in `AnnuityTermsSection.tsx:186` and `FuneralTermsSection.tsx:107`.

### Task A8: Fields that apply (F8, F10) — the F10 half is gated on D11

**Files:** `features/party/PartyDetailPage.tsx`, `features/claims/ClaimsPage.tsx`, tests, e2e

- [ ] **Step 1:** In the party identity rail, render Date of birth, Nationality and the other person-only fields only when the party is a person. Leave company-only fields to companies. An em dash then keeps meaning "we don't know".
- [ ] **Step 2 (D11):** Replace "CR" with a warning-bucket badge reading "Contestable". Keep the sr-only sentence as the badge's description. Grep `e2e/` for the old text first.

### Task A9: Typography as tokens, and `DESIGN.md` brought up to date (T1–T7)

**Outcome (2026-10-09):**
- `text-display/headline/title/eyebrow` are in `@theme`. All 14 eyebrows, the headline (PageHeader, RealmPicker), the title (`Panel emphasis`, slide-over, `Field emphasis`) and the display (StatCards) use them.
- Inter is self-hosted as `@fontsource-variable/inter@5.3.0`, `opsz` build. Verified: zero requests to Google's font servers, and h1 = 20px / −0.015em / 600 / 1.3.
- Small button 13px → 14px. **Filter chip 13px → 12px**, not 14: at 14 the claims filter row would no longer fit one line at 1280px.
- Five new guards: tracking only through tiers (the temporary-password display is exempt), no off-scale sizes including `text-lg` and above, uppercase `text-xs` must be `text-eyebrow`, no mono money.
- **Mono, narrowed:** money left mono (invoice breakdown, commission). The ~90 contextual `font-mono` uses that inherit the size around them were **left alone**; forcing them all to 12px would shrink identifiers inside 14px sentences.
- DESIGN.md: font, radius (8px), default button (secondary), nav-active, tier utilities, resolved drift, and a "Considered and declined" list (D1–D3, D5).

**Files:** `index.css`, `index.html`, `package.json`, `components/PageHeader.tsx`, `components/Panel.tsx`, `components/ui/button.tsx`, `features/RealmPicker.tsx`, the eyebrow and mono sites, `test/designGuards.test.ts`, `DESIGN.md`

- [ ] **Step 1:** Define the `DESIGN.md` tiers as Tailwind v4 text tokens in `@theme inline`, each with size, line height, tracking and weight:

  ```css
  --text-headline: 1.25rem;
  --text-headline--line-height: 1.3;
  --text-headline--letter-spacing: -0.015em;
  --text-headline--font-weight: 600;
  --text-title: 1rem;
  --text-title--line-height: 1.4;
  --text-title--font-weight: 600;
  --text-eyebrow: 0.75rem;
  --text-eyebrow--line-height: 1.4;
  --text-eyebrow--letter-spacing: 0.03em;
  --text-eyebrow--font-weight: 500;
  /* display, and mono (0.75rem / 1.4) the same way */
  ```

  Body, Label and Micro stay `text-sm` / `text-xs`. Renaming 1,000 sites buys nothing, since those tiers already map one to one.
- [ ] **Step 2:** Migrate the headline (`PageHeader`, `RealmPicker`), the title (`Panel emphasis`, slide-over title) and all 13 eyebrows to the tokens. Add guards: no `tracking-wide`, `tracking-wider` or `tracking-[…]` outside `index.css`, and `uppercase` only together with `text-eyebrow`.
- [ ] **Step 3:** In `ui/button.tsx`, change `sm` from `text-[13px]` to `text-sm`. Then guard: no arbitrary `text-[…px]` anywhere.
- [ ] **Step 4:** Self-host Inter as a variable font (`@fontsource-variable/inter`), remove the Google `<link>`s from `index.html`, and set `--font-sans: 'Inter Variable', …`. This fixes the faux bold (T4) and the CDN dependency (T6). Check that `font-variant-numeric: tabular-nums` still applies to the variable font.
- [ ] **Step 5:** Reduce the six mono combinations to the spec's one (`font-mono text-xs`), with an ID *in a page title* staying in the sans headline as it is today. Write it as a pattern replacement, review the collisions by hand, and guard against `font-mono` with any other `text-` size.
- [ ] **Step 6:** Update `DESIGN.md`: the radius (8px, `rounded-md`), the default button variant (`secondary`), nav-active (Paper + raise + 500), the text-token names from Step 1, the sidebar width if D9 passed, and remove the two exceptions that Step 3 and the `RealmPicker` change cure. Record D1–D3 under *Do's and Don'ts* as declined, with the reasons.
- [ ] **Step 7:** Capture the before and after sets at scale 1.5 for the five screens in §Source and compare them. The only expected visible change is the headline tracking and the eyebrow spacing.

### Task A10: Fewer lines — structure from space and shade

**Why:** the user compared a policy's Billing tab with a reference settings page (2026-10-09). The policy screen draws about 20 horizontal rules; the reference draws about 6 and gets its structure from whitespace and a grey header band. Agreed target: only three kinds of line remain — **a box outline, the faint line between table rows, and the tab underline.**

**Files:** `components/PageHeader.tsx`, `components/Panel.tsx`, `components/Field.tsx`, `components/DataTable.tsx`, `components/AppShell.tsx`, `index.css`, `features/documents/PaymentScheduleTable.tsx`, `features/policies/PolicyDetailPage.tsx` (rail), `DESIGN.md`

**Outcome (2026-10-09):** steps 1, 3–9 and 11 done, verified at 1280×600 ×1.5. Deviations:
- **Step 1:** the plan's idea of keeping the page bar unruled on tabbed records was tried and **reverted**. The tabs span only the work column, so the rail slid under the bar with no edge, its text sliced off under the title. That was screenshot 7's "leak". The bar is ruled whenever `main` is scrolled, on every page.
- **Step 2:** the leak was not a sticky-offset mismatch. The probe measured `--pagebar-h` equal to the bar's rect height (101 = 101) and the tabs flush under it. The cause was the rail scrolling under an unruled bar; fixed by step 1.
- **Step 5:** a toned band showed white hairline seams between every pair of header cells at 150% scaling. Switching to separate borders didn't help (reverted). A 1px band-coloured `box-shadow` to the right of each `th` covers them. Dark band is `0.215` (above `--surface`; it was below it).
- **Step 7:** `Panel` gained an `actions` slot; `PaymentScheduleDownloads` is a separate export used by the policy Billing tab and the customer's Premiums panel.
- **Step 9:** "Term" and "Matures" collapse to "Term and maturity: None on record" only when both are absent. "No fixed term" would claim something the backend can't distinguish.

- [ ] **Step 1: Page header.** Remove the resting `border-b`. Show a hairline only while `main` is scrolled (a scroll listener sets `data-scrolled` on the bar, and CSS draws the rule). On a tabbed record the tab rule is then the only rule at the top.
- [ ] **Step 2: Fix the leak under the sticky header.** Content shows through between the page bar and the sticky tabs at 150% scaling. Measure `--pagebar-h` against the bar's `getBoundingClientRect().height` (fractional versus `offsetHeight`'s rounded integer). If that's the cause, publish the fractional height, or overlap the tabs 1px under the bar.
- [ ] **Step 3: Panel.** Keep the outline; drop the `border-b` under the title block, and keep the spacing.
- [ ] **Step 4: Field rows.** Drop the per-row `border-b`; separate rows with `py-2`. A `note` moves to sit under the label, left-aligned, in Micro / Subtle Ink, instead of being right-aligned under the value.
- [ ] **Step 5: Header band.** Darken `--band` until the table header reads as a band rather than as two rules (light: about `--control`, 0.965; dark: one step above `--surface`). Check muted header ink stays ≥ 4.5:1 on it. Remove the rule under the header row.
- [ ] **Step 6: Table-only panels.** Where a `Panel` holds nothing but a `DataTable`, the panel title row and the table share one box with no rule between title and band.
- [ ] **Step 7: Payment schedule header.** Merge the title row and the "party · product · premium · PDF · Excel" toolbar into one row: title and subtitle on the left, PDF/Excel on the right. The party/product/premium line repeats the page header and the rail; drop it.
- [ ] **Step 8: Sidebar.** Remove the `border-t` above the user block; the nav's existing scroll shadow marks the edge.
- [ ] **Step 9: Not-applicable rail fields.** "Term —" / "Matures —" with an explanatory note collapse to one line ("No fixed term") or are hidden. Same reasoning as F8.
- [ ] **Step 10: Unchanged on purpose.** Input borders stay (WCAG 1.4.11, 3:1); the reference's faint field edges would fail it. Row hairlines stay, because they carry the eye across a row.
- [ ] **Step 11: `DESIGN.md`.** Reword "hairline rules and tonal surfaces carry all structure" to the three-line rule above, and update *Panel*, *Tables* and *Field* to match.
- [ ] **Step 12:** Capture the five review screens plus the Billing tab at scale 1.5 and count the horizontal rules before and after.

Not decided, so not in scope: sentence-case sidebar group labels instead of uppercase, and two-line name/email rows in the client and agent lists.

---

## Plan B — interaction feedback

**Outcome (2026-10-09), branch `console-polish-b`:** all of B1–B8 done.
- **B1:** press tones on every button variant, row, nav item, chip and section link, with `active:duration-0`; new Control Press token.
- **B2:** `LoadingBlock` is three pulse lines with a **visible** label. Visible on purpose: e2e waits on `getByText(label)).not.toBeVisible()`, which an sr-only label would satisfy immediately. Pulses moved to the Control tone.
- **B3:** most tabs already kept their data through the store's `loading(previous)`. Only the 4 local-state panels needed `lib/remembered`.
- **B4:** 59 `useForm` calls spread `VALIDATE_ON_TOUCH`, enforced by a guard; a behaviour test fails without it.
- **B5:** the "362 titles" was mostly component props; about 20 were real. The meaningful ones moved to `Tip`, focusable only where rare.
- **B6/B7:** verified in the browser.
- **B8:** `useListKeys` plus a hint in the palette.

### Task B1: A press you can feel (I1)

**Files:** `components/ui/button.tsx`, `components/DataTable.tsx`, `components/AppShell.tsx` (nav items), tests

- [ ] **Step 1:** Button base: add `active:duration-0` so the press ground lands on pointer-down with no 150ms fade. Per variant: primary and danger `active:opacity-80`; secondary `active:bg-control-hover`; outline and ghost `active:bg-selected`. Colour only: `DESIGN.md` forbids transform and lift.
- [ ] **Step 2:** Give the row activation button and the nav items the same `active:bg-selected active:duration-0`.
- [ ] **Step 3:** Record in `DESIGN.md` → *Buttons* that the "target gap" is closed.

### Task B2: Skeletons where content lands (I2)

**Files:** `components/states.tsx`, the detail pages (policy, party, claim, underwriting case, schemes), tests

- [ ] **Step 1:** Add `PanelSkeleton` (a ruled header plus N field rows at staggered widths) and `RailSkeleton`, built like `TableSkeleton` (Quiet Paper pulse).
- [ ] **Step 2:** Replace `LoadingBlock` inside panels and rails on the detail pages first. Keep `LoadingBlock` only where the shape is unknown.
- [ ] **Step 3:** Under `prefers-reduced-motion: reduce`, stop the pulse (task B7).

### Task B3: Instant tab revisit (I3) — gated on D4

**Files:** the tab-content loaders in `features/policies/*`, `features/party/*`, a small `lib/` cache

- [ ] **Step 1:** Add a module-level `Map` keyed by request URL, holding the last successful response, cleared on sign-out and after any mutation on that record.
- [ ] **Step 2:** In the tab loaders, show the cached response at once and refetch in the background. The refetch's result replaces it, and a failed refetch keeps the cached data with the inline error above it.
- [ ] **Step 3:** Tabs remain unmounted, and `DESIGN.md` → *Two shapes* gets one sentence about the cache.

### Task B4: Validate on touch (I4) — gated on D10

**Files:** the 44 `useForm` call sites (a pattern replacement), the tests that assert submit-time errors

- [ ] **Step 1:** Add `mode: 'onTouched', reValidateMode: 'onChange'` to every `useForm` call. Add a guard: every `useForm({` names a `mode`.
- [ ] **Step 2:** Run the unit tests and fix any test that relied on errors appearing only after submit. Keep the behaviour change, and adjust the test to blur the field.

### Task B5: Tooltips that everyone can reach (I5)

**Files:** a new `components/ui/tooltip.tsx`, the meaningful `title=` sites

- [ ] **Step 1:** Wrap `@radix-ui/react-tooltip` in a primitive that is keyboard-focusable and shows on focus, with a 300ms open delay and Floating-menu shadow (the popover family).
- [ ] **Step 2:** Triage the 362 `title=`. Migrate the ones carrying meaning: nav badge explanations, an unrecognised status literal, disabled-action reasons (`DESIGN.md` → *Buttons → Disabled* already asks for a tooltip), truncated text. Delete those that only repeat visible text. Leave the rest.
- [ ] **Step 3:** Add a guard: no `title=` on a disabled `Button`.

### Task B6: Popovers that grow from their trigger (I12)

**Files:** `components/DatePicker.tsx`, `components/PartyPicker.tsx`, the pickers built on Radix Popover

- [ ] **Step 1:** Add `origin-[var(--radix-popover-content-transform-origin)]` with a 120ms fade and 96%→100% zoom (`tw-animate-css` is already loaded). That is one curve, mirrored on close.
- [ ] **Step 2:** Add these two to the motion vocabulary in `DESIGN.md` → *Motion*.

### Task B7: Respect the person's settings (I11)

**Files:** `index.css`, `components/ui/sheet.tsx`, `DESIGN.md`

- [ ] **Step 1:** Under `@media (prefers-reduced-motion: reduce)`: the slide-over and mobile nav cross-fade (no translate), the skeleton pulse stops, the popover zoom becomes a fade, and the spinner stays because it reports status.
- [ ] **Step 2:** Under `@media (prefers-contrast: more)`: redefine `--border` as `--border-strong`, and darken `--muted-foreground` and `--subtle-foreground` one step, in both themes (*Both Themes Rule*).
- [ ] **Step 3:** `prefers-reduced-transparency` needs nothing while D2 is declined. Note that in `DESIGN.md`.

### Task B8 (optional): Keyboard paths for heavy queues

**Files:** `components/DataTable.tsx`, the list pages

- [ ] `j`/`k` move the row focus, `Enter` opens it, and `/` focuses the list filter. Active only when no field has focus, and listed in the command palette's help.

---

## Plan C — flows

### Task C1: Don't lose half-built work (I6) — gated on D7

**Files:** `App.tsx`, `screens.tsx`, a new `lib/useUnsavedGuard.ts`, the long forms

- [ ] **Step 1:** Move `<BrowserRouter>` + `<Routes>` to `createBrowserRouter` built from the existing manifest. Run the full e2e suite once on its own commit before anything else, because every route is touched.
- [ ] **Step 2:** `useUnsavedGuard(isDirty)` = `useBlocker` + `beforeunload`. The block shows an inline `ConfirmAct`-style prompt ("Leave without publishing? The version you were building is lost."), with verbs "Leave" and "Keep editing". No modal, per `DESIGN.md` → *Confirm Act*.
- [ ] **Step 3:** Wire it into `PublishVersionForm`, `CreateProductPage`, `IssueGroupSchemePage`, `IssuePolicyPage`, `OpenUnderwritingCasePage`, `RegisterClaimPage`, and any other form above ~300 lines.

### Task C2: A product form with a map (I7)

**Files:** `features/products/CreateProductPage.tsx`, `PublishVersionForm.tsx`

- [ ] **Step 1:** Lay the form out in `DetailLayout`. The work column keeps the form, and the 320px rail becomes a live **summary** of the version being built (product type, term, benefits, funds, rate bands). It shows facts only and no action, per the *Record-Rail Rule*.
- [ ] **Step 2:** Put a `SectionNav` over the form's sections (Basics, Benefits, Terms, Rates, Funds…) so the seventh section is a jump, not a drag.
- [ ] **Step 3:** Run the product e2e specs. The section headings become landmarks, so check that nothing matched them as plain text.

### Task C3: Finance you can find your way in (I8) — gated on D8

**Files:** `screens.tsx`, `components/AppShell.tsx`, e2e that clicks Finance items

- [ ] **Step 1:** Split the `finance` group into five groups, each gated as Finance is today: **Collections** (Arrears, Field receipts, Bank transfers), **Payouts** (Payouts, Payment runs, Maturities, Withholding rules), **Ledger** (GL postings, Chart of accounts, Accounting periods, Accounting policies, Posting rules, Unposted events, Manual journals, Journal templates), **IFRS 17 & close** (IFRS 17 engine, Year-end close, Funds), and **Reinsurance & returns** (Treaties, Reinsurance statements, Regulatory returns).
- [ ] **Step 2:** Give each nav item across the console its own lucide icon, and add a unit guard that no two items in one realm share an icon.
- [ ] **Step 3:** Update the e2e specs that reach these screens by nav name.

### Task C4: Audit every mutation's outcome (I9) — gated on D5

**Files:** the ~54 mutation call sites; this plan's appendix

- [ ] **Step 1:** List every mutation with: the screen, what visibly changes on success (a badge, a row, nothing), and whether a `Receipt` is shown. Commit the table as an appendix to this plan.
- [ ] **Step 2:** For each "nothing visible" row, add a `Receipt` from the server's response, with an `onward` link to the natural next step ("Policy issued → Open policy").
- [ ] **Step 3:** For each row where a badge changes, check that focus goes to the changed status or that the change is announced; add `role="status"` where it isn't.

### Task C5: A "Today" home (I10) — gated on D6

**Files:** a new `features/home/TodayPage.tsx`, `screens.tsx` (`homeFor`), `navBadges.ts`

- [ ] **Step 1:** A page of count rows built only from the badge counts already fetched (`navBadges.ts`), filtered to the signed-in roles: "**205** underwriting cases open", "**15** claims to assess", etc. Each row is a link to its filtered list. Use `CountLine` styling, not stat cards, because the numbers aren't for comparing.
- [ ] **Step 2:** `homeFor` lands staff realms there. An empty queue says so ("Nothing waiting for you") rather than hiding the row.

---

## Plan D — withdrawn, kept on record

Not to be offered again unless `DESIGN.md` changes:

| Proposal | Why withdrawn |
|---|---|
| Raise content text from 12px to 14px | The compressed scale is a confirmed decision (D1). |
| Tighter table rows | The rows are already 44px; the screenshots were taken at 150% scaling. |
| Translucent, blurred sticky bars | "Not … glassy"; flat is law (D2). |
| Spring physics and drag-to-dismiss on the slide-over | "Almost none" motion; desk-first console (D3). |
| Remove the slide-over's scrim | `DESIGN.md` specifies the 20% backdrop; the drawer is dismissable on purpose. |
| A receipt on every mutation | Receipts are for invisible outcomes only (D5); replaced by the audit in C4. |
| Theme-switch cross-fade | Motion without information; not in the vocabulary. |

---

## Suggested order

1. **A1–A5**: one-line-to-small fixes with no decisions needed. Best as one branch, one merge.
2. **A9**: typography tokens and `DESIGN.md` refresh. It's a branch of its own because it touches many files.
3. D1–D11 are recorded in §3; then **A6–A8**.
4. **A10**: fewer lines.
5. **Plan B**, in order B1 → B7 (B8 optional).
6. **Plan C**: C1's router move on its own commit with a full e2e run, then C2–C5.

Full e2e after each plan, not after each task (milestone cadence); affected specs after each task.
