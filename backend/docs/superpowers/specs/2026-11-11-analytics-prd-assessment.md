# Analytics, Reporting & Dashboard PRD — assessment against the platform (2026-11-11)

DLICP in the PRD = this platform. This document checks every PRD section against what the platform already has
(code and dev database, 2026-11-11), names what is missing, flags where the PRD is wrong or ambiguous for a life
insurer, and says how each part should be built under the platform's own principles. Nothing here is built yet.

## Verdict in one paragraph

The PRD's principles are right and mostly match ours already (one source of truth per module, read-only analytics,
idempotent event projections, no second calculation engine, drill-down, plain status words). About a third of it
exists: 25 reports with filters, period comparison and Excel/CSV/PDF export; four role dashboards (finance,
underwriting, claims, agent) whose tiles link to work queues; an action bell; a reporting projection fed by events
with replay-safe `applied_event` and a rebuild. The large gaps are: **no management dashboard, no drill-down from a
report figure to its records, no prospect concept, no underwriting or claim-stage history in reporting, no premium-
invoice facts (only collections), no branch-level access, no agent targets, no scheduled reports, no KPI catalogue**.
Several PRD items need a business decision before they can be built honestly (prospects, renewals, branch access,
attribution, collection-rate definition, earned premium while the IFRS 17 engine has never run).

## How we will build it — the platform's principles applied to the PRD

1. **One owner per fact.** Analytics reads module APIs and events; it never writes operational state (PRD 3.1 —
   already our rule; `reporting` owns only its projection tables).
2. **No invented numbers.** Where a figure has no reliable source (prospects, earned premium, targets), the page says
   so in plain words instead of showing 0 (PRD 25; our "never a fake zero" rule).
3. **Plain words first, codes small.** Every KPI, status and column in words a branch manager understands; codes only
   as secondary text (finance walkthrough items 65, 94, 104, 157, 160).
4. **Every figure explains itself.** A KPI shows its period, its "as of", and a one-line definition from the
   catalogue, and opens the records behind it (PRD 3.5, 19).
5. **Maker–checker for anything that changes meaning.** KPI definitions, targets and scheduled distributions that
   send customer data out are proposed by one person and approved by another (our two-person rule).
6. **Fewer menus, not more.** One "Reports" group with areas, and the role dashboard as the landing page — not eleven
   new sidebar entries (the staff sidebar is already 40+ items).
7. **Copy the nearest existing pattern.** New reports use the existing `Report` interface, `ReportFilter`,
   `ReportSection`, the report page layout and the exporter; new projections follow `ReportingEventHandlers`.
8. **History is recorded, never reconstructed.** Status changes, attribution and branch are captured when they happen
   (as `report_policy_status_change` and the frozen `branch_code` already are); missing history is declared, not guessed.

---

## Section-by-section assessment

Legend: ✅ exists · 🟡 partly · ❌ missing · ⚠️ PRD needs correcting / decision

### PRD 3 — Fundamentals

| PRD | Platform today | Assessment |
|---|---|---|
| 3.1 Single source of truth | ✅ Modules own data; `reporting` keeps projections only; Spring Modulith enforces boundaries | Keep. Analytics must not add writes. |
| 3.2 Consistent KPI definitions | 🟡 Each report has a description; definitions live in code per report; the dashboard reuses report figures (`ReportFigures`) | Build a **KPI catalogue** (PRD 19), generated from code, read-only (same choice as posting rules, D10). |
| 3.3 Dimensions | 🟡 Filters: period / as-of / cohort, product, category, agent, sales channel, branch, client type; group-by product, category, agent, channel, branch | Missing: week/quarter/year buckets, region, claim type and payment status as filters, customer drill. Region exists on parties (NBS wards) but not in projections. |
| 3.4 Operational vs historical | 🟡 Operational = work queues and dashboards (live module reads); historical = projections | Correct split already. Make it explicit on each page ("live" vs "as of last refresh"). |
| 3.5 Drill-down everywhere | ❌ Report tables have no row links; dashboard tiles link to queues but lose the filter | **Biggest UX gap.** Add a `link` column type to `ReportColumn` (policy, claim, client, agent, invoice) and carry filters into queue URLs. |

