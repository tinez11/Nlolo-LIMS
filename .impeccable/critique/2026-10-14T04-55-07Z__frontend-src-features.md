---
target: frontend/src/features
total_score: 29
max_score: 40
na_heuristics: 
p0_count: 1
p1_count: 3
timestamp: 2026-10-14T04-55-07Z
slug: frontend-src-features
---
Method: dual-agent (A: design review, isolated · B: detector evidence, isolated). All load-bearing claims re-derived against source before synthesis.

# Design Critique — `frontend/src/features`

## Design Health Score

| # | Heuristic | Score | Key Issue |
|---|-----------|-------|-----------|
| 1 | Visibility of System Status | 3 | 43 submit buttons use `disabled={status==='loading'}` — announces "unavailable" when it means "working"; 6 swap their own label mid-flight |
| 2 | Match System / Real World | 3 | `underwriting/DecisionPanel.tsx:160` prints raw enum "recommends **LOADED**" above a Select reading "Accept with a loading" |
| 3 | User Control and Freedom | 3 | URL carries tab/filter/page; `UnsavedGuard` on long forms. Row activation means three different things |
| 4 | Consistency and Standards | 2 | 29 hand-rolled tables, none using `bg-band`; 80 `rounded-lg border` panel lookalikes vs 37 `Panel` importers; 3 section-nav idioms where DESIGN.md documents 2 |
| 5 | Error Prevention | 2 | `gates/` is excellent; the two highest-blast-radius actions have no guardrail (P0) |
| 6 | Recognition Rather Than Recall | 3 | Pickers mean no uuid is typed; `finance/UnpostedEventsPage.tsx:75` shows raw `eventType`/`sourceRef` |
| 7 | Flexibility and Efficiency | 3 | Palette teaches `j`/`k`/`/` as universal; `[data-row-activate]` on 16 of ~38 destinations, `[data-list-filter]` on 7 |
| 8 | Aesthetic and Minimalist | 3 | Zero hard-coded colour values in `features/`. Three Lines Rule broken in 2 places |
| 9 | Error Recovery | 3 | `states.tsx` writes copy per error kind + `traceId` with `select-all`; array-editor errors escape it |
| 10 | Help and Documentation | 4 | Earned. `ConfirmAct`'s mandatory third line; `FormField` hint-vs-error; "A recommendation, not a decision" |
| **Total** | | **29/40** | **Good** |

All ten heuristics apply (Operate surface); none scored n/a.

## Design Specificity Verdict

**Authored for this product in the shared chrome and older feature areas; category-interchangeable in `features/finance/`.**

Authored evidence: `ConfirmAct` makes `reversal` a required prop; `StatusBadge.tsx:60` rings unrecognised backend literals (a decision about enum drift); `gates/claimGates.ts` refuses as-at-date coverage because the backend ignores `asOf`; `DatePicker.tsx` is 612 hand-written lines because day-first ambiguity is an operational error.

Interchangeable evidence (verified counts): `features/finance/` imports `PageHeader` 14 times and then builds everything below from scratch — 0 `Panel`, 0 `DataTable`, 0 `DetailLayout`, 1 `ConfirmAct` across 14 files, 13 files with hand-rolled `<table>`.

**Deterministic scan:** `detect.mjs --json` over `frontend/src/features` → `[]` exit 0; over `frontend/src/components` → `[]` exit 0. Same with `--no-config --no-design-system`.

**The clean is half-vacuous.** `detector/engines/regex/detect-text.mjs:32` gates the page-analyzer family on `PAGE_ANALYZER_EXTS = {.html,.htm,.astro,.vue,.svelte}` AND `isFullPage()`. No `.tsx` satisfies either; ~50 rules (the copy/typography/palette family) never ran. A probe `.tsx` full of marketing buzzwords scored zero. What the clean does prove: 5 rules genuinely fire on Tailwind in `.tsx` (`side-tab`, `gradient-text`, `gray-on-color`, `ai-color-palette`, `bounce-easing`) and all 2,779 `className` usages pass. Gradients total 4, all in `AppShell.tsx:222-225`, all achromatic scroll shadows.

