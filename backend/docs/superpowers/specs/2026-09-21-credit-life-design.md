# Build 6 — Credit life

**Date:** 2026-09-21
**Requirement:** §4 of the client's underwriting requirements table.
**Status:** plans 1 and 2 built and merged. Eight of the nine client questions were
answered on 2026-09-22 — see §0. §6 of the requirements table is still outstanding.

---

## 0. Client answers, 2026-09-22

Recorded verbatim in effect, with what each one changed. These supersede the assumptions
they replace wherever the two disagree.

| # | Answer | Effect |
|---|---|---|
| 1 | **Cover declines STRAIGHT-LINE.** Their example: 1,000,000 over 12 months reduces by 1,000,000 ÷ 12 = 83,333.33 each month | Unblocks plan 1 task 7. `AmortisationCalculator`'s `FLAT_RATE` branch already computes exactly this — `principal × (n−k)/n` — and reads no interest rate |
| 2 | **The lender has NO per-loan identifier.** One policy number is issued to the bank and it returns sheets; nothing distinguishes one borrower from another. **The insurer must issue the id** | Reverses §2.1. See §2.1a |
| 3.1 | **Rate is per annum and ADJUSTABLE per lender** — 0.4% for some, 0.5% for others | Confirms per annum. Makes the rate a scheme-level field rather than a constant |
| 3.2 | **The lender accepts pro-rata clawback** | Unblocks plan 3's last task |
| 3.3 | **Free cover limit is 600,000,000 TZS** | Config. Note LOLC's loans are ~10M and BUMACO's up to 20M, so in practice **no borrower will reach it** — the capped-cover and referral path is correct and will essentially never fire |
| 3.4 | **Exclusions confirmed**: 12-month suicide, 12-month pre-existing, no general waiting period | Unblocks plan 4 |
| 3.5 | **Exits are reported MONTHLY** | Unblocks plan 3's exits file |
| 3.6 | **The borrower is never told.** The insurer deals only with the lender | Removes the borrower-notification path from plan 5 entirely |
| 3.7 | **TIRA class is "Credit Life"**, and a valid filing reference is required before publish | As the platform already enforces at `publishVersion` |

**Still outstanding: §6 of the requirements table**, first requested 2026-09-03 and asked
for six times.

---

## 1. The model

A bank lends money. The insurer covers the borrower's **outstanding** loan balance if the
borrower dies. The bank pays the insurer an agreed percent per annum of the original
principal. Borrowers arrive several hundred at a time on a CSV the bank already maintains.

```
policy.policy (CREDIT_LIFE)         the master policy / scheme — ONE contract per lender×product
     |
     +-- policy.group_scheme        interest method, FCL, currency          (1:1)
     +-- policy_member              one per LOAN, not one per person
              +-- loan parameters   principal, rate, term, frequency, instalment, dates
```

**The bank is the policyholder and is not a life assured.** Same shape as Build 5, and
`life_assured_party_id` — added by `underwriting/V4` explicitly *for this product* — carries
the borrower.

The product is **not a new contract shape**; it is the first one whose sum assured decreases
over the term. That single property is what makes the group path mandatory rather than
merely convenient: `policy.policy_member_benefit` is the only effective-dated cover amount
on the platform, and `PolicyApi.claimableCover(policyNumber, policyMemberId, asOf, benefitType)`
is the only query that answers "what was this life covered for on the day it happened".
On the individual path both would have to be invented.

---

## 2. Decisions

### 2.1 A member is a LOAN, not a person

**Superseded in part by client answer 2 — read §2.1a with this.** A member is still a
loan; what changed is who names it.

The member key was to be `loan_account_number` + lender. Two loans to the same borrower are
two members, which is correct — each covers its own debt.