### PRD 4 — Navigation

⚠️ The PRD wants 11 sidebar entries. We already have a **Reports** group with an area tree (`reportAreas`) and a
role **Dashboard** as landing page. Recommendation: keep one Reports entry; add areas *Customers & prospects*,
*Underwriting*, *Collections*, *Portfolio*, *Claims*, *Distribution*, *Products & branches*, *Finance*, *Scheduled*.
Add a **Management overview** as a dashboard (not a report) for the roles that need it.

### PRD 5 — Management overview dashboard

| PRD | Platform today | Gap / note |
|---|---|---|
| Global filters, compare, last updated | 🟡 Dashboards have a range picker; reports have compare-with | No branch/product/agent filters on dashboards; no "last updated". |
| A. Business growth | 🟡 New business, clients reports exist | ❌ prospects (no concept), ❌ prospect→application conversion. Application→policy conversion is computable (cases vs issued). |
| B. Portfolio | 🟡 In-force, lapse rate, persistency, products | ⚠️ "Renewed" does not apply to individual life (see PRD 9). "Cancelled" must map to our statuses (Not taken up, Free-look cancelled, Surrendered, Claim paid — D1). Approaching maturity exists (Maturities page). |
| C. Collections | 🟡 Premium collections report (cash by method, arrears ageing) | ❌ invoiced / due / due today / upcoming as reported facts; ❌ collection rate (no agreed definition — see PRD 8). |
| D. Claims | 🟡 Claim outcomes, turnaround, approved vs paid, outstanding | ❌ "awaiting documentation" and "under assessment" counts in reporting (exist in claims tables, not projected). |
| E. Operational efficiency | 🟡 Role dashboards + action bell | ❌ turnaround targets (no SLA config except offline-receipt SLA and dunning days). |
| 5.5 Every card clickable with context | 🟡 Tiles link to queues | Must carry period/branch/product into the destination. |

**Recommendation:** a *Management overview* dashboard built from existing report figures + four live queue counts,
each card with period, definition and a link. Phase 1.

### PRD 6 — Prospects and customers

⚠️ **We have no prospect.** What exists: agent quotations (saved quotes), the public quote link
(`omnichannel.public_application`), and parties registered by agents. There are no stages, follow-up dates,
lost reasons or assignment.
- The PRD's nine-stage CRM is a new module (Customer/CRM), not analytics. It conflicts with "don't build a second
  operational system" unless the business decides to build a prospect register as an operational feature first.
- **Decision P1:** (a) build a light prospect register in `party`/`omnichannel` (prospect = a quote or public
  application not yet a proposal; stages: Quoted → Applied → Converted / Lost, with a lost reason and follow-up date),
  or (b) report only on what exists (quotes, public applications, proposals) and skip prospect stages.
  Recommended: **(b) now, (a) later** — conversion from quote → proposal → policy is computable today without CRM.
- Customer analytics: ✅ Clients report (registrations, KYC). ❌ customers with multiple policies, with arrears, by
  branch (parties have no branch). ⚠️ "Avoid duplicate customers": we have no merge/master-record; duplicates are
  possible (same NIDA twice?). Needs a duplicate check before counting "distinct customers" honestly.

### PRD 7 — Underwriting

| PRD | Platform today | Gap |
|---|---|---|
| Status mapping | 🟡 Case statuses exist; underwriting dashboard counts open cases | ❌ No reporting projection for underwriting at all. |
| Application register | 🟡 The Underwriting queue is the live register | Missing columns: assigned underwriter (no assignment concept), days pending, last status change. |
| Ageing, performance, conversion | ❌ | Computable from `created_at`, `recommendation_at`, `decision_decided_at`, `sale_locked_at` — but only current state. |
| 7.4 Turnaround incl. paused time | ❌ | `underwriting.case_event` exists but is **empty on dev** — no status history is being written. Requirements/evidence timestamps arrive with underwriting build 4–6 (specced, not built). |