**Visual overlays: none for the target.** Vite found on :5173 and browser-bundle injection succeeded, but only reached the realm picker; `features/` is behind Keycloak SSO. Its one finding (`gradient-text` on `body`) is a proven false positive — 0 of 149 live CSS rules contain `background-clip: text`.

## Overall Impression

A console with an unusually strong conscience and an uneven hand. Parts built first are better than most commercial insurance software. Parts built most recently were built as if the first parts did not exist. The biggest opportunity: the module handling the general ledger is the one module that opted out of the design system, in the role owning 22 of 38 destinations.

## What's Working

1. **`ConfirmAct`'s required `reversal` prop** — converts a writing habit into a compile error and forces the author to read the backend. Recoverable actions say so, so irreversible warnings keep their force.
2. **The refusal layer in `lib/` and `gates/`** — `lib/money.ts` compares via BigInt cents and returns `NaN` for uncomparable amounts so "cannot compare" cannot read as "within the limit".
3. **`AppShell` + `PageHeader` + `DetailLayout` as a self-documenting layout contract** — `--pagebar-h`/`--sectionbar-h` published and consumed; the shell comment records the 161px overflow bug and four failed fixes.

## Priority Issues

### [P0] Posting to the general ledger and closing the financial year are single, unconfirmed clicks

**What.** `finance/ManualJournalDetailPage.tsx:228` and `finance/YearEndClosePage.tsx:89` are both `<Button variant="primary" onClick={() => void act(...)}>Approve and post</Button>`. No `ConfirmAct`, no restated figures, no consequence line. The *Reject* form beside each requires a typed reason.

**Why it matters.** `ConfirmAct` is used at 20 sites; waiving an invoice, reinstating a policy, executing an EFT, settling a claim and publishing a product version all ask twice. Maker-checker catches the wrong person, not the right person's slip, and reversal is a compensating journal — the audit trail carries the mistake permanently.

**Fix.** Wrap both in `ConfirmAct`, `tone="danger"`, consequence restating server-returned figures ("Post Dr TZS 4,120,000.00 / Cr TZS 4,120,000.00 to period 2026-12, 6 lines"), reversal reading "Nothing unposts it. A mistake is corrected by a reversing journal, which stays on the record alongside this one."

**Suggested command:** `$impeccable harden frontend/src/features/finance`

### [P1] `features/finance/` is a second design system wearing the first one's page header

**What.** 0 `Panel`, 0 `DataTable`, 0 `DetailLayout`, 1 `ConfirmAct`, 14 `PageHeader`, 13 files with hand-rolled `<table>`. No `bg-band` header (0 of 29 repo-wide), `font-normal` headers vs the spec's Label/500, `py-1` cells against the 44px Row Rule, no row activation, numerics not right-aligned. **Seven** raw `toLocaleString` money helpers in `finance/`, so the ledger prints `1,240,000.00` where the console prints `TZS 1,240,000.00`. Pattern has spread to `reinsurance/statementForm.ts`, `reinsurance/StatementPage.tsx`, `documents/PaymentScheduleTable.tsx`, `documents/SavingsStatementTable.tsx`. `manualJournalForm.ts:149` sums with `Number` and `+=` — against `lib/money.ts`'s own header: "There is no arithmetic in this module on purpose — the backend computes every total."

**Why it matters.** `screens.tsx` gives FINANCE_OFFICER/ADMIN 22 of 38 destinations. The role with the most screens gets the least design, in the module where misreading a figure is the risk.

**Fix.** (a) Replace read-only registers (`ManualJournalsPage`, `UnpostedEventsPage`, `JournalTemplatesPage`, `PostingRulesPage`, `PolicyRegisterPage`, `ExpenseAllocationPage`, `EngineRunPage`) with `DataTable`; (b) replace panel lookalikes with `Panel`; (c) delete the seven local helpers, render through `formatMoney`, and take the server's total or move `totals()` to BigInt cents. Keep hand-rolled tables only where cells are inputs.

**Suggested command:** `$impeccable polish frontend/src/features/finance`

### [P1] The destination of a claim settlement is a free-text box with a placeholder for a label

**What.** `ClaimSettlementPanel.tsx:337` — `<FormField label="Payee reference"><Input placeholder="Mobile-money destination" /></FormField>`, validated by `z.string().trim().min(1)` only. The typed value is echoed into the confirmation and into `EftExecutionPage`'s "Pay" column.

