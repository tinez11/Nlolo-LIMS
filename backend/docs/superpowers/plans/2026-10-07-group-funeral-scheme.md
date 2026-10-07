# Group Funeral Scheme Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An association's members and their families on one master policy, priced members x the plan's group rate and billed monthly to the association; plus 0-premium dependant rows on individual funeral policies.

**Architecture:** The FUNERAL product's plan gains a group rate and a "sold as" setting. A group scheme gains the basis `FUNERAL_PLAN`: its member rows are the main members (what the bill counts); each member's family -- and the main member themself -- are **covered lives** in the existing `policy.covered_life` table, now linked to their `policy_member`. That reuses the family funeral machinery: role rules, the age sweep, claims on a covered life, the waiting period, payee rules and second-claim refusal. The underwriting group proposal carries the opening members and families (typed or by file). Billing is driven by `policy.PremiumRestated` (members x rate). Finaccounting needs no new posting rule; its expense-allocation in-force driver counts covered lives.

**Tech Stack:** Spring Boot 3 / Java 21, JPA + JdbcTemplate, Postgres 16 (RLS), Testcontainers, React + zustand + Playwright.

Spec: `backend/docs/superpowers/specs/2026-10-07-group-funeral-scheme-design.md` (commits f0cce4ee, 12e08858, 7ea2ff8c).

## Global Constraints

- Worktree `.worktrees/group-funeral`, branch `group-funeral`. Host `./mvnw -B -o`, `-Dtest=` fully qualified; never Maven in Docker; stop the dev backend before `clean test` in a worktree it runs from; no Maven while Playwright runs; keep the machine awake for long runs (scratchpad `awake-e2e.ps1` pattern). Never run Prettier. Edit with Write/Edit.
- New migrations go through `node scripts/dev/append-test-migration.mjs <module>/<file>`; apply each to dev by hand (`docker exec -i infra-postgres-1 psql -U postgres -d lifeplatform -v ON_ERROR_STOP=1 --single-transaction < file`).
- `clean test-compile` after any signature change (incremental compile hides test breaks). A new mapped column on an entity forces its migration onto every test class listing that module -- prefer side tables or JDBC where the funeral work already did (`funeral_policy`, `funeral_claim`).
- Money BigDecimal scale 2; group rate is a monthly price (no frequency loading).
- Refusals name the rule and the life ("Child Amani (born 2003-04-01) is 22 on 2026-10-07; a child joins up to 20").
- The seeder (`backend/scripts/seed-dev-data.sh`) must keep working: mirror any request-shape change there.

## Structure decisions (refining the spec's storage, same behaviour)

- **R1 Lives are covered lives.** A FUNERAL_PLAN scheme's lives -- main member included -- are `covered_life` rows with a new `policy_member_id` (null on an individual funeral policy). `policy_member` rows exist only for main members (role MAIN_MEMBER): the bill counts them, exits act on them, the schedule pages them. Spec §5 called family members "member rows linked to the principal"; covered lives linked to the member is the same model on the table that already has role, student, benefit, cover start/end and the claim machinery.
- **R2 The family's plan state per member.** `funeral_policy` is 1:1 with a policy (plan, awaiting takeover). For a scheme the plan is on `group_scheme.plan_code`; a pending spouse takeover is per family, on `policy_member.awaiting_takeover_life_id` (new nullable column, policy migration).
- **R3 The bill.** `premium = active main members x group rate` on the policy (frequency MONTHLY). Every change in the count publishes `policy.PremiumRestated` with `effectiveFrom` = the next billing date (whole months, spec Q6 a); the restatement `reason` carries "N members x rate" so the bill shows it.
- **R4 Who is paid.** A dependant's death: the product's `dependantClaimPayee` read per family -- MAIN_MEMBER pays the main member (promoted to a party at claim if freeform), MAIN_MEMBER_BENEFICIARY pays the beneficiary named on the member. The main member's death pays the member's beneficiary (new nullable `policy_member.beneficiary_name/relationship/phone`).

---

### Task 1: The product -- sold as, group rate, 0-premium dependants