**Recommendation:** first make underwriting publish status-change events (or write `case_event`), then project
them like `report_policy_status_change`. Without that, turnaround and "paused" time cannot be measured — show
"history starts on <date>" rather than guessing.

### PRD 8 — Premium collection and receivables

| PRD | Platform today | Gap |
|---|---|---|
| Dashboard (due today/7/14/30, overdue, collected) | 🟡 Arrears queue, finance dashboard, collections report | ❌ invoice-based "due in N days" view; ❌ "collected today". |
| A–C Due / upcoming / overdue registers | 🟡 Arrears page (overdue cases, dunning) | Overdue ageing exists (5 bands). PRD wants 6 bands (1–7, 8–14, 15–30, 31–60, 61–90, 90+); ours are 1–30, 31–60, 61–90, 90+. |
| D Daily collection (opening → closing receivables) | ❌ | Needs invoice facts + payment facts in reporting. Today `report_collection` holds collections only. |
| F Agent collection | ❌ | Needs servicing-agent attribution (see PRD 11.4). |
| G Policy arrears | 🟡 Arrears page per invoice | Per-policy roll-up missing. |
| 8.4 Collection rate | ⚠️ undefined | **Our money model differs from the PRD's assumption.** Money lands on the *policy balance* (deposit) and the balance *pays premiums* as they fall due; savings pay invoices directly; agent cash now pays at once (1320). So there are three facts: **cash received** (deposits, by method), **premium settled** (paid from balance), **owed** (open invoices). Decision **C1**: collection rate = premium settled for invoices due in the period ÷ premium due in the period (invoice basis), with cash received shown separately (period basis). Prepayments, reversals (bounced cheques, expired agent cash), waivers and credits each get an explicit rule. |

Also: savings and deposit **contributions are not premium** (IFRS 9) — they must be shown apart or excluded from
"premium" KPIs; unit-linked premium includes an investment part. The PRD does not distinguish these.

### PRD 9 — Policy portfolio

| PRD | Platform today | Gap / note |
|---|---|---|
| Policy register | ✅ Policies page (live) + Policies in force report | Add last premium date / next due (billing join). |
| Lapsed, maturity | ✅ Lapse rate report; Maturities page | Lapse report rows lack outstanding premium. |
| Renewal report | ⚠️ | Individual life policies **do not renew**; only group schemes, lender schemes and annual funeral/group covers do. Define renewal per product type or drop it. |
| Movement (opening → closing) | 🟡 `report_policy_status_change` records every transition with effective date | Build the movement report from it; map our statuses: issued, activated, reinstated, lapsed, paid-up, surrendered, claim-terminated, matured, expired, not taken up, free-look. D1 (death showing as Surrendered) is already corrected in reporting (`CLAIM_TERMINATED`). |
| Persistency | ✅ 13th/25th-month by count and premium | ⚠️ PRD's 3/6/12/24/36 months is not the industry convention; recommend **13, 25, 37, 61 months** (as built) plus optional 3/6 for early lapse. |
| Product performance | 🟡 Policies by product, new business, loss ratio | ⚠️ PRD asks to "distinguish annualised, written, billed, collected premium" — we can: annualised (premium × frequency), billed (invoices), collected (settled). Written = issued annualised. Earned needs the IFRS 17 engine. |

### PRD 10 — Claims

✅ Mostly exists: claim outcomes, turnaround, approved vs paid, outstanding movement, incurred variance, early claims,
claims net of reinsurance. Gaps: "awaiting documents", "under investigation", "referred" stages (claims has
`claim_document_request`, `claim_assessment` tables but reporting projects only registered/approved/rejected/
settlement/settled/reopened); claims officer assignment (no concept); stage-by-stage turnaround. Controls in 10.4
are already true (approved from decisions, paid from settlements, reopen tracked).

### PRD 11 — Agents and distribution

