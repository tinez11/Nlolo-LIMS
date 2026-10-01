---
target: frontend/src/features
total_score: 31
max_score: 40
na_heuristics: 
p0_count: 0
p1_count: 2
timestamp: 2026-09-29T06-19-40Z
slug: frontend-src-features
---
**Method: dual-agent** (A: unanchored design review · B: detector + measured evidence), run isolated, neither seeing the other. First attempt died on an opus session limit; both re-run on Sonnet. Every finding re-verified against source by the synthesizing context before inclusion.

## Correction: the migration seam does not exist

The run was briefed that redesign plans 3 (claims) and 4 (finance) were never written, so those lanes would be unmigrated. That briefing was stale and wrong. `b7f29be` (plan 3, claims) and `b8774db` (plan 4, finance) are both merged to `main`, along with `0e85d76` (plan 5 doc). Both agents caught it independently:

- A, by reading: `ClaimsPage.tsx`, `GlPostingsPage.tsx`, `ArrearsPage.tsx`, `TreatyDetailPage.tsx` are structurally indistinguishable from `PoliciesPage.tsx`.
- B, by counting: `PageHeader` adopted in 38/38 screens across all 13 feature directories; `Panel`, `InlineError`, `DataTable` appear throughout the supposedly-unmigrated lanes.
- B, by screenshot: claims list and underwriting list render the same `PageHeader`, same filter-chip row, same `TableSkeleton`.

Only `RecordTabs` (3/38, party + policies) and `SectionNav` (1/38, claims only) have narrow footprints, and those read as per-screen layout choices justified in code comments. The console is one product.

## Design Health Score

| # | Heuristic | Score | Key Issue |
|---|-----------|-------|-----------|
| 1 | Visibility of System Status | 3 | `PartyDetailPage.tsx` fires seven independent panel loads with seven separate "Loading…" texts and no aggregate signal |
| 2 | Match System / Real World | 3 | `UnderwritingCaseDetailPage.tsx:304` renders `ASSESSMENT_TYPES` as raw enum literals, breaking the platform-wide "never render a raw enum" discipline |
| 3 | User Control and Freedom | 3 | `IssuePolicyPage.tsx` is a ~25-field all-or-nothing form with no draft persistence, and is specifically the recovery path used after something failed |
| 4 | Consistency and Standards | 4 | 8 guard rules in `designGuards.test.ts`, zero raw colour values in tree, `PageHeader` at 38/38 |
| 5 | Error Prevention | 3 | Declining a life (`DecisionPanel.tsx:178`) and publishing a priced version (`PublishVersionForm.tsx:811`) submit on one click with no `ConfirmAct` |
| 6 | Recognition Rather Than Recall | 3 | `AgentDetailPage.tsx:157` shows "Own plan override" as a raw GUID |
| 7 | Flexibility and Efficiency | 3 | No bulk actions; `CommandPalette` resolves only `POL-` numbers |
| 8 | Aesthetic and Minimalist Design | 4 | Consistently under-decorated; `ChartOfAccountsPage.tsx` is densest and still reads cleanly |
| 9 | Error Recovery | 3 | `states.tsx` generic 409 copy undersells available specificity |
| 10 | Help and Documentation | 2 | No help affordance in `AppShell.tsx`; partly offset by strong inline `Field` notes and gate copy |
| **Total** | | **31/40** | **Good** |

Prior snapshot scored 19/40 but was slug `frontend-src` on 2026-09-02, before the redesign existed. Not like-for-like.

## Design Specificity Verdict

Authored for this product, not category-interchangeable. Structural evidence:

- `DisclosurePanel.tsx:22` records the disclosure question as literally asked, not against a canonical bank, because a non-disclosure dispute turns on exact wording.
- `ClaimSettlementPanel.tsx:308` renders no payee field for a credit-life claim; the lender is payee by contract, hard-coded as prose.
- `UnderwritingCaseDetailPage.tsx:204` has a permanent "No policy was issued" panel because `AFTER_COMMIT` issuance can fail after a decision commits.
- `EftExecutionPage.tsx` exists because one settlement rail has no callback integration, and demands a bank reference because paid-but-untraceable is a named failure mode.

**Deterministic scan: clean.** `detect.mjs` returned `[]` over both `frontend/src` and `frontend/src/components`, exit 0, confirmed twice independently. That means no bundled rule fires, not that the UI is good.

**Visual overlays: none.** No overlay injected; none exists. B captured 10 Playwright screenshots (5 screens × 1440×900 and 390×844) instead.

## Both automated sweeps missed the real defect