This deliberately sidesteps person-matching, which does not work here. Party de-duplication
fires only when an identity document is present (`PartyApiImpl:89`, backed by a partial
unique index `WHERE id_number IS NOT NULL`), so a spreadsheet row carrying a name and a date
of birth but no national ID would create a brand-new person, silently, on every submission.
The loan account number is the only identifier the bank is guaranteed to hold and keep
stable.

### 2.1a The INSURER issues the member reference

Client answer 2: the lender holds no per-loan identifier to give us. Today one policy
number goes to the bank and sheets come back, with nothing distinguishing one borrower
from another. Automating that means we must issue the id.

**A member reference is minted at enrolment** — `CL-<scheme>-<sequence>` — and printed on
the report that goes back with the file. From then on the lender can quote it for an exit
or a correction.

That loses what the lender-supplied key gave for free: protection against a resubmitted
file enrolling everybody twice. On the first file the lender has nothing to quote back.
So:

- **A row with no reference is a NEW borrower**, and we mint one.
- **A row carrying a reference names an existing member.**
- **New rows are de-duplicated on a composite** of borrower name, date of birth,
  disbursement date and principal. Two loans to one person on the same day for the same
  amount are indistinguishable under it — possible but rare, and it fails in the safe
  direction: a rejection the lender can overturn by confirming, rather than silent double
  cover.

The composite is a *rejection rule*, not a database key. `ux_policy_member_active_loan`
becomes an index on the issued reference, which is unique because we generate it.

`policy_member.member_party_id` is `NOT NULL` today. The freeform-member design specified in
`plans/2026-09-10-group-scheme-substitution-and-notices.md:256` — `member_type`,
`member_name`, `member_date_of_birth`, nullable `member_party_id` — is a prerequisite for
this build.

Four hundred registered parties per file means four hundred `kyc_status='PENDING'` rows in
the staff Clients queue, which is exactly the pain that plan diagnosed for employer schemes.
A borrower is **promoted to a real party at claim**, when there is a death certificate and an
identity to verify anyway. KYC is deferred to the moment it actually matters.

### 2.3 Cover is the lesser of the schedule we compute and the balance the bank declares

The schedule is the priced benefit and is therefore the contractual maximum. A lower figure
declared by the bank at claim caps it.

This is deliberately asymmetric. The insurer never repays arrears, penalty interest or
default charges it did not rate for, and because the bank's figure can only *reduce* the
payout, the bank has no incentive to inflate it.

If the bank produces no figure, the schedule stands (§2.9). A claim must not deadlock on a
document a counterparty may never send.

### 2.4 The schedule is computed on demand, and snapshotted onto the claim

Loan parameters are stored on the member; the balance at any date is computed, not
materialised. Materialising one `policy_member_benefit` row per repayment date would be
24,000 rows for a 400-borrower file of 60-month loans, and would abuse a table built for
occasional restatement.

The computed figure **is** snapshotted onto the claim at registration, because the approval
ceiling reads the claim's own stored facts and the amount must not drift after the claim is
filed.

### 2.5 The interest method is inferred from the file, never asked

A book is all reducing-balance or all flat-rate, never mixed — but which one is a fact about
the lender that nobody has supplied, and it is the fact that has blocked this product since
2026-09-03.

**`instalment_amount` is a required column.** The system computes both candidate instalments
from principal, rate, term and frequency, and the one the bank's own instalment matches
within 1% is the method. Across a representative sample the two differ by 16%–30%
(e.g. 8,500,000 TZS at 18.5% over 48 months: 251,914 reducing against 308,125 flat), so the
window discriminates cleanly with room to spare.

Three consequences, all of them good:

- It is a free data-quality check. An instalment matching neither method means the principal,
  rate, term or instalment is wrong, and the row is rejected with a reason instead of a
  schedule being quietly computed from bad inputs.
- It self-diagnoses moratoria, interest-only periods and balloon payments. Such a loan fits
  neither plain formula, so it rejects **visibly** rather than being silently miscomputed.
  The client question about whether their book contains them stops being a landmine.
