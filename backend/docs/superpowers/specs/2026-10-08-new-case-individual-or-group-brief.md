# Brief: Underwriting "New case" — choose Individual or Group first

Date: 2026-10-08. Status: findings verified against the code on branch `audit-fixes`; not started.
Audience: whoever builds it (the console only; the server's checks are already right).

## 1. The problem the user hit

On Underwriting → **New case** (`/staff/underwriting/new`, `frontend/src/features/underwriting/OpenUnderwritingCasePage.tsx`)
a user picked the company **nlolo-group2** (party type CORPORATE) as the life assured. The screen accepted it; the
server refused on submit:

> The life assured must be a person; nlolo-group2 is CORPORATE

(trace 50a9f8b7-03c4-4ce2-bb2c-6b6377310ab2; the check is `UnderwritingApiImpl` ~line 216.)

The form offers choices the server will always refuse, and nothing on it says that group business is opened elsewhere.

## 2. Findings

| # | Where | Problem | Server today |
|---|---|---|---|
| 1 | `OpenUnderwritingCasePage` Life assured picker (~line 233) | Searches every party, companies and groups included | Refuses on submit (person only) |
| 2 | `OpenUnderwritingCasePage` Applicant picker (~line 216) | Any party. A company **is** a valid applicant (key-person cover), but the screen does not say the life assured must then be a person | Accepts — correct |
| 3 | `OpenUnderwritingCasePage` product list (~line 255) uses `isSingleLifeProduct` (`src/api/types.ts:442`) | Only hides GROUP_LIFE and CREDIT_LIFE. A FUNERAL product whose terms are sold **to groups only** (`FuneralSoldAs.GROUP`, e.g. FUN-GRP-01) still appears | Fails later, at pricing (`FuneralQuoter` refuses a group-only plan) — late and confusing |
| 4 | Navigation | No Individual/Group choice. Group business starts from three separate New-business nav items: Group scheme (`group-schemes/new`), Group funeral scheme (`group-funeral-schemes/new`), Credit-life scheme (`credit-life-schemes/new`). New case looks like the place for everything | — |
| 5 | Scheme set-up pages: `IssueGroupSchemePage` (~177), `ProposeGroupFuneralPage` (~140), `IssueCreditLifeSchemePage` (~163) | The policyholder / association picker lists people too; a scheme's holder should be a company or group | Not checked here; confirm before relying on it |
| 6 | `IssuePolicyPage` (`policies/new`, manual issue) Life assured picker (~354) and product list (~373) | Same as 1 and 3 | **Probably unguarded.** Manual issue may run without an underwriting case (`underwritingCaseId == null` is the "exception route", `PolicyApiImpl` ~2595) and `PolicyApiImpl` has no person check of its own. Verify with a test; if confirmed, add the server check too |
| 7 | Agent console | Has no case screen at all, so agents are not affected | — |

Every individual product (endowment, savings, fixed-term deposit, annuity, deferred pension, unit-linked, individual
funeral) shares finding 1. On the case path the server catches all of them.

## 3. What to build (the user's direction)

**New case opens with a choice: Individual or Group.**

- **Individual** → the current form, with:
  - Life assured: people only. `PartyPicker` takes only `kycStatus` today; add a `partyTypes` prop and pass it to
    `searchParties` (which already supports `partyTypes`; use `PARTY_AREAS.individuals`). The UUID lookup branch
    (`getParty`) must apply the same filter.
  - Applicant: people or organisations, with a one-line note: "A company may apply; the life assured must be a person."
  - Products: also hide FUNERAL products whose current terms are sold to groups only. This needs the funeral terms
    (`getFuneralTerms` → `soldAs`), or a `soldAs` field on the product list. Prefer the cheapest correct option;
    showing such a product disabled with "sold to groups — use Group" is acceptable.
- **Group** → three choices that link to the existing pages (do not rebuild them):
  Group life scheme → `group-schemes/new`, Group funeral scheme → `group-funeral-schemes/new`,
  Credit-life scheme → `credit-life-schemes/new`. Each with a one-line description of who it is for.
- On those three set-up pages, the policyholder/association picker lists organisations only
  (`PARTY_AREAS.organisations`).
- Apply findings 1 and 3 to `IssuePolicyPage` as well, and settle finding 6 on the server.

How the choice looks (a first step on the same page, or a small chooser screen before the form) is the designer's call.
A link straight to the form (for example `?type=individual`) should skip the chooser, so existing links and e2e flows
can go straight in.

## 4. Constraints in this repo

- Never run Prettier. Lint forbids `setState` in `useEffect` bodies (`.then` callbacks are fine). The design guard
  requires the console primitives (`Checkbox`, `Select`, `Button`...). `exactOptionalPropertyTypes` is on: optional
  props need `| undefined`. Some files are CRLF; edit with the editor tools, not shell string replacement.
- **E2E is tied to accessible names.** These specs open New case and will break if the page's first screen or
  labels change: `e2e/underwriting.ts` (shared helper), `staff-underwriting`, `staff-underwriting-queue`,
  `staff-family-funeral`, `staff-annuity`, `staff-deferred-annuity`, `staff-unit-linked`, `staff-unit-linked-u2`.
  Update the helper once, then run those specs against the real stack.
- Tests: vitest for `PartyPicker` (the `partyTypes` filter, both search and UUID paths), the chooser, and the product
  filter; then the e2e specs above.

## 5. Other open items (not part of this build)

- Data on the dev database: GRP-9E1BFD2A has parent rows that duplicate the spouse; POL-67B3A404 has the child
  "imani" twice. To fix by hand or through the families screen.
- Customer portal: the customer can download the payment schedule and savings statement through the API, but the
  customers realm has no screens yet.
- Yearly group funeral schemes: renewal is undecided (can the association hand in a new member list at the
  anniversary?). Waiting on the user.
- The `audit-fixes` branch is not merged yet: full backend suite running, then vitest, the dev stack restarted from
  this worktree, full e2e, merge.
