# Build 2 — the policy term

Status: **built 2026-09-02.** Second of six builds scoped from an underwriting
requirements review. Follows
`2026-09-02-build1-individual-person-record-design.md`. §7 records what came out
of building it.

---

## 1. What is missing

`policy.policy` records `issue_date` and nothing else about time. Against §3
(Cover Details) that leaves out **policy term, commencement date, maturity /
expiry date and premium-paying term**.

The sharpest consequence is not the requirement, it is an inconsistency the
platform already carries:

- `MATURITY` is a `benefit_type` on `product.benefit_schedule` and on
  `policy.coverage`.
- `MATURITY` is a `claimType`, and `Claim.approve()` documents that a maturity
  claim auto-approves straight from `REGISTERED` with no assessment.
- **No policy anywhere carries a maturity date.**

So the platform can pay a maturity claim but cannot say when a policy matures.
Nothing can sweep for policies maturing this month, and "is this maturity claim
even due?" has no answer on the record. That is the gap this closes.

`issue_date` is also doing two jobs. A policy issued today may carry risk from
next month; §1 asks for a *proposed commencement date* precisely because the two
differ. `claimGates` currently judges "had risk commenced?" against `issueDate`
because it is the only date available — and records in its own comments that this
is an approximation.

## 2. Scope

**In:** four columns, the invariant that ties them together, the API and spec
fields, and rendering on the issue form, the policy page and the drawer.

**Out:** anything that *acts* on the dates. No maturity sweep, no scheduled job,
no change to `claimGates` (it should move to `commencementDate`, but that is a
gate change and belongs with Build 4). No change to billing schedules.

## 3. Schema — `policy/V6__policy_term.sql`

| Column | Type | Notes |
| --- | --- | --- |
| `commencement_date` | `DATE` | when risk starts; may differ from `issue_date` |
| `policy_term_months` | `INTEGER` | cover duration |
| `premium_paying_term_months` | `INTEGER` | may be shorter than the cover term |
| `maturity_date` | `DATE` | derived, and persisted so it can be queried |

All nullable. Every policy issued before this migration has none of them, and a
backfilled default would invent contract terms — which on an insurance policy is
considerably worse than inventing a person's occupation.

Constraints:

- `policy_term_months > 0` and `premium_paying_term_months > 0` when present.
- `premium_paying_term_months <= policy_term_months` — a limited-payment policy
  pays for less time than it covers; paying for longer than the cover runs is
  not a product, it is a data error.
- **`maturity_date` must equal `commencement_date` plus `policy_term_months`.**
  Enforced in the database, not only in Java:
  `CHECK (maturity_date IS NULL OR maturity_date = (commencement_date + make_interval(months => policy_term_months))::date)`.
  `make_interval` is immutable so this is a legal CHECK.

### Why store a derived date at all

Because the alternative is worse in the specific way this platform keeps getting
caught. A maturity sweep needs `WHERE maturity_date BETWEEN ...` on an indexed
column; computing it per row makes the query unindexable, and computing it in
Java means every caller re-derives it and one of them eventually gets it wrong.
Storing it with a CHECK that pins it to its inputs gives the queryable column
without letting it drift — the same instinct as the `ORDER BY` sweep recorded in
`frontend/PLAN.md` §10, where "whichever row the scan returned" decided money.

Partial index on `(tenant_id, maturity_date) WHERE maturity_date IS NOT NULL` so
the sweep this enables is cheap when it is written.

### Products with no maturity

`WHOLE_LIFE` and `ANNUITY` have no maturity, and a group scheme is annually
renewable rather than termed. All four columns stay null for those, which is why
the invariant is written as "if `maturity_date` is present it must agree", not
"every policy has a maturity".

## 4. API shape

`PolicyApi.IssueRequest` is already a record, and it has **31 construction
sites**. Rather than editing all of them, the four fields are appended to the
canonical constructor and a second constructor carrying the pre-Build-2
parameter list delegates with nulls:

```java
record IssueRequest(..., LocalDate commencementDate, Integer policyTermMonths,
                    Integer premiumPayingTermMonths) {
    /** Pre-Build-2 issuance: no term information. */
    IssueRequest(UUID policyholderPartyId, ... , String reasonForManualIssue) {
        this(..., null, null, null);
    }
}
```

Note what is **not** in the request: `maturityDate`. It is derived at issue from
commencement plus term. A caller that could supply it could supply a wrong one,
and the CHECK would then reject a request that looked reasonable. Deriving it
server-side means there is exactly one place it can be computed.

`PolicyView` grows all four, including the derived `maturityDate`, because a
reader should not have to do date arithmetic to answer "when does this mature".

**Not a `default` interface method anywhere.** See Build 1 §9.1 — an extra
record constructor is plain Java with no proxy involved, which is why it is safe
here and was not there.

## 5. Screens

- **`IssuePolicyPage`** grows commencement date (defaulting visibly to today),
  policy term and premium-paying term. The derived maturity is shown inline as
  the user types, so the consequence of "240 months" is legible before submit.
- **`PolicyDetailPage`** and **`PolicyDrawer`** render commencement, term and
  maturity. A policy with no term recorded shows the em dash, and the panel
  notes that pre-Build-2 policies carry no term rather than implying whole-life.

## 6. Done gate

Backend unit/integration plus the policy contract test; `tsc`, `eslint`, Vitest;
and the full Playwright suite — this changes `PolicyView`, which the policy,
claims, billing, reinsurance and agents specs all read. `V6` needs a manual
`psql` apply and a backend restart before real-stack e2e reflects it.

---

## 7. Notes from building it

### 7.1 Where the end-of-month bug would have been

`maturityPreview` on the issue form and `Policy.applyTerm` on the aggregate both
add months, and they must agree or the form shows a date the record will not
hold. The trap is that the obvious JavaScript — `new Date(y, m + n, d)` — rolls
**31 January + 1 month into 3 March**, while `java.time.LocalDate.plusMonths`
clamps to **28 February**. Both sides now carry a test pinning the clamping
behaviour on 31 Jan (and a leap year, and 31 Aug), so the two cannot drift
without something going red.

This is why the preview is labelled display-only in three places. It exists so
"240 months" is legible as a date before someone commits to it; it is never sent,
and `policy_maturity_matches_term` would reject it if it were.

### 7.2 An extra record constructor is safe where a `default` method was not

Build 1 §9.1 records a `default` interface method silently losing
`@Transactional` because Spring's proxy never re-entered. The same
avoid-touching-31-call-sites instinct applies here, and the same trick would be
wrong for the same reason — but a second **record constructor** is plain Java
resolved at compile time with no proxy anywhere near it. Worth stating explicitly
because the two look like the same manoeuvre and only one of them is safe.

### 7.3 Two "empty" states that are not the same

- **A product that does not term** — whole life, an annuity, an annually
  renewable group scheme — genuinely has no commencement, term or maturity.
- **A policy issued before V6** has none recorded.

Both render as an em dash, which is right, but the note beneath differs
("This product does not mature" versus "issued before the term was captured")
because a finance officer asking why a policy has no maturity needs to know which
of the two they are looking at. The form treats a blank term as a real answer
rather than a validation error for the same reason.

### 7.4 One asymmetric validation

A **term with no commencement date** is rejected: a duration with nothing to run
from cannot produce a maturity, so the record would carry a number that anchors
to nothing. A **commencement date with no term** is accepted, because that is
exactly how whole life is recorded. Easy to get backwards, so both directions
have a test.

### 7.5 Deliberately not done

`claimGates` still judges "had risk commenced?" against `issueDate` and should
move to `commencementDate` now that one exists. That is a gate change, it belongs
with the `issueGates` work in Build 4, and doing it here would have meant
touching the gate set without the product bounds that make the rest of it
answerable.

Nothing sweeps for maturing policies yet. The index exists; the job does not.