- If the "all one method" assumption turns out to be wrong, the same mechanism works per row
  with no redesign. Scheme-level inference asserts every row agrees and rejects the file if
  they do not.

### 2.6 Cover starts at disbursement and follows the schedule through arrears

Inception is the loan's **disbursement date**, backdated. It is the only choice with no
uninsured gap between the loan and the cover, which is the window a bank will argue about
after a death. Group scheme commencement already permits backdating; it forbids future
dating, which is why a future disbursement date is a rejection (§3).

When a borrower falls behind, the **scheduled** balance keeps declining. That is what was
priced, it is simple to explain, and under §2.3 the insurer never pays the arrears anyway.
Lapsing cover for missed loan repayments would produce an uninsured death and an angry
lender.

### 2.7 Above the free cover limit, cover is capped — and the referral finally opens

A borrower above the FCL is covered **up to** it, with the excess uncovered. This is already
what `GroupBenefitCalculator` does. Rejecting the row instead would make the largest
exposures systematically the uninsured ones, which is the worst possible selection.

`PolicyMember.referForEvidence(UUID)` has existed with **no caller** since Build 5 — no
underwriting case has ever been opened for an above-FCL member. This build gives it one.

A row breaking the product's **hard** entry-age or term gates is rejected outright, not
truncated. Truncating means a 25-year loan insured for its first 10 years with nobody told,
discovered in year 11.

### 2.8 Premium is a single premium; the bank earns commission on it

Premium is an agreed percent **per annum** of the original principal, charged once at
enrolment, invoiced as **one invoice per accepted file** carrying the total across its
members. One charge, one collection, no arrears, no lapse — and one file-to-invoice
correspondence, which is what reconciliation arguments are actually about.

Early settlement refunds **pro rata**, and commission claws back pro rata with it. Without
the matching clawback the insurer returns the borrower's premium while the bank keeps
commission on money that was given back — a loss on every early settlement, on a product
whose volume the bank controls.

The bank is registered as an ordinary `agent_profile` behind a corporate party. The
broker/bancassurance type discriminator that M7 deferred
(`plans/2026-08-14-m7-distribution.md:74`) waits for a second partner; one corporate agent
does not justify it. Single premium means `FIRST_YEAR` fires once and `RENEWAL` never does.

### 2.9 The claimant is the bank, so there is no payee redirection

`plans/2026-09-10-group-claims.md:1047` records the defect this closes: a group death claim
pays the claimant, which is right for an employer scheme and wrong for credit life, where the
payout extinguishes a debt.

The resolution needs no new concept. **The claimant of record is the bank** — which is also
the policyholder — and the life assured is the borrower, promoted from freeform at that
moment. Claimant and payee are the same entity, so the money reaches the lender through the
ordinary path.

Staff register the claim, notified by either the bank or the family. The portal deliberately
carries no claim reporting (§2.11).

Payout uses a new **EFT disbursement type**: the instruction is recorded and posted to the GL,
and finance executes the transfer out-of-band. The only existing rail is a mobile-money
gateway against a mock with no authentication, which is not where a multi-million-shilling
lender payout belongs.

If cover was capped below the debt, the capped amount is paid, the claim closes and the
member exits. The residual is the bank's credit risk, which is the entire purpose of a free
cover limit.

### 2.10 Every submission is proposed and accepted by a second person

Uploads come from two places — staff on the bank's behalf, and the bank itself. **Both
propose**; a **different** staff user accepts. Cover incepts on acceptance but backdates to
disbursement, so the control costs throughput and not risk.

Same-person acceptance would buy an audit trail and no control, and the platform already
carries one open finding of that shape (a single ADMIN can change a live price in one call).

**One submission in flight per scheme.** Two files in flight can enrol the same loan account
twice, and propose-then-accept widens that window.

Note the operational constraint this creates: a one-person office cannot process a file.