| PRD | Platform today | Gap |
|---|---|---|
| Agent profile | 🟡 Agent page (commission, statements, login); agent dashboard | ❌ sales funnel, collection performance, portfolio quality on one profile. |
| Rankings | ❌ | Build from existing figures; no composite score (agree with PRD). |
| Targets | ❌ | No target entity. Needs a maker–checker target register (per agent, period, measure). |
| 11.4 Attribution | 🟡 `report_policy.agent_id` and `branch_code` are frozen at issue | ⚠️ `policy.AgentOfRecordChanged` exists but reporting ignores it. Decision **A1**: production stays with the selling agent (frozen); collections and portfolio quality follow the *servicing* agent from the change date. Needs an attribution history table. |

### PRD 12 — Product and branch

- Product: 🟡 comparisons exist; add the measures above.
- Branch: ⚠️ **Branch scoping is not built** (memory: branch register exists since 078eeb18; policies carry a frozen
  `branch_code` from the sale; clients and claims have no branch; staff users have no branch). "A user must not see
  branch information outside their access" requires (1) a branch on each staff user, (2) branch on claims/clients
  (derived from the policy), (3) row filtering in every report and queue. Decision **B1**: is branch-restricted
  access needed now, or is branch only a reporting dimension? Recommended: dimension now, access later.

### PRD 13 — Financial analytics

🟡 Trial balance, GL, income statement, balance sheet, capital adequacy, solvency forecast, stress tests, commissions,
reinsurance exist and read the ledger. ⚠️ Constraints the PRD does not know about:
- **Earned premium / insurance revenue for GMM/VFA products is not available** — the IFRS 17 engine has never run
  (findings 148, 158, D14). Any "earned" figure must say "engine results not yet posted".
- **Policyholder liabilities** (LRC/LIC/CSM) are only right after engine runs.
- Bank reconciliation status does not exist (finding 56); BooksMatch (register vs ledger) does.
- Regulatory return figures do not reconcile to the ledger today (finding 182) — the same must not happen here.

### PRD 14 — Universal report experience

✅ title, description, period, filters, summary figures, table, export (xlsx with letterhead, csv, pdf).
❌ drill-down, column visibility, saved filters, search inside a report, "last updated" on projection-based reports,
background export for large reports, export audit (to verify). Pagination exists in queues, not in report tables.

### PRD 15 — Scheduled reports

❌ None. ⚠️ Sending customer-level data by email is a data-protection risk: recipients must be **staff users only**
(no free email addresses), summary-level by default, two-person approval of a schedule that includes personal data,
and every send logged. Uses the existing exporter + communication (email via provider).

### PRD 16 — Alerts

🟡 The action bell shows **needed actions only, grouped by kind** (a deliberate decision). PRD alerts mix work items
(premium due, claim pending — fit the bell) with management signals (agent below target, high lapse rate — do not:
they are dashboard warnings). Keep the bell for actions; put signals on dashboards with thresholds configured under
maker–checker.

### PRD 17 — Role dashboards and access

🟡 Finance, underwriting, claims, agent dashboards exist; access is permission-based (`reports.view`, area
permissions, add-only grants). ❌ Senior management, branch manager dashboards. ✅ Finance officer/approver separation
is already preserved (viewing grants nothing). Agent: ✅ own book only (`AgentBook`), but agents have no reports.

### PRD 18 — Data model and architecture

| PRD | Platform today | Gap |
|---|---|---|
| Event-fed projections | ✅ `reporting` consumes policy, billing, party, claims events; `applied_event` makes replay idempotent; rebuild exists | ⚠️ PRD event names differ (InvoiceGenerated → `billing.PremiumInvoiceGenerated`, PaymentReversed → `billing.DepositReversed`/`PremiumPaidReversed`, etc.). Map, don't rename. |
| Status history | ✅ policies (`report_policy_status_change`) · 🟡 claims (dates per stage) · ❌ underwriting, ❌ invoices | Add `report_invoice` (due, settled, waived, reversed) and an underwriting stage history. |
| Facts kept separate | ✅ collections apart from policies, approvals apart from settlements | Keep; add invoice facts. |
| 18.5 Data quality | 🟡 Rebuild + idempotency; BooksMatch reconciles payments to ledger | ❌ projection-vs-source reconciliation (e.g. `report_collection` total vs billing receipts per month); ❌ failed-event list for reporting (finaccounting has Unposted events; reporting has none visible). |

