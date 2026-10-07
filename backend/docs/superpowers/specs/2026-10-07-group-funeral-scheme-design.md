# Group funeral scheme: an association's members and their families on one master policy

Design approved 2026-10-07. Branch `group-funeral`, from main 153078b2.

## 1. What it is

An employer or association holds one master policy. Many members join it, each with their own family -- spouse,
children, parents, extended family as the plan allows -- or alone. The **association pays one monthly bill**:
active members x the plan's group rate. A death of any covered life pays the plan's benefit for its role.

The user's real product: plan A1, main member 2,000,000, spouse 1,000,000, child 500,000; 3,000 per member per month
(36,000 a year), paid by the association.

Today the platform cannot sell it: group schemes (GROUP_LIFE, CREDIT_LIFE) cover member lives only -- a member has no
family -- and family cover exists only on the individual FUNERAL policy (one policy, one family). The family funeral
design (2026-10-04) agreed "individual first; a group (employer/SACCO) variant later reusing group scheme tables";
this is that variant.

## 2. Decisions (user answers)

1. **The association pays** one bill for the scheme.
2. **The bill is rate x active members** (Q2 a) -- families add nothing.
3. **Families are declared when the member joins** (Q3 a) and checked against the plan's family rules; a member with
   no family is listed alone ("this can apply to one person").
4. **Everything comes from the product** (Q4): benefits per role, family rules, waiting period, claim settings and the
   **group rate** are configured on the product's plan; setting up a scheme only chooses the association, the product
   and the plan.
5. **One plan per scheme** (Q5 a).
6. **Whole months, counted on the billing date** (Q6 a): a joiner is covered from joining and billed from the next
   bill; a leaver is covered to the end of the leaving month and is off the next bill; no part-month charges.
7. **Approach A:** a group scheme whose members carry families -- the group scheme tables (master policy, member
   schedule, freeform names, joining file, exits, claims naming the life) extended with a principal-member link and a
   role; the family funeral product supplies plans and rules.
8. Defaults accepted with the design: an unpaid bill follows the normal arrears path and lapses the whole scheme;
   family changes allowed any time (a new life's waiting period from its own date); the main member's death follows
   the product's setting (§6).

9. **Accounting (user confirmed):** group funeral stays in portfolio **FUN** (set on the product); the expense
   allocation's in-force driver counts **covered lives** for schemes.
10. **0-premium dependants (user approved, built with this):** on the individual FUNERAL premium table a dependant
    role's yearly premium may be **0** -- "included in the main member's premium" -- so a flat family rate is
    expressible on an individual policy too. The main member's rows stay > 0, so no policy is ever free.

## 3. The product (design part 1)

- A FUNERAL product version gains **"Sold as"**: `INDIVIDUAL`, `GROUP`, or `BOTH`.
  - GROUP: each plan carries a **group rate per member per month** (money, > 0); the per-age premium table is not
    required.
  - INDIVIDUAL: as today -- the premium table is required, the group rate is not.
  - BOTH: both required.
- Unchanged on the plan: benefits per role, role rules (allowed, most lives, entry ages, cover stops at, student to),
  waiting period, accident waiver, dependant claim payee, main-member-death rule.
- The monthly/quarterly loading % does not apply to a group rate (already a monthly price).
- An individual sale (case, quote, issue) is refused on a version sold as GROUP only; a scheme is refused on a version
  sold as INDIVIDUAL only.

## 4. Setting up a scheme (design part 1)

- The existing group scheme set-up (`IssueGroupSchemePage`, `PolicyApi.issueGroupScheme`) accepts a FUNERAL product
  version sold as GROUP or BOTH, with a **plan code**.
- New benefit basis **`FUNERAL_PLAN`**: each life's cover comes from its role in the plan. No free cover limit, no
  typed premium, no typed benefit.
- The scheme records the product version and plan it was set up on (pinned): a later product version does not change an
  existing scheme's cover or rate.
- Policyholder: the association, a registered organisation party. Start date as group schemes today (not in the
  future).
- **The proposal carries the opening members and their families** (user answer 2026-10-07, found while planning):
  every scheme is an offer until its first bill is paid, a policy's premium may not be zero
  (`chk_premium_amount_positive`), and members join only an in-force scheme -- so an empty funeral scheme could never
  go on cover. The group proposal's opening schedule (today registered parties with a grade or salary only) gains
  freeform lives and families, typed or **uploaded as a file** in the joining-file format; the underwriter sees the
  whole proposal; issuance creates the scheme with those lives and a first bill of opening members x rate; the
  association's first payment puts everyone on cover. Later joiners come by the scheme page or the joining file.

## 5. Members and families (design part 2)

- A **main member** is a member row (PARTY or FREEFORM: name + date of birth), role MAIN_MEMBER.
- Each **family member** is a member row with a **role** (SPOUSE, CHILD, PARENT, EXTENDED), name, date of birth, a
  student flag for children, and a link to its **principal member** (`principal_member_id`).
- **Checked at joining** against the plan's rules: role allowed; most lives per role (e.g. one spouse, up to six
  children) per family; entry age per role on the joining date; no life twice on the scheme (same name + date of birth
  under the same main member, or the same party). A refused life names the rule; the rest of the family still joins.