### 2.11 The scheme is open-ended, and the substitution plan must change to allow it

A credit-life scheme has **no headcount cap**; the bank writes new loans every month and
monthly files add members to the existing scheme.

`plans/2026-09-10-group-scheme-substitution-and-notices.md:759` specifies that `addMember`
and `POST /group-schemes/{n}/members` are **removed, not deprecated**, in favour of
fixed-headcount one-out-one-in substitution. That is correct for an employer scheme and fatal
here.

**Fixed headcount becomes a per-scheme property rather than a global rule.** It was never a
truth about group schemes; it was a truth about employer schemes written as a global
constraint. That plan is uncommitted, so this is nearly free today and a migration later.

### 2.12 Loans leave by an explicit exits file

Monthly enrolment files add members and say nothing about loans that ended, so without a
second channel no refund or clawback can ever fire. The bank sends an **exits file**
(`loan_account_number`, `exit_date`, `exit_reason`, `outstanding_balance_at_exit`), with a
**quarterly full reconciliation** as the audit.

A reconciliation-only design is dangerous as the primary mechanism: one bank-side export
glitch drops fifty rows and fifty people silently lose cover. An exits file makes termination
a stated act.

A restructure, refinance or top-up is **always exit-and-re-enrol**, never an in-place
amendment — even when the loan account number is unchanged. Both halves are already being
built, and a restructured loan is a different risk over a different term that should be
priced as one.

A member-exit path does not exist today; only `dischargeForSettledClaim` removes a member.
It is required by three separate decisions above (§2.8 refunds, §2.12 exits, §2.12
re-enrolment) and is one build item.

### 2.13 Access: the bank is a policyholder with logins, in the `customers` realm

The bank is a **corporate policyholder party inside the insurer's tenant**, not a tenant.
Making each bank a tenant would force the insurer into cross-tenant reads of its own book,
which RLS exists to prevent.

Bank users live in the existing `customers` realm. Row-level scoping under tenant RLS already
works there and is already correct for this case: `PolicyController.enforceCustomerOwnPolicyOnly`
compares the token's `party_id` to `view.policyholderPartyId()`, and on a credit-life scheme
the bank *is* the policyholder.

The real gap is narrower than it first appears. `party_id` binds **one Keycloak user to one
INDIVIDUAL party**; nothing ties a user to a corporate party. Three loan officers at one bank
is N users → 1 corporate party, and that relationship does not exist. Closing it means
letting `party_id` reference a corporate party, allowing many users to carry the same one,
and auditing all 26 `REALM_CUSTOMERS`-gated endpoints for individual-only assumptions.

### 2.14 `regreporting` must consume member movement before this ships

`policy.GroupMemberAdded` and `GroupMemberExited` have **no consumer**, so
`regreporting.policy_dimension`'s sum assured goes stale whenever membership changes. On an
employer scheme that is occasional drift. Here members are added every month and exited on
every settlement, so it is continuous — and it lands in a TIRA return.

This is a pre-existing gap being inherited, not created, but credit life is what turns the
leak into a running tap. It is in scope.

---

## 3. The enrolment file

CSV only. The bank's existing file is a CSV, so Apache POI and the whole class of XLSX
type-coercion faults (doubles read back as `8499999.999999999`, numeric account numbers
rendered as `4.17E+11`, dates as serial numbers) are avoided entirely. An unexpected XLSX is
rejected with a clear message rather than parsed by a library nobody needed.

**Nine columns**, cut from thirteen on 2026-09-21 once the real client files arrived — see
`credit-life-enrolment-sample.csv` alongside this spec:

| Column | Req | Why |
|---|---|---|
| `loan_account_number` | ✓ | Member key (§2.1); duplicates reject |
| `borrower_full_name` | ✓ | Freeform member (§2.2) |
| `borrower_date_of_birth` | ✓ | Entry-age hard gate; identity at promotion |
| `borrower_sex` | — | Not priced on. Kept for TIRA reporting |
| `borrower_national_id` | — | Optional; eases the §2.2 promotion |
| `borrower_phone` | — | Optional |
| `loan_principal_amount` | ✓ | Premium base and schedule origin |
| `loan_term_months` | ✓ | Schedule input; term hard gate |
| `disbursement_date` | ✓ | **Cover start** (§2.6) |

**Four columns were removed rather than made optional**, because neither real lender file
carries any of them and a column nobody fills is a column that rots:

| Removed | Where it went |
|---|---|
| `annual_interest_rate_percent` | Nowhere. Straight-line decline never reads a rate |
| `repayment_frequency` | The **scheme**, beside `interest_method`. A lender's product repays on one cadence |
| `instalment_amount` | Gone with the withdrawn method inference |
| `first_repayment_date` | Derived as disbursement plus one period |

**That is one genuinely new column for the lenders, not four.** BUMACO's August sheet
already carries name, gender, date of birth, disbursed date, disbursed amount and term;
only the loan account number is missing.

**A moratorium can no longer be expressed.** `first_repayment_date` was the only way to
state a payment holiday, and every loan is now assumed to begin repaying one period after
disbursement. Client question 8 asks whether their book contains any; if it does, that
column returns. So does the rate, if the answer to the cover-decline question is
reducing-balance.

**No rating inputs.** Premium is an agreed percent, not rated through the base-rate table,
so credit life never touches the path where issuance *refuses* on an unrecorded sex. Interest
method, repayment cadence and currency are all scheme-level and are not columns.

**Partial accept.** Good rows enrol; bad rows return in a report
(`credit-life-rejection-report-sample.csv`) whose reason text says, per row, **THIS BORROWER
IS NOT COVERED**. Six outcomes today: `ENROLLED`, `ENROLLED_CAPPED`, and rejections for
`MISSING_REQUIRED_FIELD`, `DUPLICATE_LOAN_ACCOUNT_NUMBER`, `DISBURSEMENT_DATE_IN_FUTURE`,
`ENTRY_AGE_OR_TERM_OUT_OF_BOUNDS`, `INSTALMENT_MATCHES_NO_KNOWN_METHOD`.

This departs from `issueGroupScheme`, which is deliberately atomic — *"a failure part-way
through the schedule leaves no half-populated scheme."* The departure is intentional: a
400-person file bouncing for weeks over one typo leaves 399 people uninsured in the meantime.

The report reaches the bank **both** as a portal download and by email, and the bank
acknowledges it explicitly. A downloadable file nobody opens is not an acknowledgement, and
"this borrower is not covered" is the one message in this system that must be provably
received. It would be `communication`'s first non-policy template.

---

## 4. What has to be built

**Does not exist at all**

1. Freeform members — `policy/V13`, per the substitution plan's §3
2. Loan parameters on `policy_member`
3. Amortisation, both methods, plus the §2.5 inference
4. Decreasing cover through `claimableCover`
5. A member-exit path (serves §2.8, §2.12 twice)
6. CSV intake — `commons-csv`, the content-type allowlist (four values today, rejects CSV),
   the multipart limit (unconfigured, so 1 MB), submission entity, validation, rejection report
7. Submission state machine: one in flight, propose→accept, two distinct users
8. Single-premium calculation and one-invoice-per-file
9. Pro-rata refund and matching clawback
10. EFT disbursement type
11. `regreporting` consumer for `GroupMemberAdded`/`GroupMemberExited`
12. Rejection-report email templates and the acknowledgement action
13. The credit-life scheme page — a new page sharing the shell, not an extension of
    `GroupSchemePage`, which renders grades and benefit bases this product does not use

**Exists but must change**

14. `ProductCategory` gains `CREDIT_LIFE` — enum, DB check, wire type, and the ~8 places
    `GROUP_LIFE` currently gates behaviour
