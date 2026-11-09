# Reports redesign: comparisons, the computation tree, claims variance, solvency and risk

Date: 2026-11-09. Status: design agreed with the user in conversation; awaiting their review of this document.

## 1. Why

The user (2026-11-09): the reports module "has just basic reports" and "no good UI". They asked for:

- more reports on policies, claims, reinsurance and commissions;
- solvency, RBC, ORSA, PCR/SCR, asset-liability management, forecasting, stress tests and an AML report;
- the computation behind every figure: an income statement total must open "until it reaches the figure summed up";
- reports that are concise, clear and accurate, and a beautiful UI that follows DESIGN.md.

What exists (checked 2026-11-09):

- `reporting` module: 12 reports. Management: policies in force, lapse rate, persistency, new business, premium collections, clients, policies by product. Accounting: income statement, balance sheet, trial balance, general ledger.
- Elsewhere: IFRS 17 statements and engine runs, treaty bordereaux and statements, agent commission statements, TIRA regulatory returns.
- One generic report page: a filter bar, plain tables with a total row, and Excel/CSV/PDF export. There are no comparisons, no charts, no headline figures and no way to see how a total was reached. The income statement is leaf accounts per chart class, with one result line.

## 2. Decisions (the user, 2026-11-09)

| # | Decision |
|---|---|
| D1 | Variance means **this period against the prior period**: the prior month or quarter, or the same period last year. No budget and no actuarial A/E for now. |
| D2 | The 12 existing reports are kept and upgraded, not replaced. |
| D3 | The main menu keeps **one** "Reports" item. Inside the page, a **searchable report list grouped by area**. Each area is **collapsed by default**; the open report's area opens itself. |
| D4 | The computation is shown **as a tree inside the table (option B)**. ▸ on a line opens its parts beneath it, indented, down to postings and source transactions. Expand all / Collapse all. |
| D5 | Solvency is built on **placeholder risk-charge factors**, clearly marked, until the user supplies TIRA's Risk-Based Capital guideline. The factors are settings, not code. |
| D6 | The UI follows DESIGN.md ("The Lit Figure"). |
| D7 | Reports elsewhere (IFRS 17, treaty statements, agent statements, TIRA returns) are listed in the report list with ↗ and open their own pages. |

## 3. Principles every report follows

1. **Accurate.** Every figure, sum, change and % is computed on the server. The browser never adds anything up (DESIGN.md: "Don't sum, average or difference in the browser"). A computation tree's children are served with the parent's figure, and the server checks that they add up. A failed check is shown on the line, not hidden.
2. **Concise.** One answer per report, on the figures band. At most four stat tiles. One chart. One main table. Notes only where a figure needs them.
3. **Clear.** Plain words for line names. Every comparison shows the prior figure, the change and the %, with an arrow and "up" or "down". Green or red is decided by whether the change is good or bad for the insurer, never by its sign alone.
4. **Traceable.** Every figure that is a sum opens as a tree. A ledger figure opens: report line → GL accounts → postings → source transaction (receipt, claim payment, commission, journal). An operational figure (claims, policies) opens: total → groups → the records themselves.
5. **Comparable.** Every report takes "Compare with: prior period / same period last year / none". Filters and the comparison live in the URL, as today.
6. **Exportable.** Excel and PDF carry the comparison columns, subtotals and the expanded lines. CSV stays flat.

## 4. The screens

### 4.1 Reports page

- **Left, inside the page:** a 240px report list.
  - A search box on top, then areas: Accounting, Claims, Policies, Clients, Reinsurance, Commissions, Solvency & risk, Regulatory.
  - Each area shows its report count and is collapsed by default.
  - NEW marks new reports for the first release. ↗ marks reports that live on another page.
- **Opening Reports with no report chosen** shows a short home: each area's key figure (profit, claims incurred, policies in force, the capital adequacy ratio) with its change, each linking to its report.
- On a phone, the list becomes a "Choose a report ▾" control above the report.

### 4.2 A report

Top to bottom:

1. **Page bar:** the report title; a one-line description saying the period and what it is compared with; Excel and PDF.
2. **Filter bar:** period, compare with, then the report's own filters (branch, product, channel, claim type, agent). Controls follow the existing `ReportFilterBar` and are Control-ground fields.
3. **Figures band:** up to three Display figures, the answer the report exists for. Each has its caption "up X (Y%) from Z".
4. **Stat tiles** (`StatTile`): up to four breakdown figures, each with a green or red change pill.
5. **One chart**, from `components/charts` in the chart palette. Bridge (waterfall) for movements and variances; labelled share bars for breakdowns; a trend line for cohorts and forecasts. The bridge chart is a new in-house chart.
6. **The table**, on a card:
   - columns are this period | prior | change | %;
   - subtotal rows in bold, the grand total last;
   - ▸ on any line that has parts;
   - an opened line shows a check row: "✓ 1,102.0 + 96.3 + 42.2 = 1,240.5", the server's sum.
   - Postings lists show the first 20, then "show all" and "download".
7. **Notes**, only where a figure needs one: for example "Placeholder risk charges, not TIRA's" as an amber strip under the page bar.

Empty, loading and error states use the shared set. A report with no data for the period says so in one line and still shows the comparison period's figures.

## 5. The catalogue

### 5.1 Accounting (upgraded)

| Report | Content |
|---|---|
| Income statement | IFRS 17 presentation with subtotals: insurance revenue (4000) − insurance service expenses (5000) = **insurance service result**; ± net reinsurance (6000); ± finance and investment result (7000); − other expenses and tax (8000) = **profit or loss**. Figures band: revenue, insurance service result, profit. Chart: bridge from the prior period's profit to this period's, one bar per line. Tree: line → accounts → postings → source. |
| Balance sheet | Assets (cash, investments by class, receivables, reinsurance assets, other), liabilities (insurance contract liabilities, payables, other) and equity, each with subtotals. A check line shows that assets equal liabilities plus equity. This date vs the prior year-end, or the prior month. Tree as above. |
| Trial balance | Opening + movement = closing per account, plus a prior-period column. The ledger check stays. |
| General ledger | Postings for an account with a running balance. Each posting links to its source transaction. It is also the leaf level of every ledger tree. |

### 5.2 Claims (new, variance against the prior period)