### PRD 19 — KPI dictionary

❌ Build as a **read-only catalogue generated from the report definitions** (name, words, formula, numerator,
denominator, source, date basis, exclusions, owner, version). Changing a formula = a code change with a version
bump, like posting rules (D10). The PRD's "management must not change a formula informally" is satisfied by this.
Its initial KPI table needs our corrections: *premium collected* = premium settled (not deposits); *lapse rate* —
we already compute; *persistency* — 13/25 months; *agent production* — under rule A1.

### PRD 20–21 — UX and non-functional

✅ Mostly our existing standards (plain status words, colour plus text, responsive, TZS, a11y checks). ⚠️ "Modern
enterprise dashboard" must follow DESIGN.md ("The Lit Figure", navy + teal, figures band), not a new visual system.
Performance: volumes are small today; pre-aggregation only where a query is proven slow.

### PRD 22–25 — Phases, acceptance, out of scope, constraints

The constraints (preserve architecture, no fake data, declare gaps) match ours. The phases need re-ordering to put
**foundations that unblock everything** first: drill-down, KPI catalogue, invoice facts, underwriting history, and the
decisions below.

---

## Flaws and ambiguities in the PRD (to correct before building)

1. **Assumes a prospect/CRM module exists** — it does not (PRD 6, 11 sales funnel, 18.2 prospect events).
2. **"Renewal" for life policies** — individual life does not renew (PRD 5B, 9.2, 9.4, 11.2).
3. **"Cancelled"** is not one status here — Not taken up, free-look, surrender, claim-terminated, expired are
   different movements and must stay apart (PRD 9.2 movement report).
4. **Collection rate** assumes payments match invoices one-to-one; our balance model, agent cash and savings need
   the three-fact definition (C1).
5. **"Premium" is used for savings contributions and unit-linked investment** — must be separated (IFRS 9 / VFA).
6. **Earned premium and liabilities** assumed available — they depend on IFRS 17 engine runs that have never happened.
7. **Persistency intervals** (3/6/12/24/36) differ from the 13/25-month convention already built.
8. **Branch access** assumed to exist — branch scoping is not built; clients and claims have no branch.
9. **Agent targets** assumed to exist — no target register.
10. **Attribution** — PRD asks for history but not the rule (selling vs servicing agent).
11. **Underwriting "assigned underwriter" / claims "assigned officer"** — no assignment concept exists.
12. **Scheduled delivery** of customer data by email has no protection rules in the PRD beyond "restrict recipients".
13. **Alerts** mix actions and management signals — conflicts with the action-bell decision.
14. **Event names** are invented; must map to the platform's existing events (18.2 says so, but the KPI and model
    sections then rely on the invented names).
15. **Duplicate customers** — PRD assumes a master record exists; it does not.

## Decisions needed from the user

| # | Question | Recommended |
|---|---|---|
| P1 | Prospects: build a light prospect register, or report only quotes → proposals → policies? | **Decided 2026-11-11: (b)** — report on what exists (design below); a register (a) only if later asked |
| C1 | Collection rate definition (invoice basis, with cash received shown separately)? | **Agreed 2026-11-11.** The user: the main need is the **due premiums, so they can be followed up** — see "Premiums to follow up" below |
| R1 | "Renewal": which products renew (group schemes, lender schemes, annual covers)? | **Set aside by the user 2026-11-11.** No renewal reporting: nothing records a renewal (schemes and Familia roll on with no anniversary step). Individual products show expiring / maturing / expired / matured instead. Revisit only if a scheme renewal process is built. |
| A1 | Attribution (PRD 11.4): production to the selling agent, collections/quality to the servicing agent from the change date? | **Settled 2026-11-11 as the default:** sales count for the agent who sold (frozen at issue, already); collections and the follow-up list count for the policy's current agent (only schemes can change agent today). Changing the agent on individual policies is **left out** — not part of this work. |
| B1 | Branch: reporting dimension only, or also restrict what staff see? | **Decided 2026-11-11: (a)** — branch is a filter and grouping only; no branch-restricted access |
| T1 | Agent targets: needed in this redesign? Who sets them (maker–checker)? | **Decided 2026-11-11: (b)** — no targets; rankings and trends only |
| S1 | Scheduled reports: staff recipients only, summary by default, personal data needs approval? | **Decided 2026-11-11: no scheduled reports** — download by hand only. Instead: **more filters on reports, including quarters** (to confirm: "quotas" read as quarters) |
| K1 | KPI catalogue read-only from code (like posting rules)? | **Decided 2026-11-11: (a)** |
| X1 | Persistency at 13/25/37/61 months (plus 3/6 early lapse)? | **Decided 2026-11-11: keep** as built (13th/25th), with the additions recommended |