- **Each life's cover:** benefit = the plan's amount for its role; starts on its joining date; the waiting period runs
  from there; ends at the role's "cover stops at" age (the student age for a student child), on removal, or with its
  main member's leaving.
- **Joining:** one member at a time on the scheme page (main member, then family), or **by file** -- one row per life,
  family rows naming their main member's reference; the whole file is checked and every refused row reported.
- **Later changes:** add or remove a family member any time; removing a main member ends the family's cover at the end
  of that month.
- **Beneficiary:** a main member may name a beneficiary when joining (name, relationship, phone); otherwise it is taken
  at claim.
- **The schedule** groups each main member with their family: role, age, benefit, waiting-period end, status.

## 6. The bill and arrears (design part 3)

- The scheme bills **monthly**, on the scheme's start day of each month, to the association.
- Amount = **active main members on the billing date x the plan's group rate**; each bill carries the member count and
  rate ("312 members x 3,000").
- A joiner or leaver recomputes the monthly amount for the next billing date through `policy.PremiumRestated` (billing
  restates bills not yet raised or paid, as for family funeral). Bills already raised are not changed.
- At set-up the first bill is opening members x rate; an empty scheme bills nothing until its first member joins.
- **Arrears:** the product's grace period and the existing reminders to the association; unpaid, the **whole scheme
  lapses** and every life's cover ends from the lapse date; reinstated through the existing route once paid. A death in
  the grace period is covered; after the lapse it is not.
- Commission to a broker or agent on the scheme, on premiums collected, as group life; the premium levy if configured.

## 7. Claims (design part 4)

- A death claim **names the life** from the schedule (main member or family member). Checked: on the scheme and covered
  on the date of death; the waiting period from its joining date passed (an accident waives it if the product says
  so); the scheme not lapsed. Benefit = the plan's amount for the role. A freeform life is promoted to a party at claim.
- **Payee:** a dependant's death pays the main member or the nominated beneficiary (product setting); the main member's
  death pays their nominated beneficiary.
- **Main member's death** (product setting, read for schemes):
  - `POLICY_ENDS` -> that member and their family leave the scheme at the end of the month of death; the scheme carries
    on, the next bill falls by one;
  - `SPOUSE_TAKES_OVER` -> the spouse becomes that family's main member; cover continues; the bill is unchanged.

## 8. Accounting

No new posting rules. The scheme is one insurance contract, classified at issue from its product's portfolio (FUN,
PAA by the register baseline); members and families add lives to it, not contracts.

| Event | Journal (PAA) |
|---|---|
| Monthly bill (`billing.PremiumInvoiceGenerated`) | Dr 2142 / Cr 2141 (PRM_REN) |
| Association pays (`billing.PremiumCollected`) | Dr bank / Cr 2142; levy Dr 5220 / Cr 2650 |
| Cover month earned (`finaccounting.PaaRevenueEarned`) | Dr 2141 / Cr 4160 |
| A raised bill restated | `PremiumInvoiceIncreased/Reduced`: 2142 / 2141 |
| Claim approved / paid | Dr 5110 / Cr 2211; Dr 2211 / Cr bank |
| Commission | PAA acquisition expensed when incurred (5310) |

The expense allocation's maintenance driver counts **covered lives** on a scheme (one per life) rather than one per
policy, so a 500-life scheme carries its share of maintenance cost.

## 9. Console

- Product form: "Sold as" and, per plan, "Group rate per member per month"; the premium table optional when sold as
  GROUP.
- Group scheme set-up: a FUNERAL product and plan; no FCL or premium fields for the FUNERAL_PLAN basis.
- Scheme page: the schedule grouped by family; add a member, add a family member, remove; join by file with a per-row
  report; the bill's count x rate.
- Claim form: choose the life from the scheme's schedule.

## 10. Tests

- Product: "Sold as" validation (group rate required for GROUP/BOTH, premium table for INDIVIDUAL/BOTH); sale refused
  across the wrong channel.
- Joining: role rules -- counts, entry ages, duplicates -- with the reason per refused life; file joining with a report.
- Bill: members x rate on the billing date through joiners and leavers; empty scheme bills nothing; arrears lapse ends
  every life.
- Claims: waiting period, lapse, benefit per role, payee; main-member death under both rules.
- Accounting: a bill, a payment and a claim journal on the scheme's FUN-PAA group; the allocation driver counting
  lives.
- e2e: set up a group funeral product, open an association scheme, add a member with a spouse and a child, check the
  bill, claim the child's death.

## 11. Not in this build

- Members choosing among plans (one plan per scheme).
- Part-month (pro-rata) charges.
- Members paying their own premiums (the association pays).
- Self-service joining by the association (staff and file only, as group schemes today).
