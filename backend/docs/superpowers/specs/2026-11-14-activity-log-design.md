# Activity log -- design

Date: 2026-11-14. Status: agreed with the user in conversation, awaiting their review of this write-up.

## Why

Admins need to answer "who did what, when, and to what" in plain words. The existing Event journal
(`/staff/audit-log`, `audit.audit_log`) cannot:

- it records business events, not people: only 9 of the 91 event kinds in the dev data name who acted, each under a
  different field;
- it never sees a change that raises no event -- permission grants (iam), product and price edits, most finance
  settings;
- it never sees a refused or failed attempt;
- it reads as technical names with raw JSON (`finaccounting.GlPostingRecorded {...}`).

## Decisions (user, 2026-11-14)

| # | Question | Decision |
|---|---|---|
| 1 | Audience | An admin investigating: who did what, when; searchable by person, record and date; History on records. No insights, no sign-in tracking (later, on the same records). |
| 2 | What is recorded | Every change anyone makes -- all write actions -- with its outcome: done, refused (with the reason shown), failed. |
| 3 | Words | A short past-tense label per action ("Approved a withdrawal"), drafted from the API summaries and reviewed by the user before going live. |
| 4 | Edits | Show what changed. Secrets never kept; ID and phone numbers masked in the line. |
| 5 | Viewing | Not recorded. Changes only. |
| 6 | Event journal | One page. The activity log replaces it; events appear nested under the action that caused them. |

## Part 1 -- what is recorded

### One recorder in front of every write

A servlet filter (audit module) records every `POST`, `PUT`, `PATCH` and `DELETE` that reaches a controller -- 274
operations today, all described in `backend/api/openapi/*.yaml`. For each:

| Field | Source |
|---|---|
| when | the request's start |
| who | the signed-in identity: staff user, agent or customer (workspace), their display name; `System` for scheduled jobs; `Not signed in` for public writes (public quote link, password reset) |
| action | the matched route (`METHOD` + path pattern), turned into its label |
| record | the route's path variables, mapped to a record type (policy, client, claim, agent, scheme, staff user, product, ...) and its reference |
| what was sent | the request body, secrets removed, personal numbers masked (below); a file upload records file name and size, never content |
| outcome | `DONE` (2xx), `REFUSED` (4xx, with the plain words the person saw), `FAILED` (5xx) |
| action id | a new id, carried on the request thread so events raised by the action can be joined to it |

### Labels

A file of labels keyed by `METHOD path-pattern` (e.g. `POST /claims/{claimId}/approve` -> "Approved claim"),
drafted from each operation's OpenAPI summary and given to the user to skim in one list. A test enumerates every
write route the application serves and fails when one has no label, so a new action cannot reach production
unlabelled.

### What changed

- The body is kept as sent. The line shows the changed fields in words: `field: before -> after` where the screen
  sends the whole record and an earlier entry for the same record holds the old value; otherwise `set to <value>`.
- **Never kept:** any field named like a password, code, OTP, PIN, token, secret or authorization.
- **Masked in the line, full when the line is opened:** national ID / ID numbers, phone numbers, bank and mobile
  money account numbers.

### Events joined to the action that caused them

`DomainEventAuditListener` already writes every business event to `audit.audit_log` on the request thread, after the
change commits. It reads the current action id from the thread and stores it on the event row. Opening an action shows
its follow-on events in words ("Payout requested", "Posted to ledger", "SMS sent to client"), using the event words
already kept for the dashboards (`frontend/src/features/dashboard/activity.ts`), extended to every event kind; an
event kind without words is humanised from its name, never dropped.

Events with no action (scheduled runs, redelivered events) appear as their own `System` lines.

The 6,000+ events recorded before this change appear as `System` lines with an unknown person.

### Keeping it

- Kept, never deleted. Stored by month, like `audit.audit_log` (partitions made ahead by pg_partman in dev and
  production; a guarded migration creates test months, per `audit/V3`).
- Append-only: the application's database role may insert and read, never update or delete. Row-level security by
  tenant, as for the rest of `audit`.

### Who sees it

Holders of the existing `audit.view` permission. No new role.

### Changes made while building (2026-11-14/15)

- **Writes with no one signed in get no line of their own**: the public quote link (`/public/**`), Keycloak's internal
  calls (`/internal/**`) and the mobile-money callback (`/webhooks/**`). They carry no session, so there is no person
  to name and no tenant to file the line under. What they cause still appears, as System lines ("Application
  received", "Payment confirmed").
- **The record tab is called "Activity"**, not "History": a claim already has a History tab (its assessments). Agents
  get an Activity panel, since their page has no tabs; staff users get "What they did", which opens the log filtered
  to them (their access changes are already listed on their page).
- Group and credit-life schemes are addressed by policy number, so their activity is the policy's.

## Part 2 -- screens

### Activity log page (replaces Event journal, same menu place)

- Filters: person; record (policy number, claim number or client name); kind (claims, money, policies, people &
  access, products & prices, settings); outcome (done / refused / failed); dates, defaulting to the last 7 days.
- One line per action, newest first:
  `14 Nov 10:32 · Asha M. (staff) · Approved claim CLM-0042 · TZS 4,500,000 · Done`.
  A refused line carries its reason: `Refused: you cannot approve your own payout`.
- A person filters to that person; a record opens that record.
- Opening a line: what changed; what followed (events, in words); "Technical detail" folded away (route, raw body,
  raw events) for support.
- Download the filtered list as CSV.

### History tab on records

Policy, client, claim, agent, scheme and staff user pages get a History tab: the same lines filtered to that record,
newest first, including its `System` follow-ons. Shown only to holders of `audit.view`.

### Staff user page

Admin > Users > a user: "What they did" opens the log filtered to that person.

## Out of scope

Insights (counts per person, own-work approvals, after-hours), sign-in and lockout tracking from Keycloak, and
recording who viewed what. Each can be added later on the same records.

## Open points for the plan

- Where the display name for a record id comes from (claim number, client name) without the audit module depending
  on every module: a small resolver port per record type, or the reference as captured from the path and response.
- Body capture size cap, and the CSV row cap.
- Which existing e2e specs name "Event journal" (rename breaks them; they must be updated in the same change).