**Why it matters.** `EftExecutionPage.tsx:93` already records the real consequence: "finance was shown 'mobile' as the destination of an 800,000 transfer." The credit-life branch was fixed by naming the lender from the policy; the individual branch — the commonest claims — was not. In the same file the *amount* publishes its ceiling as a `hint` before typing; the *destination of the money* gets a placeholder that vanishes on first keystroke and never reaches a screen reader.

**Fix.** Offer the platform's candidates first (claimant + named beneficiaries) via the existing `PartyPicker`/`PartyName`, with "Another destination" revealing free text plus a format hint. Minimum one-line fix: move the placeholder into `FormField`'s `hint` prop. Related: `ClaimSettlementPanel.tsx:268` accepts any `^[A-Z]{3}$` as free-text currency on a TZS-only platform.

**Suggested command:** `$impeccable harden frontend/src/features/claims`

### [P1] A vendor's name is printed to policyholders in a console that must be white-label

**What.** `customer/CustomerHomePage.tsx:42` ("Your cover with Nlolo Life.") and `customer/CustomerPoliciesPage.tsx:26` ("Every policy you hold with Nlolo Life.") — the only two brand strings in `frontend/src`.

**Why it matters.** PRODUCT.md Positioning is binding ("No single insurer's brand may be baked in… vendor-neutral and plausibly white-labellable"); DESIGN.md's Don'ts repeat it. Nlolo is the platform *vendor*, so a policyholder of tenant insurer X is told their cover is with the software company. `AppShell.tsx:142` uses an "LP" monogram with a comment explaining why.

**Fix.** Delete both. "Your cover." / "Every policy you hold." mean the same and are true for every tenant.

**Suggested command:** `$impeccable clarify frontend/src/features/customer`

### [P2] Repeated-row editors drop the ARIA wiring `FormField` exists to guarantee

**What.** `FormField.tsx` centralises `role="alert"`, `aria-describedby`, `aria-invalid` and the error border. Array editors render root/row validation outside it as a bare `<p>`: `BeneficiariesPanel.tsx:238` (the "must sum to 100%" error), `CommissionPlanPanel.tsx:214,233`, `ManualJournalEditorPage.tsx:161,167`. Separately, **163** `<th>` carry no `scope` against 15 that do.

**Why it matters.** These errors fire on submit, after focus has moved — exactly what `role="alert"` is for. A screen-reader user submits a beneficiary split, hears nothing, cannot discover why the form didn't advance. It also lets the red border and the announcement disagree.

**Fix.** Give `FormField` an `as="group"` mode (`<fieldset>`/`role="group"` with its own `aria-describedby`) for array roots; route per-row errors through the row's own `FormField`. Add `scope="col"` in the same pass.

**Suggested command:** `$impeccable audit frontend/src/features`

## Cognitive Load — 4 of 8 fail (high)

Failing: chunking, visual hierarchy, ≤4 options per decision point, progressive disclosure.

- `finaccounting/ChartOfAccountsPage.tsx:426` — 11 toggles in one wrapping row (6 account types + 3 posting modes + 2 flags), three dimensions separated by two unlabelled `w-px` rules (also the only two vertical rules in the repo).
- `claims/ClaimsPage.tsx:255` — 8 chips, each a full coloured `StatusBadge`; `PoliciesPage` the same.
- `policies/PolicyDetailPage.tsx:349` — up to 12 record tabs in an `overflow-x-auto` strip with no scroll shadow and no overflow affordance, in a work column narrowed by a 240px sidebar and 320px rail. The *sidebar* got four hand-built scroll shadows for exactly this reason.
- `products/PublishVersionForm.tsx` — 40 `<option>` across 9 sections in 1,533 lines.
- Sidebar at full ADMIN — 38 destinations across 14 groups, three exceeding four items.

Hierarchy: `customer/CustomerHomePage.tsx:82` gives a stat figure `text-headline`, the tier DESIGN.md reserves for the page `<h1>`; and 29 hand-rolled tables have no `bg-band`, so header and body sit at the same tone.

## Emotional Journey

**Peak:** `claims/ClaimSettlementPanel.tsx` — ceiling before typing, starting figure with its author, stated shortfall in money, confirmation naming amount and payee, reversal line that is a checked fact, then a persistent `Receipt` rather than a toast.

