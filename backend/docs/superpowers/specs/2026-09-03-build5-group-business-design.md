# Build 5 — Group business, slice 1

**Date:** 2026-09-03
**Requirement:** §5 of the client's underwriting requirements table.
**Status:** slice 1 shipped (schema, domain, service, HTTP). Frontend and later slices open.

---

## 1. The model, as confirmed with the client

> One master/group policy + many insured members (individual lives) attached to that
> policy. So a member is generally **not** a separate policy.
>
> ABC Company / 500 employees / GL-000123 — the company is the policyholder.

That single paragraph decides the whole shape:

```
policy.policy (GROUP_LIFE)          the master policy / scheme — ONE contract
     |
     +-- policy.group_scheme        benefit basis, FCL, currency        (1:1)
     +-- group_scheme_grade         grade -> benefit                    (GRADED only)
     +-- policy_member              who is covered
              +-- policy_member_benefit   effective-dated salary + benefit
```

**The employer is the policyholder and is not a life assured.** Migration V8 corrects V7,
which had backfilled `life_assured_party_id = policyholder_party_id` across every policy —
true for individual business, and on a group policy an assertion that the *company itself*
is insured. 28 rows. A GROUP_LIFE policy now answers "who is insured" with NULL, meaning
"not a single person", rather than with a confident wrong answer.

---

## 2. Decisions

### 2.1 Benefit basis: all three, one per scheme

`FLAT | SALARY_MULTIPLE | GRADED`, configured on the scheme rather than hard-coded,
because real schemes differ: flat is typical for SACCO, funeral and credit-linked cover;
salary multiple is the standard for formal employer schemes; graded splits by staff
category. `group_scheme_basis_parameter_present` makes the basis and its parameter agree —
a SALARY_MULTIPLE scheme with no multiple cannot value anybody, and a FLAT scheme carrying
a multiple is telling two stories.

Inputs that do not belong to the basis are **rejected, not ignored**. A grade code on a
flat scheme, or a salary on a graded one, is a caller who believes something about this
contract that is not true; accepting it would let an administrator upload a salaried
schedule to a flat scheme and see plausible, wrong numbers.

### 2.2 Free cover limit: scheme-level, and null ≠ zero

The FCL lives on the scheme, and each member is *evaluated against* it. It is deliberately
not copied onto each member row, which would be 500 copies of one number that can disagree
with it.

`fcl_amount IS NULL` means the scheme has **no limit** — every member covered in full with
no evidence, which is a real design for small flat schemes. That is not the same as a limit
of zero, which would send all 500 employees to underwriting. Zero is therefore rejected at
the boundary rather than accepted and reinterpreted.

### 2.3 A member over the limit is covered *up to* it

Market standard, taken as the client's rule (flagged below): a member above the FCL is
covered up to the limit **immediately**, and the excess is granted only on acceptance. A
150m benefit against a 100m limit is 100m of real cover from day one — not zero, and not
150m.

So `benefit_amount` and `covered_amount` are both stored and both facts, and neither is
derivable from the other once a decision has been made: ACCEPTED and DECLINED produce
different covered amounts from identical inputs.

`underwriting_status` is **four states, not a boolean**:

| state | meaning | covered |
|---|---|---|
| `WITHIN_FCL` | at or under the limit | full benefit |
| `EVIDENCE_REQUIRED` | over the limit, underwriting outstanding | the limit |
| `ACCEPTED` | evidence provided, excess granted | full benefit |
| `DECLINED` | evidence provided, excess refused | the limit |

`EVIDENCE_REQUIRED` and `DECLINED` share a covered amount and mean different things. A
claim assessor has to be able to tell them apart, and a boolean cannot.

### 2.4 Benefits are effective-dated — the load-bearing decision

A claim must pay the benefit in force **on the date of event**. A salary-multiple benefit
recomputed at claim time would value a two-year-old death at a salary the member did not
have when they died. So a salary change writes a **new row** rather than editing the old
one.

This is also what makes the client's own rule enforceable rather than merely conventional:

> what happens when a member's salary changes — whether their benefit automatically
> changes, changes at the next scheme renewal, or requires an endorsement.

**Confirmed: cover does not follow payroll silently.** Benefit is recalculated at renewal
or by explicit endorsement. Premium was rated on the declared schedule and the FCL was
tested at the old benefit; cover that rises with payroll is unpriced risk, and it can carry
a member across the FCL with nobody underwriting them.

### 2.5 The scheme total is derived, never stored twice

A scheme's sum assured **is** the total of what its members are covered for. Two
consequences:

- **A scheme cannot be issued empty.** Its sum assured would be nil, which
  `policy_sum_assured_positive` rejects, and rightly — a contract insuring nobody for
  nothing is not a policy. So issuance takes the opening schedule in the same call. That
  also means a failure part-way through a 500-row schedule leaves no half-populated scheme
  for somebody to find and mistake for a complete one, and it sidesteps `MoneyDto`'s
  `@DecimalMin("0.01")` floor cleanly rather than working around it.
- **There is no `sumAssured` input.** It is derived in one place. A caller able to supply
  it is a caller able to supply one that disagrees with the schedule underneath it — the
  same reasoning that keeps `maturityDate` off `IssueRequest`.

`policy.sum_assured_amount` is still *stored*, because the policy list and a reinsurance
return read it. It is restated from the schedule **inside the same transaction** as any
membership change, together with the DEATH coverage row (which is what
`getCoverageStatus` answers from — restating one and not the other would give the platform
two different answers to "how much is this scheme insured for" depending on which screen
you were standing in front of). `GroupSchemeIntegrationTest` asserts the stored and derived
totals agree on both sides of a joiner.

### 2.6 `policy.PolicyIssued` is emitted for a scheme too

Not obvious, and it matters: billing raises the employer's invoice schedule from that
event, distribution accrues the broker's commission from it, and regreporting writes the
`policy_dimension` row the regulator's return reads. A group policy that skipped it would
be invisible to all three.

---

## 3. What was built

**Migration** `policy/V9__group_scheme_and_members.sql` — four tables, RLS on each,
`ux_policy_member_active` (partial, so somebody who leaves and rejoins keeps both rows),
and `idx_policy_member_party` answering "every scheme this person is covered on" — the
question a claims assessor asks when a death is reported and nobody knows which employer's
scheme it falls under.

**Domain** `GroupBenefitCalculator` — a pure function over plain values, the only place on
the platform that decides what a group member is insured for. 12 tests, no database, no
Spring, 0.19s.

**Service** `PolicyApi.issueGroupScheme / getGroupScheme / listMembers / addMember`.

**HTTP** `/group-schemes`, `/group-schemes/{n}`, `/group-schemes/{n}/members` (GET, POST).
Its own resource rather than a branch inside `/policies`: a scheme read as a policy answers
"one contract, 500 lives, sum assured X" — true, and useless to somebody administering the
schedule.

**Ordering.** Every "which row applies" query has a **total** order, and the member list's
sort is fixed at the controller rather than accepted from the caller. A bulk schedule gives
every row the same `joined_on`, and paging a query whose order has ties can show one member
twice while never showing another — on a member roll that is a person who believes they are
insured and is missing from the page nobody scrolled twice. Same defect shape PLAN.md §10
records finding four times, once deciding an agent's commission rate.

**Tests.** 16 integration (Testcontainers) + 12 unit + 4 contract, all green.

---

## 4. Limits of this slice, stated plainly

1. **Commencement may not be in the future.** Backdating is fine and common — a schedule
   reaches the insurer weeks after cover started — but a scheme commencing next month would
   carry a sum assured its members do not yet contribute to, and the stored and derived
   totals would disagree for a month. Making the total date-aware end to end is the first
   thing the next slice should do; refusing the case is the honest version of not having
   done it yet. Same reason a joiner cannot be forward-dated.
2. **No underwriting case is opened for a member over the FCL.** They are marked
   `EVIDENCE_REQUIRED` and counted on the scheme summary; wiring that to a real
   `underwriting` case is slice 2.
3. **`policy.GroupMemberAdded` carries the new scheme total, and nothing consumes it yet.**
   So `regreporting`'s `policy_dimension` sum assured goes stale after a joiner. Known gap,
   not a silent one.
4. **No member exit, no salary restatement, no bulk CSV intake** — the storage contract for
   all three is in place and tested (`anOlderBenefitStillAnswersForItsOwnDateAfterARestatement`
   proves the effective-dating works before anything is built on it), but no API yet.
5. **Staff only.** A scheme is set up from a submitted employee schedule at a desk. No
   agent- or customer-facing flow has been designed, and opening one because the endpoint
   exists would be a guess at a screen nobody has drawn.

## 5. Open with the client

- **§2.3 above is a market-standard assumption, not a quoted answer.** It is easy to
  overturn: the alternative rule is that a member over the limit has *no* cover on the
  excess and full cover below it, which is what the code already does — or that they have
  no cover at all until accepted, which would be a one-line change to
  `GroupBenefitCalculator.evaluate` and a different `Valuation`. Worth confirming before
  the first real scheme goes on.
- **§6 of the requirements table has still not been supplied** (flagged four times now).
- **§4 credit life** remains blocked on the amortisation source.