**Files:**
- Create: `backend/db-migrations/product/V<next>__funeral_group_rate.sql`
- Modify: `product/domain/FuneralPlanValidator.java`, `product/api/FuneralPlan.java`, the funeral terms request/response records (`product/infrastructure/FuneralRequest.java`, `FuneralTermsResponse`), `product/application/FuneralTermsStore.java`, the `funeral_plan`/`funeral_terms` entities
- Modify (console): `frontend/src/features/products/funeralSchema.ts`, `FuneralTermsSection.tsx`, their tests
- Test: `FuneralPlanValidatorTest` (extend), `funeralSchema.test.ts`

- [ ] **Migration:**

```sql
-- Group funeral schemes (2026-10-07): how a funeral version may be sold, and each plan's rate per member per month
-- for a scheme. A dependant's yearly premium may now be 0 ("included in the main member's premium").
ALTER TABLE product.funeral_terms
    ADD COLUMN sold_as VARCHAR(10) NOT NULL DEFAULT 'INDIVIDUAL' CHECK (sold_as IN ('INDIVIDUAL','GROUP','BOTH'));
ALTER TABLE product.funeral_plan
    ADD COLUMN group_monthly_rate NUMERIC(19,2) CHECK (group_monthly_rate IS NULL OR group_monthly_rate > 0);
```
plus relaxing the premium-row check if the table has one (`\d product.funeral_premium`; replace `yearly_premium > 0` with `>= 0`).
- [ ] **Validator rules** (each with a test): GROUP or BOTH -> every plan has a group rate > 0; INDIVIDUAL or BOTH -> the premium table's coverage rule as today, else the table may be empty; a premium row of 0 is allowed for every role except MAIN_MEMBER (`"A main member's premium must be above zero: nobody is covered free"`); the old `signum() <= 0` refusal becomes `< 0` for dependants.
- [ ] **API:** `FuneralPlan` gains `SoldAs soldAs` (enum INDIVIDUAL/GROUP/BOTH) and `Map<String, BigDecimal> groupRates` (plan code -> rate); request/response records gain `soldAs` and per plan `groupMonthlyRate`; OpenAPI (`openapi-product.yaml`) updated; `FuneralQuoter` refuses an individual quote on a GROUP-only version ("This product is sold to group schemes only").
- [ ] **Console:** "Sold as" select (Individual policies / Group schemes / Both); per plan "Group rate per member per month" input shown for Group/Both; the premium table not required (and hidden) for Group; `parsePremiums` accepts 0 for non-main roles; the coverage loop unchanged.
- [ ] Run `FuneralPlanValidatorTest`, the product contract and funeral product integration tests (`tz.co.nlolo.lifeplatform.funeral.FuneralProductIntegrationTest`), `npx vitest run funeralSchema`; commit `feat(product): funeral versions sold as individual, group or both; a group rate per plan; dependants may be included at 0`.

---

### Task 2: The group proposal -- FUNERAL_PLAN basis, opening families, by file

**Files:**
- Create: `backend/db-migrations/underwriting/V<next>__group_funeral_proposal.sql`
- Modify: `underwriting/api/GroupProposal.java` (+ `GroupBenefitBasis`), `underwriting/domain/ProposalGroupScheme.java`, new `underwriting/domain/ProposalGroupLife.java` + repository, `underwriting/application/UnderwritingApiImpl.java` (proposal intake), its HTTP request DTO, `FuneralApplications` (reuse the role-rule checks for each family)
- Create: `underwriting/domain/FuneralScheduleFile.java` (pure CSV parser)
- Test: `FuneralScheduleFileTest` (pure), `GroupFuneralProposalIntegrationTest`

- [ ] **Migration:** `proposal_group_scheme.benefit_basis` CHECK gains `FUNERAL_PLAN`; new nullable `plan_code`; `premium_amount` becomes nullable with a CHECK "present unless FUNERAL_PLAN"; the basis-parameter CHECK gains `(benefit_basis = 'FUNERAL_PLAN' AND plan_code IS NOT NULL AND flat_benefit_amount IS NULL AND salary_multiple IS NULL)`. New table:

```sql
CREATE TABLE underwriting.proposal_group_life (
    proposal_group_life_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL,
    case_id          UUID NOT NULL REFERENCES underwriting.proposal_group_scheme(case_id),
    member_reference VARCHAR(40) NOT NULL,   -- the association's own number for the main member; family rows share it
    role             VARCHAR(12) NOT NULL CHECK (role IN ('MAIN_MEMBER','SPOUSE','CHILD','PARENT','EXTENDED')),
    full_name        VARCHAR(200) NOT NULL,
    date_of_birth    DATE NOT NULL,
    sex              VARCHAR(6),
    id_number        VARCHAR(40),
    student          BOOLEAN NOT NULL DEFAULT false,
    party_id         UUID,
    beneficiary_name VARCHAR(200), beneficiary_relationship VARCHAR(40), beneficiary_phone VARCHAR(30),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_proposal_group_life_main ON underwriting.proposal_group_life (case_id, member_reference)
    WHERE role = 'MAIN_MEMBER';
-- RLS (NULLIF form) + grants as V9
```
- [ ] **File format** (`FuneralScheduleFile.parse(byte[] csv)` -> lines + row errors, pure): header `member_reference,role,full_name,date_of_birth,sex,id_number,student,beneficiary_name,beneficiary_relationship,beneficiary_phone`; dates `YYYY-MM-DD` or `DD/MM/YYYY`; one MAIN_MEMBER row per reference; family rows naming an unknown reference are errors; every error carries its row number. Tests: a member alone; a family of four; a child without a main member; two main members with one reference; a bad date.
- [ ] **Intake rules:** a FUNERAL_PLAN proposal needs a FUNERAL product version sold as GROUP or BOTH and a plan code on it; no premium (computed at issue); at least one main member; each family checked against the plan's role rules on the commencement date with `FuneralApplications`' existing checks (allowed role, most lives per role, entry ages) -- every refusal collected and returned at once (`FinaccountingValidationException`-style 422 listing them is the pattern here: use the underwriting module's validation exception).
- [ ] **HTTP:** the group proposal request gains `planCode` and `lives` (typed lines); `POST /underwriting/cases/{id}/group-schedule` (multipart CSV) replaces the case's lines from a file and answers the per-row report; OpenAPI updated.
- [ ] Tests: the parser (pure); a proposal with two families stored and read back on the case view; an over-age child refused with its sentence; a GROUP_LIFE proposal unchanged (regression). Commit `feat(underwriting): a group funeral proposal carries its opening members and families, typed or by file`.

---

### Task 3: Issuing a group funeral scheme

**Files:**
- Create: `backend/db-migrations/policy/V<next>__group_funeral_scheme.sql`
- Modify: `policy/api/BenefitBasis.java` (+`FUNERAL_PLAN`), `PolicyApi.IssueGroupSchemeRequest` (+ `planCode`, `lives`), `policy/application/PolicyApiImpl.issueGroupScheme`, `UnderwritingDecisionEventListener.issueSchemeFromProposal`, `policy/domain/GroupScheme` (+plan code), `CoveredLife` (+`policyMemberId`), `PolicyMember` (+ beneficiary, awaiting takeover -- as JDBC-written columns if a mapped column would force the migration onto unrelated tests; check how `funeral_policy` avoided it)
- Create: `policy/application/GroupFuneralLives.java` (the scheme-side counterpart of `CoveredLives`)
- Test: `GroupFuneralSchemeIntegrationTest`

- [ ] **Migration:** `group_scheme.benefit_basis` CHECK gains `FUNERAL_PLAN`, new nullable `plan_code` with "present iff FUNERAL_PLAN"; `covered_life.policy_member_id UUID NULL REFERENCES policy.policy_member`; `policy_member` gains `beneficiary_name`, `beneficiary_relationship`, `beneficiary_phone`, `awaiting_takeover_life_id`; index `covered_life (tenant_id, policy_number, policy_member_id)`.
- [ ] **issueGroupScheme** for a FUNERAL product: requires `FUNERAL_PLAN` and a plan code sold to groups (and the reverse -- FUNERAL_PLAN only on FUNERAL); no FCL, no flat/salary/grades, no typed premium; premium = main members x the plan's group rate, frequency MONTHLY; members = one FREEFORM (or PARTY) `policy_member` per main member, `joined_on` = commencement; covered lives per life with `policy_member_id`, benefit = the plan's role benefit, `yearly_premium` 0 (the bill is per member), cover start = commencement; scheme sum assured = sum of all lives' benefits; `PolicyIssued` as today (premiumPerEnrolment false).
- [ ] `issueSchemeFromProposal` maps the proposal's plan code and lives.
- [ ] Tests: a two-family proposal decided -> scheme PROPOSED with 2 members, 6 lives, premium 2 x 3,000 MONTHLY, sum assured = benefits; first invoice 6,000; activation on payment as any scheme; a FUNERAL_PLAN scheme on a TERM_LIFE product refused. Commit `feat(policy): a group funeral scheme issued from its proposal -- main members, their families as covered lives, members x rate`.

---

### Task 4: Members and families on an issued scheme; the bill follows

**Files:** `GroupFuneralLives.java`, `PolicyApiImpl` (`addMember`, `exitMember` branches for FUNERAL_PLAN), `PolicyController` (+ family endpoints), `CoveredLifeSweep` (scheme lives), OpenAPI
- Test: `GroupFuneralMembersIntegrationTest`

- [ ] `addMember` on FUNERAL_PLAN: a main member with optional family lives, checked against the plan's role rules on the joining date; cover from joining date; restate (R3).
- [ ] `POST /group-schemes/{n}/members/{memberId}/lives` (add a family life), `DELETE .../lives/{coveredLifeId}` (remove, with reason): role rules re-checked for the family; no bill change (families are free).
- [ ] `exitMember` on FUNERAL_PLAN: exit date forced to the end of the month (spec Q6 a); every life of the family ends then; restate from the next billing date.
- [ ] Restatement: `GroupFuneralLives.restate(policy)` -> premium = active main members on the next billing date x rate; publishes `policy.PremiumRestated` {policyNumber, premiumAmount, effectiveFrom = next billing date, reason "N members x rate"}; skipped when unchanged.
- [ ] The age sweep ends a scheme life at its role's stop age exactly as for an individual life (the sweep queries by policy; include scheme lives).
- [ ] Tests: a joiner before the billing date restates the next bill up by 3,000; a leaver mid-month covered to month end, the next bill down; adding a second spouse refused; a child ageing out ends that life only. Commit `feat(policy): group funeral members join and leave with their families; the monthly bill follows the count`.

---

### Task 5: The joining file on an issued scheme

**Files:** `policy/application/GroupFuneralJoining.java` (reuses the Task 2 CSV format through a copy of the pure parser in `policy.domain` -- the modules may not share it), `PolicyController` (`POST /group-schemes/{n}/funeral-joiners`, multipart), OpenAPI
- Test: `GroupFuneralJoiningIntegrationTest`

- [ ] The whole file is parsed and every family checked; valid families join (one restatement for the file), refused rows come back with their reasons in a per-row report (CSV download as the credit-life report does). A family is all-or-nothing: one refused life refuses its family, named.
- [ ] Tests: a 3-family file with one bad child -> 2 families join, 1 refused with the child's sentence; the bill restated once. Commit `feat(policy): group funeral joiners by file, family by family, with a per-row report`.

---

### Task 6: Claims on a scheme life

**Files:** `PolicyApiImpl.claimableCover` / `funeralClaimFacts` / `dischargeForSettledClaim` (scheme branch through `GroupFuneralLives`), `claims/application/FuneralClaims` (payee per family, R4), `ClaimsApiImpl` (a scheme claim names `coveredLifeId`, not `policyMemberId`)
- Test: `GroupFuneralClaimIntegrationTest`

- [ ] A death claim on a FUNERAL_PLAN scheme names the covered life; checked: covered on the date of death; the waiting period from that life's cover start (accident waiver as the product says); the scheme in force (not lapsed); benefit = the life's stored benefit; a second death claim on the life refused (existing).
- [ ] Payee (R4). Main member's death: `POLICY_ENDS` -> the member exits at the end of the month of death with the family; `SPOUSE_TAKES_OVER` -> the spouse's covered life becomes the family's main member (the `policy_member` keeps the family; `awaiting_takeover_life_id` while the staff step is pending, as individual funeral does).
- [ ] Tests: a child's death after the waiting period approved for 500,000; within it refused (accidental waived when set); a main member's death under each rule; a claim on a lapsed scheme refused. Commit `feat(claims): death claims on a group funeral scheme's lives`.

---

### Task 7: Billing and accounting checks

**Files:** none expected beyond tests -- verify; `finaccounting/application/EnginePolicySnapshots` + `ExpenseAllocations.groups` (lives driver)
- Test: extend `GroupFuneralSchemeIntegrationTest` (ledger), `ExpenseAllocationIntegrationTest` (lives)

- [ ] Ledger: the scheme's invoice, collection and PAA earning post on its FUN-PAA group (existing rules I-01, I-02, I-03); a restated undue invoice posts the delta; a claim posts 5110 / 2211.
- [ ] Lives driver: `policy_snapshot` gains `lives INTEGER NOT NULL DEFAULT 1` (finaccounting migration), maintained from `policy.CoveredLifeAdded` / `CoveredLifeEnded` (scheme and individual funeral) and `policy.GroupMemberAdded` / `GroupMemberExited` (other schemes: one life per member); `ExpenseAllocations.groups` sums `lives` instead of `count(*)`.
- [ ] Tests: a scheme of 2 families / 6 lives counts 6 in the maintenance driver. Commit `feat(finaccounting): the expense allocation counts covered lives; group funeral ledger proved`.

---

### Task 8: Console

**Files:** the group proposal form (the page that opens a group case -- find it from `staff-group-schemes.spec.ts`), `GroupSchemePage.tsx` (schedule grouped by family, add member with family, add/remove a life, exit, join by file, bill count x rate), the claim form (choose a scheme life), `api/types.ts` after `npm run generate:api`
- Test: vitest for new helpers (family grouping, file report rendering)

- [ ] Proposal: product FUNERAL -> basis "Funeral plan", plan picker (plans sold to groups, with their rate), opening schedule as families (typed: main member + lives) or "Upload schedule" (CSV) with the row report.
- [ ] Scheme page: families collapsible under each main member (role, age, benefit, waiting period end, status); "Add member", "Add family member", "Remove", "Member leaves"; "Join by file"; the next bill "N members x rate".
- [ ] Claim form: on a FUNERAL_PLAN scheme, pick the life from a family list; "Accidental death" checkbox.
- [ ] `tsc -b --noEmit`, `eslint src`, `vitest run` the new files; commit `feat(console): group funeral schemes -- proposals with families, the family schedule, joiners by file, claims on a life`.

---

### Task 9: Seed, e2e

- [ ] `seed-dev-data.sh`: a FUNERAL product `FUN-GRP-01` sold as GROUP with plan A1 (2,000,000 / 1,000,000 / 500,000, rate 3,000, the user's family rules) -- mirror the request shape from Task 1.
- [ ] `frontend/e2e/staff-group-funeral.spec.ts`: set up (or use the seeded) group funeral product; propose a scheme for an association with one member + spouse + child; underwrite and issue; pay the first bill (as the group-schemes spec pays one); add a joiner and see the next bill "2 members x 3,000"; register the child's death after the waiting period (backdated cover start) and see 500,000 approved.
- [ ] Existing e2e that create funeral products keep passing (the new "Sold as" defaults to Individual policies).

---

### Task 10: Gate, merge

- [ ] Apply the product, underwriting, policy and finaccounting migrations to dev; restart backend + Vite from the worktree; run the group-schemes, family-funeral and new specs.
- [ ] Gate: dev backend stopped; `clean test` of every class under policy, underwriting, product, claims, billing, funeral, unitlinked, finaccounting, plus every class whose migration list gained a new file (FQNs, count reports); console tsc/eslint/full vitest; full e2e (machine awake).
- [ ] Merge `--no-ff` "Merge group funeral schemes", push main and the branch; update memory.