A found by reading that `IssuePolicyPage.tsx:644` explains a disabled button's reason only through a `title` attribute. B's accessibility sweep reported "only 5 native `title=` tooltips, all on non-interactive `<span>`" — clean for interactive elements. B is wrong: the attribute is applied through a spread, `{...(blocked ? { title: '...' } : {})}`, which a `title=` grep cannot see. Detector clean, grep clean, defect found only by reading.

Worse than A described: `Button` sets the real HTML `disabled` attribute (`button.tsx:86`), so the button is not focusable. The `title` is unreachable by keyboard and by screen reader — only mouse users hovering a greyed-out button ever see it.

## What's Working

1. **The Gate/Confirm doctrine, where applied.** `GatePanel` and `ConfirmAct` share one silhouette on purpose (`ConfirmAct.tsx:22`). The `reversal` prop is mandatory and checked against real backend behaviour, so `ClaimSettlementPanel` states an honest asymmetry. Ten feature files use it.
2. **Honest absence.** Three distinct renderings for three kinds of nothing: em dash for "nothing to say" (`StatusBadge.tsx:40`), spinner for loading, ring-and-`?` for unrecognised status (`StatusBadge.tsx:44`).
3. **The floor is mechanically held.** Zero raw colour values used as styles; zero `outline: none`; one global `:focus-visible`, never suppressed; 8 guard rules that actually run (8 passed, 18.79s).

## Priority Issues

**[P1] Two of the four high-stakes actions have no confirmation step**
Why it matters: `PRODUCT.md` states the consequence of being wrong is money or compliance. Claim settlement, EFT execution, reinstatement and KYC gate behind `ConfirmAct`. Declining a life and publishing a priced version do not, and a decided case closes to further evidence except when `POSTPONED`.
Fix: Wrap both submits in `ConfirmAct`, following `ClaimSettlementPanel.tsx:351`.
Evidence: `DecisionPanel.tsx:178`, `PublishVersionForm.tsx:811` — verified neither imports `ConfirmAct`.
Suggested command: `$impeccable harden`

**[P1] Blocking reasons are unreachable without a mouse**
Why it matters: `title` on a `disabled` button reaches nobody using keyboard or screen reader. `DecisionPanel.tsx`'s `blockedByRank` warning (`role="status"`, line 157) is never wired to the button it explains, while `FormField` in the same file wires field errors correctly via `aria-describedby`.
Fix: Give the warning an `id`, point the button's `aria-describedby` at it; replace the `title` spread on `IssuePolicyPage` with the same pattern. Consider `aria-disabled` plus an inert handler where the reason must stay reachable.
Evidence: `IssuePolicyPage.tsx:644`, `DecisionPanel.tsx:157-183`, `button.tsx:86`.
Suggested command: `$impeccable audit`

**[P2] Filter-chip rows break the design system's own ≤4 rule**
Why it matters: The system polices small numbers tightly elsewhere (Six Buckets, Two Peaks, `PANEL_ROW_CAP = 6`), but busiest registers show 7-9 simultaneous chips, worst for the arrive-and-triage rhythm `PRODUCT.md` describes. Screenshots confirm a 7-status row live on claims, wrapping to 2-3 lines on mobile.
Fix: Keep the 3-4 statuses staff triage against visible; collapse the tail into a "More" menu.
Evidence: `PoliciesPage.tsx:261` (8), `ClaimsPage.tsx:252` (8), `ArrearsPage.tsx:267` (9).
Suggested command: `$impeccable layout`

**[P2] The recovery form has no resilience to interruption**
Why it matters: `IssuePolicyPage` exists to recover from failures — `UnderwritingCaseDetailPage:219` sends an underwriter there after automatic issuance fails. Highest-anxiety moment, likeliest to be interrupted, loses everything on navigation.
Fix: Persist to `sessionStorage` keyed by underwriting case id, cleared on success. No `Idempotency-Key` ties the form to a single attempt.
Evidence: `IssuePolicyPage.tsx`, no draft persistence anywhere in the file.
Suggested command: `$impeccable harden`

**[P3] Audit-trail reason fields accept a single character**
Why it matters: Both fields exist to feed the audit trail per their own error copy, yet validate `min(1)`, so `x` satisfies a compliance record.
Fix: Raise to a length that forces a sentence, with copy saying why.
Evidence: `policyIssueForm.ts:97`, `decideForm.ts:33` — both verified `min(1)`.
Suggested command: `$impeccable clarify`

## Cognitive Load: 3 of 8 failed — moderate