**Also decided 2026-11-11:** every dashboard and report in **simple English** staff understand; a clear
**Collections** section in Reports for following up due premiums, and approvals shown clearly; a mockup first
(https://claude.ai/artifact/NCgMMcFxs4Xj1D2xsrVbXf).

**Management role (2026-11-11, the user):** management is a **role** an administrator gives to a staff member, like
any other. A new built-in role *Management* (seeded in `BuiltInRoles`, changeable like the rest): view-only across
the areas — reports, dashboards, policies, claims, collections, finance figures — and **no act** (no approve, post,
pay or edit). A person holding it lands on the **Management overview** dashboard. Their downloads are logged like
anyone's.

**Underwriting dashboard (2026-11-11, mockup screen 7):** builds on what exists (open / in review / with a senior /
back from agents / accepted-not-issued, decisions by month, days to decide, % accepted). Adds: **Waiting for a
decision** first — counts by stage, an ageing bar (under 3 / 3–7 / 8–14 / over 14 days) and the oldest proposals
with where each one is; "accepted, then paid by the client" (offer → paying); **where the time goes** per stage, with
time waiting for the client or agent counted apart. Needs the underwriting stage history (Phase 0) — it starts on the
day it is built; older proposals show the total only, and the page says so. "Waiting for the client's medical report"
needs underwriting requirements builds 4–6 (specced, not built); until then that stage reads "back from agents".

**Agents see what they sold, in money (2026-11-11, the user):** checked first — today agents see only a count of
policies sold, their book by product (counts) and commission in money; no premium or cover totals, and no reports
(reports.view is staff-only). Decided: **premium sold = the yearly premium** of the policies they sold (monthly
TZS 100,000 counts TZS 1.2M), plus cover sold (sum assured) and premium collected from their customers; savings and
deposits shown apart as "savings deposits taken". **On the agent dashboard** (this month / quarter, against the
period before) **and in the agent's own "My sales" report with a wider timeline** — any dates, by month, quarter or
year, compared periods, opens the policies behind each figure, downloadable. Supervisors: "My team".

**Finance dashboard order (2026-11-11, the user):** **Collections first, at the top**, with a This month / This
quarter / This year switch — premiums due, paid, collection rate, overdue now, due today, and due-and-paid by
quarter — then "Waiting for you to approve", then the rest.

## P1 — decided design (2026-11-11): the Sales funnel, from what is already recorded

What is recorded today about people who have not bought: saved quotations (agent console and /quote link: owning
agent, product, figures, quoted date, valid-until, optional unregistered prospect, the case it became), public quote
applications, clients registered with no policy, and underwriting cases through to policy and first premium. No
stages, follow-ups or lost reasons — and none will be invented.

1. **Sales funnel** (period, product, agent, channel, branch): Quoted → Proposal opened → Accepted → Policy issued →
   Paid (on cover); Not taken up beside it. Each step: count, % of the step before, sum assured and annual premium,
   median days between steps; each number opens its records.
2. **Quotes that went nowhere**: past valid-until with no proposal — agent, product, amount, days since quote, the
   prospect's name and phone (restricted, below). The nearest honest view of "lost", without reasons.
3. **Clients who never bought**: registered, no policy — who registered them, when, any quote.
4. **Agent dashboard**: own funnel this month, own quotes expiring this week, own clients who never bought;
   supervisors see their team (same picker as My commission).

Rules: a quote counts in the period quoted, a policy in the period issued; conversion follows the quote cohort and the
page says so. Proposals with no quote enter at step 2, labelled. Group schemes and lender files are counted apart
(enrolments are not sales). Test (e2e) agents and products excluded by a flag, not by name.

Who sees it: staff with reports access — all; agents — their own; supervisors — their team; branch managers — their
branch once branch access exists (B1). Names and phones of non-customers: owning agent, their supervisor, staff with
reports access only; exports of these lists logged.

Needs: one new reporting fact (quotes: made, valid-until, became a proposal) and the underwriting stage history PRD 7
needs anyway.

Confirmed 2026-11-11: agents do not work a contact list before quoting — no prospect register (option (a) dropped).
The name-visibility rule above is confirmed.

## Premiums to follow up — decided design (2026-11-11)

The user: "the major thing wanted is the due premiums, so they can be followed through." A work list first, the rate
second. Replaces and absorbs the Arrears page's gaps (finance walkthrough items 5–9).

One page under Collections, three tabs: **Due soon** (next 7/14/30 days, chosen), **Due today**, **Overdue** (bands
1–7, 8–14, 15–30, 31–60, 61–90, 90+ days; "will lapse on …" where grace is ending).

Each row: client name and phone (tap to call), policy, product, agent, branch, amount owed, due date or days overdue,
and what was done: reminder sent (date), payment request sent (date), promise to pay, call notes.

Left off automatically: premiums covered by the policy balance; premiums whose cash an agent holds (shown "Agent cash
with <agent>"); a mobile-money request already waiting; offers not yet accepted.

Row actions: send payment request (mobile money, exists), send reminder SMS (templates), record a call (outcome,
promise-to-pay date, note), open the policy.

Decided:
- **Promise to pay is a note only** — it does not hold back automatic reminders or dunning. A promised date that
  passes unpaid brings the row back to the top.
- **Agents see their own** follow-up list on their dashboard ("Your customers to follow up") with the same actions
  except the mobile-money request, and **can download it** (Excel/PDF) for a hard copy. Agents also get their own
  reports (P1 funnel, their follow-up list) with download — scoped to their own book; supervisors to their team.
  Downloads of lists with customer names and phones are logged.

Top figures: due today, due in 7 days, overdue (count and amount), collected this month, collection rate (C1).

New data to record: call notes and promises to pay (one small table in billing, its owner). Everything else exists.

## Proposed build order (our phases)

**Phase 0 — Foundations (unblocks every page)**
1. Drill-down: `link` column type on report tables; dashboard cards carry filters to queues.
2. KPI catalogue page generated from report definitions; "as of / last refreshed" on every report.
3. Reporting facts: `report_invoice` (raised, due, settled from balance, waived, reopened), cash received (deposits by
   method incl. agent cash), agent attribution history (A1).
4. Underwriting stage history (events + projection) and claims stage dates (documents requested/received, assessed).
5. Reporting health: failed/ignored events list and projection-vs-source checks per month.

**Phase 1 — Management overview + collections** — management dashboard; due today/upcoming/overdue (6 bands),
daily and monthly collection with opening/closing receivables; policy arrears roll-up; collection rate (C1).

**Phase 2 — Portfolio and claims** — policy movement report; maturity obligations; persistency (X1); claims pipeline
by stage and stage turnaround.

**Phase 3 — Distribution, products, branches** — agent profile and rankings; agent collections; product and branch
comparisons; targets (T1) if approved.

**Phase 4 — Prospects, scheduling, alerts** — prospect register if P1(a); scheduled reports (S1); dashboard signals
with thresholds.

**Phase 5 — Financial analytics** — only after IFRS 17 engine runs exist (D14): earned premium, liabilities; bank
reconciliation (finding 56) first.

Each phase: tests on metric calculations against known fixtures, a reconciliation check against the source module,
and the affected e2e specs.