**Valley:** the ledger. The approver who confirmed waiving one invoice posts the December closing journals on a single click. `Receipt` is mounted on exactly two surfaces; the finance approver's end-state is the page quietly changing shape.

## Persona Red Flags

**Alex (impatient power user).** The palette teaches `j`/`k`/`/` as universal; dead on all eight Ledger screens and `UnderwritingQueuePage`. Row activation means three different things behind one identical affordance (slide-over on ClaimsPage/PoliciesPage/ClientsPage/ProductsPage/GlPostingsPage/TreatiesPage/RegulatoryReturnsPage; navigation on ArrearsPage/PayoutsQueuePage/MaturitiesPage/AgentsPage/PaymentRunsPage/FieldReceiptsPage). `ArrearsPage.tsx:246` guards on `if (c.policyNumber)`, making a row without it a focusable, pressable button that does nothing and explains nothing.

**Sam (accessibility-dependent).** Unannounced submit errors in the three array editors. Six buttons renaming themselves mid-flight. 43 submit buttons announcing "unavailable" when they mean "working" (heaviest: `unitlinked/U2Panels.tsx` 6, `accounts/AccountPanel.tsx` 5) while `ui/button.tsx:55` documents why that's wrong and ships `pending`. 163 unscoped `<th>`. Needs a visual check before acting: `FilterChip.tsx:53` marks selection with `ring-2 ring-accent` while `:focus-visible` draws `outline: 2px solid var(--color-ring)`; `--ring` and `--accent` are both `oklch(0.16 0 0)` in **light mode** and differ only in dark, so on the 8-chip claims row "selected" and "focused" may differ by 2px of offset. Worth recording positively: skip link with a 19-tab-stop justification, `inert` behind the mobile drawer, focus returned to trigger, `prefers-reduced-motion` and `prefers-contrast: more` both honoured in both themes.

**FINANCE_OFFICER / FINANCE_APPROVER (worst-served role).** 22 of 38 destinations; the eight Ledger screens are the only ones outside the design system. Money loses its currency code in their own area. The figure they check before posting is computed in-browser in floating point and printed as a left-aligned sentence in a `colSpan={5}` cell, so the two compared numbers are not in a column — on a platform setting `tabular-nums` globally so figures compare vertically. No nav badge counts a journal awaiting approval or an unclosed period: every other operational role has a queue.

## Minor Observations

- `underwriting/DecisionPanel.tsx:160` — one `humanizeStatus` import fixes the raw-enum recommendation.
- Two raw `<Input type="date">` survive: `finance/ManualJournalEditorPage.tsx:131` (GL auto-reversal date) and `policies/GroupSchemePage.tsx:812` (member `joinedOn`, which drives when cover starts) — both render `MM/DD/YYYY` on an en-US machine.
- `components/FormOutline.tsx` is a third section-navigation idiom, undocumented in DESIGN.md.
- `reporting/ReportSectionTable.tsx:32` hand-builds a `Panel` pixel-identical to the real one — the mechanism by which the finance divergence began.
- The seven slide-overs are structurally identical and not one hosts a mutating action. The preview/act doctrine is perfectly held.
- **Documentation drift (reported, not acted on):** PRODUCT.md states the `customers` realm "mounts no screens at all, deliberately", while `features/customer/` holds 11 routed screens with a 23-line presence in `screens.tsx`.

## Questions to Consider

1. If `ConfirmAct` is doctrine, why is it a component rather than a gate in the store? Would requiring a `confirmedBy` token on the six idempotent mutations make the omission a type error instead of a review finding?
2. The console refuses to compute a total because the backend owns the number — then sums the ledger in JavaScript. Is `manualJournalForm.ts:149` an oversight, or a real gap (no server-side "is this journal balanced" endpoint) the doctrine should name?
3. Was `features/finance/`'s divergence a schedule decision, or did the shared components genuinely not fit a ledger? If the latter, `DataTable` needs a totals-row contract and DESIGN.md a ledger-table section. If the former, this argues for a merge gate failing any PR introducing a `<table>` outside `components/`.
4. Twelve tabs is no longer "separate jobs done by separate people". Is the policy record still one record, or four product shapes sharing a URL?
