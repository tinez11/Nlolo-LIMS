# Branch views for managers — design

**Date:** 2026-11-23
**Status:** approved in conversation, awaiting review of this written spec
**Part of:** the production-readiness items, third of three (after edge rate limiting and the server watch).
Branch `branch-views` from `main` (18b57b99). The screens were shaped with the impeccable skill.

## Why

A branch manager, or a finance or claims lead based outside head office, needs to see how *their* branch is doing,
and to follow a figure into the report behind it. Today nothing can be cut by branch except a report's "Group by:
Branch". Branch-scoped access was deferred on 2026-10-26 and stays deferred: this is views, not walls.

## Decisions (the user, 2026-11-23)

1. **B — branch views for managers.** Nobody is blocked from anything; anyone can pick any branch.
2. **(i) Start on your home branch when it is not head office**; head-office staff and staff with no home branch start
   on all branches. No new role.
3. **Scope:** policy-and-claims reports and the management, underwriting and claims dashboards get the branch choice;
   the finance dashboard and the accounting, solvency and reinsurance reports stay company-wide and say so. The choice
   carries between the dashboard and the reports it links to.
4. **A — branch figures from the reporting projections** (report_policy.branch_code, joined by policy number), not
   branch copied into claims and billing.
5. **Branch names** in the select and on screen; codes only in exports. **Nothing** extra for head-office staff.

## Which reports

Classified by where the figures come from. A report built on the reporting projections can be cut by the policy's
branch; one built on the ledger or the reinsurance treaties is company-wide (manual journals and company-level
postings carry no branch, so a branch cut would look whole and be partial).

| Branch choice | Reports |
|---|---|
| **Yes** | new-business, policies-in-force, persistency, lapse-rate, free-look-and-not-taken-up, products, clients, premium-collections, claim-outcomes, claims-incurred, claims-in-progress, claims-turnaround, claims-outstanding, early-claims, claims-approved-vs-paid, claims-net-of-reinsurance, loss-ratio |
| **Company-wide** | balance-sheet, trial-balance, general-ledger, income-statement, capital-adequacy, solvency-forecast, stress-tests, reinsurance, **commissions** |

**Commissions moved to company-wide while building (2026-11-23):** an agent is paid by statement across every policy
they sold, whatever its branch, so only the per-policy commission lines could be cut and the statements could not — a
branch view would look whole and be partial. Keys corrected to the code's own (`stress-tests`, `claims-turnaround`, …).

The plan verifies each "Yes" report against its source before wiring it; one found to read the ledger moves to
company-wide rather than being cut partially. **Clients:** a client has no branch, so a client is counted in a branch
when they hold a policy there; one with policies in two branches is counted in both, and the report's description
says so.

## Backend

- **`branch` request parameter** (a branch code, or absent for all branches) on `GET /reports/{key}` for the "Yes"
  reports, mapped to the existing `ReportFilter.branchCode`, which `ReportSql` already applies as
  `p.branch_code = ?`. A report not yet built on that filter gets the join it needs.
- **Report descriptions** gain `companyWide: boolean`. A company-wide report given a `branch` answers 400
  ("<report> is company-wide; it cannot be cut by branch"), so a branch can never be silently ignored.
- **Export filter summary** (`FilterSummary`): `Branch: Arusha (ARU)` (name from the branch register, code in
  brackets); company-wide reports say `Company-wide`.
- **Dashboards** `GET /dashboards/overview|underwriting|claims` accept `branch`. Absent: exactly today's figures.
  With a branch:
  - **Management:** premiums collected, new policies, policies in force, lapsed, claims paid and open claims from the
    reporting projections cut by the policy's branch (the same reports its cards open, with the branch); "waiting for a
    decision" = underwriting cases of that branch (case's own branch) + open claims of that branch (projections).
  - **Underwriting:** the underwriting API's queue, open/in-review counts, decisions by month and turnaround take an
    optional branch, filtering on `underwriting_case.branch_code`.
  - **Claims:** the branch's policy numbers come from a new reporting API method over `report_policy`; the claims
    API's own queries take them (`searchClaims` already does; the by-month figures and the open-document-request count
    gain an overload), so claim statuses stay the claims module's own rules rather than being re-derived from the
    reporting copy's dates. (Refined while planning, 2026-11-23.)
  - Each view gains `branch` (code and name, or null) and, per figure that still has no branch source, a flag the
    screen shows as "All branches". None is expected; the flag stops a quietly wrong figure.
- **`/me/access`** gains `homeBranch` (the staff record's) and `headOfficeBranch` (organisation settings), and
  `branches` (code and name, register order, head office first) for the select.

## Screens (shaped with impeccable)

Existing patterns only: the labelled select, the period chips, the summary line, the label tier. No new components
or colours.

- **Dashboard:** a labelled **Branch** select on the period row, after the chips and before the comparison note. With
  a branch chosen the note reads "Arusha branch · Compared with the same months a year earlier". It applies to every
  tab; the finance tab, which is company-wide, shows "Company-wide" in place of the select's effect (the note says
  "Company-wide · …" on that tab).
- **Policy-and-claims reports:** **Branch** joins the filter row after *Client type*. The summary line gains
  "· Arusha branch".
- **Company-wide reports:** no select; the line under the summary reads "Company-wide · Read live from the ledger".
- **Options:** "All branches", then the register's branches by name, head office first. Your own branch, when it is
  not head office, is marked "Arusha (your branch)". Head-office staff see no marker.
- **The choice lives in the address:** `branch=ARU` or `branch=all`, beside `from`/`to`. No `branch` in the address
  means the rule: home branch if set and not head office, else all. Dashboard cards pass it into report links; reports
  opened from the menu follow the same rule. Nothing is kept in browser storage.
- **A figure with no branch source** keeps its look and gains a muted "All branches" line under its label: words, not
  colour (DESIGN.md's Six Buckets and Two Meanings rules).
- **Narrow screens:** the period row already wraps; the select wraps with it.

## Testing

- **Backend:** for every "Yes" report, a test with policies in two branches proving `branch` cuts its figures and
  absent `branch` does not; every company-wide report refuses `branch` with 400; dashboards: each figure with and
  without `branch`; `/me/access` returns `homeBranch`, `headOfficeBranch`, `branches`; the export summary names the
  branch.
- **Frontend (vitest):** the starting-branch rule (home not head office / head office / none / `branch=all` / an
  explicit code); the select's options and marker; report links carry the branch; the filter bar shows Branch for
  branch reports and "Company-wide" for the others; the dashboard note's words.
- **e2e:** one spec as a staff login with a non-head-office home branch: the dashboard starts on that branch, a card
  opens its report on the same branch, "All branches" changes the figures, and a company-wide report shows
  "Company-wide". The spec sets and restores that login's home branch through the Users screen's API.
- **Visual:** the dashboard and a report of each kind, 1440 and 390 wide, light and dark, checked with impeccable's
  craft floor before merging.

## Out of scope

- Walls (branch-scoped access) — still deferred.
- Branch figures from the ledger (income statement and the rest).
- Changing a policy's branch after issue, and the 10 dev policies with no branch (they count under "All branches" only).