| # | Report | Figures band | Content |
|---|---|---|---|
| C1 | Claims incurred variance | Claims incurred, number, average | Change split into **count effect** (extra claims × the prior average) and **severity effect** (this period's count × the change in average). **Large claims** (above a threshold, default 20m, a setting) are shown separately. Bridge chart. Table by product → claim type → claims. |
| C2 | Loss ratio variance | Loss ratio, claims, earned premium | Claims incurred ÷ earned premium by product. The change is split into claims moving and premium moving. Earned premium comes from the GL (4100). |
| C3 | Claim outcomes | Approved, declined, open (count and amount) | Decline rate and decline reasons against the prior period. |
| C4 | Approved vs paid | Approved, paid, unpaid balance | Differences from loan netting, deductions and part-payments. The unpaid balance is aged 0–30, 31–60, 61–90 and 90+ days. |
| C5 | Turnaround | Days to decision, days to payment | Against a target (a setting; default 30 days to pay) and against the prior period. The claims that missed the target are listed. |
| C6 | Early claims | Claims within 1 year, within 2 years | By agent and branch, against the prior period. The main indicator of fraud or weak underwriting. |
| C7 | Gross, recovered, net | Gross claims, recoverable, received | Per claim: the reinsurance share under the treaty, what was received, and the gap. |
| C8 | Outstanding claims movement | Closing outstanding count and amount | Opening + registered − paid − declined ± amended = closing. Every line opens its claims. |

The claims data comes from `claims` (type, dates, approved amount, decline reason, settlement), `policy` (product, issue date, branch, agent, channel), `benefitpayout` and `payment` (paid amounts and netting), and `reinsurance` (recoveries). The reporting module already projects policy facts from events. It gains a claim projection on the same pattern: events plus a replay rebuild, an `applied_event` table for idempotency, and per-row facts so any period works.

### 5.3 Policies and clients (upgraded)

- **Policies in force:** an opening → closing movement (+ new + reinstated − lapsed − surrendered − matured − claimed − not taken up = closing). By product, branch and channel. "Policies by product" folds into it as a breakdown.
- **New business:** count and annualised premium against the prior period and the same period last year; by channel, agent, product and branch.
- **Lapse rate:** against the prior period; by policy year (1, 2, 3+), agent and branch.
- **Persistency:** 13th- and 25th-month persistency by cohort, with a trend line.
- **Premium collections:** billed vs collected (collection rate), arrears ageing (30/60/90+ days), and a split by payment method.
- **Free-look and not taken up** (new): cancellations in free-look and offers that closed unpaid, by product and agent.
- **Clients:** adds new clients in the period against the prior period, clients without verified KYC, and policyholders without portal access.

### 5.4 Reinsurance (new)

Business ceded per treaty (sums at risk, premium ceded, retention); bordereau reconciliation (sent vs confirmed); settlements due (due, received, outstanding); reinsurance commission and profit commission (earned vs received); net retained risk by product. The treaty statements stay where they are, linked with ↗.

### 5.5 Commissions (new)

The commission earned → booked → paid movement; by agent, branch and supervisor, including override tiers; commission ÷ premium by product against the plan; clawbacks from lapses and free-look cancellations; unpaid commission ageing. Agent statements stay where they are, linked with ↗.

### 5.6 Solvency and risk (new)

All figures are as at a month-end. The inputs are GL balances through that month (`FinaccountingApi.trialBalanceThrough`) and the in-force book from the reporting projection.

- **Capital adequacy (RBC)**
  - **Available capital** = equity (3000) + approved reserves − inadmissible assets. Which accounts are inadmissible is a setting.
  - **Required capital** combines four risk charges:
    - insurance risk: a factor × insurance contract liabilities (2xxx), by portfolio;
    - market risk: a factor per asset class account (equities 1230/1231, bonds 1215/1216/1240, property 1260, collective schemes 1250, deposits 1220, bills 1210);
    - credit risk: a factor × receivables (13xx) and reinsurance assets (14xx);
    - operational risk: a factor × premium over the last 12 months.
  - The combination formula (default √(I² + M² + C²) + O) is part of the factor set.
  - **Capital adequacy ratio** = available ÷ required, against the 100% minimum. PCR, MCR and SCR are extra threshold lines, named as TIRA names them.
  - A gauge shows where the ratio stands against the minimum. Bars show required capital by risk.
  - Tree: ratio → each charge → each account balance × its factor.
- **Solvency factors (settings):** a factor set with a version, an effective date, a name and an author, and a flag `placeholder`. While the active set is a placeholder, every solvency report carries the amber strip "Placeholder risk charges, not TIRA's". The first set is seeded as a placeholder. Changing factors means a new version, so a past report can be re-run on the factors it used.
- **Stress tests:** re-run the RBC under named shocks: equities −20%, interest rates ±1%, deaths +15%, lapses +30%, the largest reinsurer failing, and a catastrophe claim. Each shock is a setting (name and parameters). The report shows the ratio before and after each shock and ranks them. Asset shocks act on account balances; mortality and lapse shocks scale the insurance-risk charge. This is stated on the report as an approximation until the IFRS 17 engine can re-project under them.
- **Forecast:** premium, claims, profit and the capital adequacy ratio for the next 12 months. It is a trend from the last 24 months plus the in-force book's scheduled premium. It is labelled "trend forecast", with the method stated, and shows a range (low/central/high).

### 5.7 Later projects (not in this design's build)

- **Investments register, then asset–liability management:** holdings with amount, maturity, rate and currency; maturity buckets; duration gap; liquidity cover.
- **ORSA:** assembles RBC, stress tests and the forecast with the board's narrative sections and an approval step.
- **AML monitoring, then the AML report:** screening, rules, alerts and case files, with thresholds and deadlines from the FIU regulations. Sources conflicted: TZS 10m vs 20m cash threshold, 24 hours vs 3 days to report.

Each needs its own design.

## 6. Architecture

- The **`reporting` module** stays the home of every report here. It reads other modules only through events and their public `*Api`. The Spring Modulith boundaries are unchanged except for new allowed dependencies: `claims::api`, `reinsurance::api`, `distribution::api`, `billing::api`, as needed, each checked by ModularityTests.
- **`ReportResult` grows, backward-compatibly:**
  - `figures` (the figures band and stat tiles: label, value, prior, change, %, good-or-bad);
  - `chart` (kind and series, values from the server);
  - a section row may carry `children` (lazily fetched) and `check` (the server's sum statement).
- **The computation tree** is an endpoint: `GET /reports/{key}/lines/{lineId}/parts?…filters`. It returns the line's parts, each with its figures for both periods and whether it has parts of its own. Postings are paged (20, then "show all"). The server asserts that the parts sum to the line, and returns the check text and a `balanced` flag.
- **Comparison** is a filter (`compareWith=PRIOR_PERIOD|SAME_PERIOD_LAST_YEAR|NONE`). The server computes both periods and the change.
- **Claims projection:** the claim facts the variance reports need, built from claims events with the same replay-rebuild mechanism. It is reconciled once against `claims` to prove it complete.
- **Solvency:** a `SolvencyFactorSet` (versioned, with a placeholder flag) in `reporting`. RBC, stress and forecast are reports in the same framework.
- **Frontend:**
  - one `ReportsPage` with the report list and the report;
  - `ReportFigures` (band and tiles), `ReportChart` (adds a bridge chart to `components/charts`), `ReportTable` (the tree, check rows, comparison columns);
  - the existing `ReportFilterBar` gains "compare with".
- **Exports** render the same figures, tree (opened lines) and comparison columns.

## 7. Accuracy checks (tests)

- Every ledger report's figures equal the trial balance for the same months (as today). Every tree's parts sum to their line, asserted in the endpoint and in tests.
- The claims projection matches the claims table: count and approved amount per type and month.
- C1's count effect + severity effect = the total change, exactly, with rounding shown on the severity line.
- Placeholder factors produce the strip; a non-placeholder set removes it.
- The prior-period figures equal the same report run for the prior period on its own.
- UI: design guards (12px floor, chart palette, no browser sums) and accessible names for the tree toggles.

## 8. Build order

1. **Report page redesign + Accounting:** figures band, tiles, bridge chart, comparison, the computation tree; income statement, balance sheet, trial balance, general ledger.
2. **Claims C1–C8** and the claims projection.
3. **Solvency and risk:** factor sets (placeholder), RBC with PCR/MCR/SCR lines, stress tests, forecast.
4. **Policies and clients upgrades;** free-look and not taken up.
5. **Reinsurance and Commissions.**

Later: the investments register and ALM, then ORSA, then AML (each designed separately).

## 9. Open items

- TIRA's Risk-Based Capital guideline (factors, combination formula, thresholds, return template) is needed to replace the placeholders.
- The large-claim threshold (default 20m) and the claims turnaround target (default 30 days) are settings; the user may set them.
- Swahili for reports is out of scope: the staff console is English.

## 10. Batch 3 as built (2026-11-09)

- **Factor sets** live in `reporting.solvency_factor_set` (V3), one JSON document per version, insert only. A month uses
  the newest version in force at its end; with none, the built-in placeholder set (version 0, `PlaceholderFactors`).
  Rates are written in per cent ("4" is 4%). Published from `/staff/solvency-factors` with the new permission
  `reports.solvency-factors` (built-in ADMIN; iam V3 grants it to existing tenants' admin roles).
- **Available capital** = 3000 equity + the year's result not yet closed into it (credit − debit of classes 4000–8000)
  − inadmissible assets (placeholder: 1375, 1385, 1610, 1620, 1650, 1750). No separate "approved reserves": 3220
  statutory reserve is already inside equity.
- **Required capital**: each charge is its accounts' balances × rate, each account rounded to the cent, so the tree adds
  up exactly; a risk is never negative (a "raised to zero" line shows when it would be). Operational = rate × class 4000
  revenue over the 12 months to the month. Combination √(I² + M² + C²) + O, or a simple sum.
- **Thresholds** (placeholder): MCR 100%, SCR 125%, PCR 150%. The lowest is "the minimum" in the stress test.
- **Stress test** ranks shocks by capital over required capital after the shock, not by the ratio: with negative
  capital a shock that raises required capital makes the ratio less negative.
- **Forecast**: least-squares line through up to 24 months of the ledger (leading empty months dropped); the range is
  ± one standard deviation of the months around the line (cumulative on capital). The other risk charges are held
  where they stand; the operational charge is re-read on the trailing 12 months' premium. The in-force book's
  scheduled premium is NOT used yet.
- Every solvency report carries `notices` (the amber strip): the placeholder warning, and the method or approximation.
  Exports print them under the header.