Passing: single focus (one-emphasis-panel rule, reasoned about at `GroupSchemePage.tsx:196`), grouping, visual hierarchy, working memory, progressive disclosure (`PANEL_ROW_CAP = 6` with honest "N more").

Failing: chunking and minimal choices (the chip rows), and one-thing-at-a-time — `ClaimDetailPage` keeps 4-5 sections on one scroll so an assessor can read evidence while writing findings. Defensible trade, still a real cost.

Decision points above four visible options: `PoliciesPage.tsx:261` (8), `ClaimsPage.tsx:252` (8), `ChartOfAccountsPage.tsx:406` (7-8), `ArrearsPage.tsx:267` (9), `IssuePolicyPage.tsx:526` (5, collapsed).

Residual working-memory gap: after approving a credit-life settlement, `Receipt` names the destination in prose ("Finance → Bank transfers") with no link.

## Emotional Journey

Peak: `ConfirmAct`'s mandatory `reversal` prop, forcing every irreversible action to state its consequence in real values and be honest about asymmetry. `Receipt` is the other peak: persistent, not a toast, server-confirmed facts only, left where the form was.

Valley: the exception path. `IssuePolicyPage` is reached when something already went wrong, and is the least forgiving surface on the platform.

Against the four high-stakes moments: repudiating a claim — excellent. Settling money — excellent. Declining a life — absent. Publishing a priced version — absent.

## Persona Red Flags

**Alex (power user).** Pastes a claim UUID into Ctrl+K, gets "No screen matches." `jump.ts:10` resolves only `/^POL-[A-Z0-9]+$/`; the comment explains why (a bare UUID could be claim, party or case). Principled, still a daily tax. Clearing 12 same-session bank transfers is 12 full arm→type-reference→confirm cycles (`EftExecutionPage.tsx:59` holds one confirmation slot by design).

**Sam (screen reader, keyboard-only, 200% zoom).** Tabs toward "Issue policy" to learn why it is unavailable and never reaches it: it is `disabled`, so it is skipped in tab order, taking its `title` explanation with it. Same shape on `DecisionPanel`. The needed infrastructure already exists and is good — one global never-suppressed `:focus-visible`, 78 `aria-label`s, `pointer-coarse:h-11` touch targets, an explicit WCAG 2.5.3 comment at `AppShell.tsx:148` — so this is a narrow wiring gap, not a systemic failure.

**Baraka (UNDERWRITER, from `PRODUCT.md`'s role table).** Works a queue, several decisions an hour. Reads recommendation, reads disclosures, picks from a 4-option select, types a one-line reason, clicks "Record decision" — done. No second click, no restated consequence. A stale select value plus inattention accepts a case that should have been declined, and the case closes to further evidence. Sharpest break in the review.

## Minor Observations

- Three detail screens render enumerable sub-content with no empty state: `ProductDetailPage`, `RegulatoryReturnDetailPage`, `UnderwritingCaseDetailPage`. Loading and error coverage is 100% across all 38 screens.
- `PoliciesPage`, `ClaimsPage`, `GlPostingsPage`, `ArrearsPage` each hand-repeat an identical filter-strip wrapper — a `ListToolbar` extraction candidate.
- `DESIGN.md` self-reports two live drift points and 16 remaining sub-floor type sizes. Acknowledged debt.
- No `@media` or `@container` rules exist; all 26 responsive breakpoints are Tailwind prefixes concentrated in shared primitives. Mobile captures confirm it works — sidebar collapses to hamburger, `DataTable` drops its secondary column, no horizontal scroll on any of five screens.
- Party list screenshots are full of `E2E Clients Fixture …` rows — known dev seed-rot, not a UI issue.
- GL postings rendered a genuine permission-denied state under the underwriter identity, with a `trace <uuid>` and a `PageHeader` count degrading to "— journal entries · could not load". Well-built guard state, reached legitimately.

## Questions to Consider

1. `ConfirmAct` is applied rigorously to money and claims but stops exactly at underwriting decisions and product publication. Deliberate boundary, or unwritten? Should it be the default for any action that closes a case or changes a live price?
2. The command palette refuses to search records because no search endpoint exists, yet nine screens each build their own name-search box. When does "render only what the platform can prove" become "don't invest in read infrastructure"?
3. If a seventh and eighth status bucket keep arriving, is the Six Buckets Rule for colour still solving the cognitive-load problem, or has it moved into the filter row?
4. `CUSTOMER_SERVICE_REP` is explicitly undesigned yet inherits the entire `REALM_STAFF` surface. Should an undesigned role render less by default?