15. `plans/2026-09-10-group-scheme-substitution-and-notices.md` — headcount per-scheme,
    `addMember` survives (§2.11). **Do this before that plan lands.**
16. `referForEvidence` gains its first caller
17. `party_id` may reference a corporate party; many users may share one

**Deferred to the portal phase**

The staff upload path needs none of the following; the bank-facing portal needs all of it,
and all four are already on the production-readiness gate:

18. A public PKCE client on the `customers` realm (`staff` and `agents` have
    `lifeplatform-spa`; `customers` and `regulators` have no public client at all)
19. Real CORS — there is none anywhere; the SPA works by Vite dev-proxy only
20. A deployed same-origin story
21. Portal screens: upload, rejection report, submission history, invoices. Nothing else.

**Sequencing.** Staff path first. Cover flows, premiums invoice and claims pay with staff
doing the uploads; the product works end to end without the portal.

---

## 5. Known blockers outside this build

- **TIRA filing is mandatory at publish** (`ProductApiImpl:145`). The `CREDIT_LIFE` product
  cannot be published at all until a filing reference exists. A new filing is to be created
  as for any other product.
- **The commission engine has three broken links in dev**, and §2.8 makes them blocking
  rather than background: the close sweep was never applied so payout 409s, zero `OVERRIDE`
  rules exist, and 19 of 134 products have a plan.

---

## 6. Open with the client

Nine items. The last one answers four of the others by inspection and should be asked first.

1. The agreed percent, and written confirmation it is **per annum on the original principal**
   — a flat percent regardless of term insures five years of mortality for the price of one.
2. Acceptance of **pro-rata commission clawback** on early settlement (§2.8).
3. The **free cover limit** amount, and confirmation that cap-at-FCL (§2.7) matches
   expectation. Build 5 flagged this same rule as *"a market-standard assumption, not a quoted
   answer"*.
4. Confirmation of the **exclusions**: 12-month suicide, 12-month pre-existing, no general
   waiting period. Below the FCL there is no underwriting, so these are the only
   anti-selection control.
5. **Exits-file cadence** and how settlements will be notified (§2.12).
6. Whether the **borrower** — not only the bank — is told when a row is rejected and they are
   uncovered (§3). Recommended yes.
7. The **TIRA product class** this files under.
8. **§6 of the requirements table**, outstanding since 2026-09-03 and requested five times.
9. **Ten real rows filled into `credit-life-enrolment-sample.csv`.** A smaller ask than §6,
   and it settles by inspection whether they hold a loan account number, whether they give a
   term or a maturity date, a date of birth or an age, and whether their stated rate is flat
   or effective.

The interest method is **no longer on this list** — §2.5 infers it.

---

## 7. Deliberately out of scope

- **Disability and retrenchment cover.** Death only. `BenefitType` already has `DEATH` and
  `DISABILITY`, so TPD is cheap to add later; temporary disability and retrenchment pay
  *instalments over time*, which is a recurring-payment claim shape that does not exist.
- **Monthly balance declarations.** Cover is computed (§2.4). A monthly restatement of the
  whole live book would be one row per borrower per month forever instead of one row per
  borrower ever, and it would reopen the single-premium decision.
- **Premium netting.** Settling the bank's premium against the insurer's claims through one
  monthly account is where bancassurance ends up at volume and is genuinely elegant, but it
  needs netting semantics in `billing` and `finaccounting` that do not exist. Not before the
  first claim is paid.
- **Multi-currency.** Scheme-level, TZS only. A USD facility is a second scheme, not a column
  — per-loan currency would mean multi-currency sums assured on one master policy and FX at
  claim.
- **The broker/bancassurance type discriminator** (§2.8), until a second partner exists.
- **Platform-wide maker-checker.** §2.10 applies two-person acceptance to this flow only. The
  open pricing finding deserves the same treatment, but as its own decision.
