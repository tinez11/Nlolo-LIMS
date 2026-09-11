# Group Underwriting Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put group business through the same pipeline individual business already goes through — a scheme is proposed as an underwriting case, sits in the underwriter's queue, is decided by a person, and becomes an offer whose cover starts when the employer's first premium clears.

**Architecture:** The underwriting case already fits a scheme almost unchanged — the applicant is the employer and `life_assured_party_id` is already nullable because a GROUP_LIFE policy answers "who is insured" with NULL. What has nowhere to live is the scheme's *terms* and its *opening schedule*, so those go on the case, mirroring `underwriting.proposal_beneficiary`, which is already the pattern for "proposal data the case holds and issuance copies". The decision listener then branches: a group case issues a scheme, an individual case issues a policy, and both land in `PROPOSED`. `activateOnFirstPremium` already activates any PROPOSED policy, so a scheme goes on risk on the employer's first premium with no change to billing at all.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring Modulith, Flyway (per-module directories, applied by `scripts/migrate.sh`, NOT on boot), Postgres 16, Testcontainers, JUnit 5, AssertJ; React + TypeScript, Zustand, zod, Vitest, Playwright.

## Global Constraints

These are the four decisions this plan is built on. Each was taken deliberately; none is an implementation detail to revisit while building.

- **An accepted group case becomes an OFFER.** The scheme is issued `PROPOSED`, and cover starts when the employer's first premium clears — exactly as individual business works since offer-and-acceptance. This **reverses** [build5 §2.6](../specs/2026-09-03-build5-group-business-design.md)'s "a scheme goes on risk at issuance, outside offer-and-acceptance". That reversal is the point of this plan, not a side effect of it.
- **A group case gets NO rules-engine recommendation.** `RiskProfile` is age band × sum assured band plus assessment scores; group underwriting looks at scheme size, industry, claims experience and average age, none of which exist on this platform. Do not invent a group rating rule. A case with no recommendation is already legal and already documented — *"An ABSENT recommendation is not a disagreement"* — so the underwriter simply decides and **no senior sign-off is demanded**, because there is nothing to depart from.
- **A group case gets NO rating multiplier either, and its premium is NOT computed.** `resolveAgeMultiplier` reads the *applicant's* date of birth, which for an employer is meaningless, and the individual premium formula prices one life. A scheme's premium is the figure agreed with the employer and carried on the proposal. The listener must use it verbatim.
- **A member above the free cover limit does NOT get their own underwriting case.** Confirmed decision. They stay `EVIDENCE_REQUIRED`, covered at the limit, and the excess is never granted. **Consequence to state rather than discover:** the FCL is a hard cap on any member's cover, and `MemberUnderwritingStatus.ACCEPTED` becomes unreachable in practice. Do not wire `PolicyMember.referForEvidence` in this plan.

Platform rules that apply to every task:

- Every new `underwriting` migration must be appended to the migration list in all **37** test classes that carry one. A migration in no test class has never run in any test schema.
- Migrations are NOT applied on boot (`spring.flyway.enabled: false`). Apply each by hand before any real-stack check: `docker exec -i infra-postgres-1 psql -U postgres -d lifeplatform -v ON_ERROR_STOP=1 < db-migrations/<mod>/<file>.sql`
- **`./mvnw -o clean` fails while the dev backend is running** — it holds `target/lifeplatform.jar` open. Use `rm -rf target/classes target/test-classes && ./mvnw -o test-compile -DskipTests`. Do this after every signature change: plain `test-compile` prints BUILD SUCCESS while skipping files broken by one, and surefire reporting "Tests run: 0" for a class that has tests means stale classes, not missing tests.
- Whole test classes are separated by **commas** in `-Dtest='A,B,C'`. A `+` selects nothing.
- Never run Maven and Playwright (or vitest) at once — the workers starve and fail for reasons that are not code. Never delete `target/` while a suite is running.
- `policy` may depend on `underwriting::api`; `underwriting` may NOT depend on `policy`. Everything issuance needs must be readable from the case.
- Do not run Prettier. Money is `NUMERIC(19,2)`; never render an amount without its currency.

---

## What exists, and what has to be built

**Already fits a scheme unchanged:** `underwriting_case` (applicant = employer; `life_assured_party_id` nullable), the queue (`listCases`), `submitAssessment`, `decide` with its override gate, `UnderwritingDecisionMade`, and `PolicyApiImpl.activateOnFirstPremium`, which activates any `PROPOSED` policy and therefore needs no change at all.

**Has nowhere to live today:** the benefit basis, flat amount / salary multiple / grade table, free cover limit, currency, premium and the opening schedule. They exist only as arguments to `issueGroupScheme`, which creates policy, scheme and members atomically. In a pipeline they must sit on the case first, so an underwriter can look at them before a contract exists.

**Direct precedent:** `underwriting.proposal_beneficiary` holds nominations taken on the proposal and issuance copies them into `policy.beneficiary` — the two are "deliberately identical in shape". A proposed schedule on the case is that same pattern, one size up.

---

## File Structure

**Backend — created**

| File | Responsibility |
|---|---|
| `db-migrations/underwriting/V9__group_proposal.sql` | `proposal_group_scheme`, `proposal_group_grade`, `proposal_group_member` |
| `underwriting/api/GroupProposal.java` | The scheme terms + schedule as taken on the proposal |
| `underwriting/domain/ProposalGroupScheme.java`, `ProposalGroupGrade.java`, `ProposalGroupMember.java` | Entities |
| `underwriting/infrastructure/ProposalGroupSchemeRepository.java`, `ProposalGroupGradeRepository.java`, `ProposalGroupMemberRepository.java` | Reads/writes |
| `underwriting/infrastructure/GroupProposalDto.java` | Wire shape on `POST /underwriting/cases` |

**Backend — modified**

| File | Change |
|---|---|
| `underwriting/api/UnderwritingApi.java` | `openCase` overload taking a `GroupProposal`; `getCase` exposes it |
| `underwriting/api/UnderwritingCaseView.java` | `groupProposal` component (`@JsonIgnore`) + serialized `groupScheme` boolean |
| `underwriting/application/UnderwritingApiImpl.java` | Persist the proposal; skip the engine and the rating multiplier for a group case |
| `underwriting/infrastructure/UnderwritingController.java`, `OpenCaseRequest.java` | Accept a group proposal |
| `policy/api/PolicyApi.java`, `policy/application/PolicyApiImpl.java` | `issueGroupScheme` takes an `issuanceBasis` and lands `PROPOSED` unless it starts cover |
| `policy/application/UnderwritingDecisionEventListener.java` | Branch: group case → `issueGroupScheme`, individual → `issuePolicy` |
| `api/openapi/openapi-underwriting.yaml`, `openapi-policy.yaml` | Contract follows |

**Frontend — modified**

`src/features/policies/IssueGroupSchemePage.tsx` (becomes *propose*, posting a case), `src/features/underwriting/UnderwritingQueuePage.tsx` and `UnderwritingCaseDetailPage.tsx` (render a scheme case), `src/api/underwriting.ts`, `src/store/underwritingStore.ts`, `e2e/staff-group-schemes.spec.ts`

---

## Task 1: A case can carry a proposed scheme

**Files:**
- Create: `backend/db-migrations/underwriting/V9__group_proposal.sql`, `underwriting/api/GroupProposal.java`, `underwriting/domain/ProposalGroupScheme.java`, `ProposalGroupGrade.java`, `ProposalGroupMember.java`, `underwriting/infrastructure/ProposalGroupSchemeRepository.java`, `ProposalGroupGradeRepository.java`, `ProposalGroupMemberRepository.java`
- Modify: `underwriting/api/UnderwritingApi.java`, `underwriting/api/UnderwritingCaseView.java`, `underwriting/application/UnderwritingApiImpl.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/underwriting/GroupProposalIntegrationTest.java` (new)

**Interfaces:**
- Consumes: `underwriting_case` as it stands; `PartyApi.getParty`.
- Produces, for Tasks 2–6:
  - `record GroupProposal(BenefitBasis benefitBasis, BigDecimal flatBenefitAmount, BigDecimal salaryMultiple, BigDecimal fclAmount, String currency, List<GradeLine> grades, List<MemberLine> openingSchedule, BigDecimal premiumAmount, String premiumCurrency, String premiumFrequency, LocalDate commencementDate, Integer policyTermMonths)` in `underwriting.api`
  - `record GroupProposal.GradeLine(String gradeCode, BigDecimal benefitAmount)`
  - `record GroupProposal.MemberLine(UUID memberPartyId, String gradeCode, BigDecimal salaryAmount)`
  - `UnderwritingCaseView.groupProposal()` — the proposal, or null on an individual case
  - `UnderwritingCaseView.groupScheme()` — `true` when a proposal is present; serialized, so the console can tell them apart in a list
  - `UnderwritingApi.openCase(UUID applicantPartyId, UUID productId, UUID productVersionId, UUID agentOfRecordId, GroupProposal proposal, String openedBy)`

`BenefitBasis` lives in `policy.api` today. **Do not import it into underwriting** — that is a `underwriting → policy` dependency and `ModularityTests` rejects it. Declare `underwriting.api.GroupBenefitBasis` with the same three values and map at the boundary in the policy-side listener, exactly as `underwriting.api.BeneficiaryNomination` and `policy.api.BeneficiaryInput` are already two identical records for this reason.

- [ ] **Step 1: Write the failing test**

New `GroupProposalIntegrationTest.java`, copying `UnderwritingApiIntegrationTest`'s container and migration setup and adding `db-migrations/underwriting/V9__group_proposal.sql`:

```java
    @Test
    void aGroupCaseCarriesItsTermsAndItsOpeningSchedule() {
        TenantContext.set(tenantId);
        UUID employer = person("ABC Company");
        UUID first = person("Juma Employee");
        UUID second = person("Asha Employee");
        ProductFixture product = groupProduct("GRP-PROPOSAL-01");

        UnderwritingCaseView opened = underwritingApi.openCase(employer, product.productId(),
            product.productVersionId(), null,
            new GroupProposal(GroupBenefitBasis.FLAT, new BigDecimal("5000000.00"), null, null, "TZS",
                List.of(),
                List.of(new GroupProposal.MemberLine(first, null, null),
                        new GroupProposal.MemberLine(second, null, null)),
                new BigDecimal("1200000.00"), "TZS", "ANNUALLY", LocalDate.now(), null),
            "underwriter1");

        UnderwritingCaseView read = underwritingApi.getCase(opened.caseId());
        assertThat(read.groupScheme()).isTrue();
        assertThat(read.groupProposal().benefitBasis()).isEqualTo(GroupBenefitBasis.FLAT);
        assertThat(read.groupProposal().flatBenefitAmount()).isEqualByComparingTo(new BigDecimal("5000000.00"));
        assertThat(read.groupProposal().openingSchedule()).hasSize(2);
        assertThat(read.groupProposal().premiumAmount()).isEqualByComparingTo(new BigDecimal("1200000.00"));

        // NULL sum assured, on purpose: valuing the schedule needs GroupBenefitCalculator,
        // which lives in policy and which underwriting must not re-implement. The figure
        // appears when policy derives it at issuance, in the one place that owns it.
        assertThat(read.sumAssuredAmount()).isNull();
        assertThat(read.lifeAssuredPartyId())
            .as("an employer is not a life assured; the lives are the schedule")
            .isNull();
    }

    @Test
    void anIndividualCaseCarriesNoGroupProposal() {
        TenantContext.set(tenantId);
        UnderwritingCaseView opened = underwritingApi.openCase(person("Solo Applicant"),
            individualProduct.productId(), individualProduct.productVersionId(),
            new BigDecimal("2000000"), "TZS", null, "underwriter1");

        assertThat(underwritingApi.getCase(opened.caseId()).groupScheme()).isFalse();
        assertThat(underwritingApi.getCase(opened.caseId()).groupProposal()).isNull();
    }

    @Test
    void aGroupProposalMustNameAtLeastOneLife() {
        // Same rule issueGroupScheme already enforces, moved to where the proposal is taken:
        // a scheme's sum assured IS the total of its schedule, so an empty one is a contract
        // insuring nobody for nothing. Catching it here means it is refused before an
        // underwriter spends time on the case.
        TenantContext.set(tenantId);
        UUID employer = person("ABC Company");
        ProductFixture product = groupProduct("GRP-PROPOSAL-EMPTY");

        assertThatThrownBy(() -> underwritingApi.openCase(employer, product.productId(),
            product.productVersionId(), null,
            new GroupProposal(GroupBenefitBasis.FLAT, new BigDecimal("5000000.00"), null, null, "TZS",
                List.of(), List.of(), new BigDecimal("1200000.00"), "TZS", "ANNUALLY", LocalDate.now(), null),
            "underwriter1"))
            .isInstanceOf(UnderwritingValidationException.class)
            .hasMessageContaining("at least one life");
    }

    @Test
    void aGroupProposalMustBeAgainstAGroupProduct() {
        // The mirror of issueGroupScheme's own check ("A group scheme needs a GROUP_LIFE
        // product"), applied at proposal time so the case cannot be decided into an issuance
        // that will then refuse it.
        TenantContext.set(tenantId);
        assertThatThrownBy(() -> underwritingApi.openCase(person("ABC Company"),
            individualProduct.productId(), individualProduct.productVersionId(), null,
            new GroupProposal(GroupBenefitBasis.FLAT, new BigDecimal("5000000.00"), null, null, "TZS",
                List.of(), List.of(new GroupProposal.MemberLine(person("A Life"), null, null)),
                new BigDecimal("1200000.00"), "TZS", "ANNUALLY", LocalDate.now(), null),
            "underwriter1"))
            .isInstanceOf(UnderwritingValidationException.class)
            .hasMessageContaining("GROUP_LIFE");
    }
```

Fixtures for this class, written once and used by every test above:

```java
    @Autowired private UnderwritingApi underwritingApi;
    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;

    private static final AtomicInteger SEQ = new AtomicInteger(3000);
    private UUID tenantId;
    private ProductFixture individualProduct;

    private record ProductFixture(UUID productId, UUID productVersionId) {}

    @BeforeEach
    void freshTenant() {
        tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        individualProduct = publishProduct("IND-" + SEQ.incrementAndGet(), ProductCategory.TERM_LIFE);
    }

    private UUID person(String name) {
        return partyApi.registerIndividual(name, LocalDate.of(1985, 6, 15),
            "+2557" + String.format("%08d", SEQ.incrementAndGet()), null, "test").partyId();
    }

    private ProductFixture groupProduct(String code) {
        return publishProduct(code, ProductCategory.GROUP_LIFE);
    }

    private ProductFixture publishProduct(String code, ProductCategory category) {
        ProductSummaryView product = productApi.createProduct(code, "Test " + code, category, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")), null, "actuary");
        return new ProductFixture(product.productId(),
            productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId());
    }
```

- [ ] **Step 2: Run it and watch it fail**

```
cd backend && ./mvnw -o test -Dtest='GroupProposalIntegrationTest' -DfailIfNoTests=false
```

Expected: FAIL to compile — `GroupProposal`, `GroupBenefitBasis` and the `openCase` overload do not exist.

- [ ] **Step 3: Write the migration**

`backend/db-migrations/underwriting/V9__group_proposal.sql`:

```sql
-- db-migrations/underwriting/V9__group_proposal.sql
-- A group scheme, before it is a contract.
--
-- Group business bypassed underwriting entirely. POST /group-schemes created the policy, the
-- scheme and every member in one call, ACTIVE on return -- no case, no assessment, no
-- decision, and (until the role gate) any staff token. Meanwhile individual business had a
-- queue, a person's decision, a senior gate on departing from advice, and an offer the
-- customer accepts by paying. Two flows, one platform, and the one insuring five hundred
-- people at a time was the unsupervised one.
--
-- So a scheme is proposed as a case first. What had nowhere to live is here: the terms, the
-- grade table, and the schedule of lives being asked for.
--
-- MODELLED ON proposal_beneficiary, deliberately and not by coincidence. That table holds
-- nominations taken on the proposal and issuance copies them into policy.beneficiary, "the two
-- deliberately identical in shape". This is the same idea one size up, and the same rule
-- applies: underwriting holds what was ASKED FOR, policy holds what was GRANTED, and neither
-- reads the other's tables.
CREATE TABLE underwriting.proposal_group_scheme (
    -- 1:1 with the case, so the case id is the key. A row here IS the statement "this case is
    -- a scheme", which is why nothing else needs a discriminator column.
    case_id             UUID PRIMARY KEY REFERENCES underwriting.underwriting_case(case_id),
    tenant_id           UUID NOT NULL,

    benefit_basis       VARCHAR(20) NOT NULL CHECK (benefit_basis IN ('FLAT','SALARY_MULTIPLE','GRADED')),
    flat_benefit_amount NUMERIC(19,2) CHECK (flat_benefit_amount IS NULL OR flat_benefit_amount > 0),
    salary_multiple     NUMERIC(6,2)  CHECK (salary_multiple IS NULL OR salary_multiple > 0),
    fcl_amount          NUMERIC(19,2) CHECK (fcl_amount IS NULL OR fcl_amount > 0),
    currency            CHAR(3) NOT NULL,

    -- The premium AGREED with the employer, not a computed one. A scheme is not priced by the
    -- individual formula: that prices one life from one age band, and resolveAgeMultiplier
    -- would read the EMPLOYER's date of birth, which insures nobody. Carried verbatim to
    -- issuance.
    premium_amount      NUMERIC(19,2) NOT NULL CHECK (premium_amount > 0),
    premium_currency    CHAR(3) NOT NULL,
    premium_frequency   VARCHAR(20) NOT NULL,

    commencement_date   DATE,
    policy_term_months  INTEGER CHECK (policy_term_months IS NULL OR policy_term_months > 0),

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          VARCHAR(100),

    -- The basis and its parameter must agree, exactly as policy.group_scheme's own
    -- group_scheme_basis_parameter_present does. A SALARY_MULTIPLE proposal with no multiple
    -- cannot value anybody; a FLAT one carrying a multiple is telling two stories.
    CONSTRAINT proposal_group_basis_parameter_present CHECK (
        (benefit_basis = 'FLAT'            AND flat_benefit_amount IS NOT NULL AND salary_multiple IS NULL)
     OR (benefit_basis = 'SALARY_MULTIPLE' AND salary_multiple     IS NOT NULL AND flat_benefit_amount IS NULL)
     OR (benefit_basis = 'GRADED'          AND flat_benefit_amount IS NULL     AND salary_multiple IS NULL)
    )
);

CREATE TABLE underwriting.proposal_group_grade (
    proposal_group_grade_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    case_id             UUID NOT NULL REFERENCES underwriting.proposal_group_scheme(case_id),
    grade_code          VARCHAR(30) NOT NULL,
    benefit_amount      NUMERIC(19,2) NOT NULL CHECK (benefit_amount > 0),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (case_id, grade_code)
);

CREATE TABLE underwriting.proposal_group_member (
    proposal_group_member_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    case_id             UUID NOT NULL REFERENCES underwriting.proposal_group_scheme(case_id),
    member_party_id     UUID NOT NULL,
    grade_code          VARCHAR(30),
    salary_amount       NUMERIC(19,2) CHECK (salary_amount IS NULL OR salary_amount > 0),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- One proposal line per person. A schedule naming somebody twice is an employer's
    -- spreadsheet error, and admitting it would double their cover and the scheme's total.
    UNIQUE (case_id, member_party_id)
);
CREATE INDEX idx_proposal_group_member_case ON underwriting.proposal_group_member (case_id);

-- No joined_on column, unlike policy.policy_member. Every life on an opening schedule joins
-- when the scheme commences -- that is what an opening schedule MEANS -- and a per-line date
-- here would be a second answer to a question the commencement date already settles.

ALTER TABLE underwriting.proposal_group_scheme ENABLE ROW LEVEL SECURITY;
CREATE POLICY proposal_group_scheme_tenant_isolation ON underwriting.proposal_group_scheme
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE underwriting.proposal_group_grade ENABLE ROW LEVEL SECURITY;
CREATE POLICY proposal_group_grade_tenant_isolation ON underwriting.proposal_group_grade
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE underwriting.proposal_group_member ENABLE ROW LEVEL SECURITY;
CREATE POLICY proposal_group_member_tenant_isolation ON underwriting.proposal_group_member
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

-- NULLIF from the start, not added by a later sweep. Without it a tenant-less query RAISES
-- ("invalid input syntax for type uuid") instead of returning nothing, because current_setting
-- on a RESET GUC returns the empty string rather than NULL -- found in production, and swept
-- across all 91 existing policies on 2026-09-10.
GRANT SELECT, INSERT, UPDATE, DELETE ON underwriting.proposal_group_scheme TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON underwriting.proposal_group_grade TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON underwriting.proposal_group_member TO app_role;
```

- [ ] **Step 4: Add the migration to the 37 test classes that list underwriting migrations**

Pattern-only node script (shell string editing corrupts these files — CRLF endings defeat `\n` matching), anchored on V8:

```js
// scratchpad/add-uw-v9.js
const fs = require('fs');
const AFTER = 'db-migrations/underwriting/V8__rating_multiplier.sql';
const NEW = 'db-migrations/underwriting/V9__group_proposal.sql';
let changed = 0;
for (const f of process.argv.slice(2)) {
  const src = fs.readFileSync(f, 'utf8');
  const eol = src.includes('\r\n') ? '\r\n' : '\n';
  const lines = src.split(eol);
  const i = lines.findIndex((l) => l.includes(AFTER));
  if (i < 0) { console.log('NO ANCHOR: ' + f); continue; }
  if (lines.some((l) => l.includes(NEW))) { console.log('ALREADY: ' + f); continue; }
  const line = lines[i];
  const indent = line.match(/^\s*/)[0];
  // Preserve the list terminator: if the anchor was the last entry its line ends with the
  // closing paren, which has to move onto the new last entry instead.
  const tail = line.endsWith(',') ? '' : line.slice(line.lastIndexOf('"') + 1);
  const anchorLine = tail === '' ? line : line.slice(0, line.lastIndexOf('"') + 1) + ',';
  lines.splice(i, 1, anchorLine, indent + '"' + NEW + '"' + (tail === '' ? ',' : tail));
  fs.writeFileSync(f, lines.join(eol));
  changed++;
}
console.log('changed ' + changed + ' of ' + (process.argv.length - 2));
```

```bash
cd backend && node ../scratchpad/add-uw-v9.js $(grep -rl "db-migrations/underwriting/V8__rating_multiplier.sql" src/test/java)
grep -rl "V9__group_proposal" src/test/java | wc -l   # expect 37
```

- [ ] **Step 5: Add the API types**

`backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting/api/GroupBenefitBasis.java`:

```java
package tz.co.nlolo.lifeplatform.underwriting.api;

/**
 * How each member's benefit is arrived at, as proposed.
 *
 * <p>Same three values as {@code policy.api.BenefitBasis}, and deliberately a SEPARATE enum.
 * {@code underwriting} may not depend on {@code policy} — policy already depends on
 * underwriting and the cycle would be immediate — so the two are mapped at the boundary by
 * {@code policy.application.UnderwritingDecisionEventListener}, which is on the policy side and
 * is therefore the one place allowed to see both. Exactly the arrangement
 * {@code underwriting.api.BeneficiaryNomination} and {@code policy.api.BeneficiaryInput}
 * already have, and for the same reason.
 */
public enum GroupBenefitBasis { FLAT, SALARY_MULTIPLE, GRADED }
```

`backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting/api/GroupProposal.java`:

```java
package tz.co.nlolo.lifeplatform.underwriting.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A group scheme as asked for, before anybody has agreed to write it.
 *
 * <p>Everything {@code PolicyApi.issueGroupScheme} needs, held on the case so an underwriter
 * can see the whole proposal — terms, grade table and schedule — before a contract exists.
 * Issuance copies it; nothing here is authoritative once a scheme is issued, in exactly the way
 * {@code proposal_beneficiary} stops being authoritative once beneficiaries are on the policy.
 *
 * @param premiumAmount the premium AGREED with the employer. A scheme is not priced by the
 *     individual formula — that prices one life from one age band, and the age it would read is
 *     the employer's. Carried verbatim to issuance.
 * @param commencementDate when the employer wants cover to start. May be backdated; a schedule
 *     reaches the insurer weeks after the fact.
 */
public record GroupProposal(GroupBenefitBasis benefitBasis, BigDecimal flatBenefitAmount,
                             BigDecimal salaryMultiple, BigDecimal fclAmount, String currency,
                             List<GradeLine> grades, List<MemberLine> openingSchedule,
                             BigDecimal premiumAmount, String premiumCurrency, String premiumFrequency,
                             LocalDate commencementDate, Integer policyTermMonths) {

    /** One band on a GRADED proposal: a staff category and what it is worth. */
    public record GradeLine(String gradeCode, BigDecimal benefitAmount) {}

    /**
     * One life on the opening schedule.
     *
     * <p>No {@code joinedOn}: every life on an opening schedule joins when the scheme
     * commences, which {@link GroupProposal#commencementDate()} already says.
     */
    public record MemberLine(UUID memberPartyId, String gradeCode, BigDecimal salaryAmount) {}
}
```

- [ ] **Step 6: Persist it, and skip the engine for a group case**

Add the entities and repositories (three each, plain JPA following `ProposalBeneficiary`/`ProposalBeneficiaryRepository`), then in `UnderwritingApiImpl`:

```java
    @Override
    @Transactional
    public UnderwritingCaseView openCase(UUID applicantPartyId, UUID productId, UUID productVersionId,
                                          UUID agentOfRecordId, GroupProposal proposal, String openedBy) {
        UUID tenantId = TenantContext.get();
        partyApi.getParty(applicantPartyId);
        if (proposal == null || proposal.openingSchedule() == null || proposal.openingSchedule().isEmpty()) {
            // A scheme's sum assured IS the total of its schedule, so an empty one is a contract
            // insuring nobody for nothing. issueGroupScheme already refused it; refusing it here
            // means it never reaches an underwriter's queue.
            throw new UnderwritingValidationException("A group proposal names at least one life");
        }
        if (productApi.getSnapshotByVersionId(productVersionId).category() != ProductCategory.GROUP_LIFE) {
            throw new UnderwritingValidationException(
                "A group proposal needs a GROUP_LIFE product; this one is "
                    + productApi.getSnapshotByVersionId(productVersionId).category());
        }

        // SUM ASSURED IS NULL ON A GROUP CASE, and that is a decision rather than an omission.
        //
        // Valuing the schedule needs GroupBenefitCalculator -- flat, salary x multiple, or a
        // grade lookup, each rounded once -- and that lives in policy.domain, which underwriting
        // may not reach. Re-implementing it here would put benefit arithmetic in two modules and
        // let them drift, which is the one thing money arithmetic must never do. (The console's
        // groupBenefitPreview.ts mirrors it deliberately, but it is labelled a preview, it sends
        // nothing, and both sides run the same worked examples against each other. A second
        // SERVER-side copy has no such safety net.)
        //
        // So the case says what is being ASKED FOR -- the basis, the schedule, the count -- and
        // the sum assured appears when policy derives it at issuance, in the one place that
        // owns it. The queue shows the member count and the basis instead, which is what an
        // underwriter picking work off it actually sorts by.
        UnderwritingCase underwritingCase = new UnderwritingCase(tenantId, applicantPartyId, productId,
            productVersionId, null, proposal.currency(), agentOfRecordId, openedBy);
        underwritingCase.recordGroupProposal(nextProposalNumber(), proposal.commencementDate());
        underwritingCaseRepository.save(underwritingCase);
        persistGroupProposal(tenantId, underwritingCase.getCaseId(), proposal, openedBy);
        return toViewWithNominations(underwritingCase);
    }
```

**`recordGroupProposal` is new, and it exists because the individual one cannot be reused.** The existing `recordProposal(String proposalNumber, UUID lifeAssuredPartyId, ProposalDetails details)` takes a life assured that the service has **already resolved to non-null** — its javadoc says the service "turns a null (self-insured) into the applicant before calling this, so the column is always answerable rather than carrying a null that every reader has to interpret". On a scheme there is no single life to resolve to, and resolving to the employer would assert that the *company* is insured. That is the exact mistake `policy/V8` had to migrate 28 rows to undo.

Add to `UnderwritingCase`:

```java
    /**
     * A group scheme's proposal identity. No life assured, deliberately.
     *
     * <p>Separate from {@link #recordProposal} because that one takes an already-resolved,
     * non-null life assured — correct for individual business, where a null means "the
     * applicant insures themselves". A scheme has no single life to resolve to, and resolving
     * to the applicant would record that the EMPLOYER is insured. {@code policy/V8} migrated
     * 28 rows to undo precisely that assertion on the policy side; this is the same rule
     * applied before the mistake can be made.
     *
     * <p>So {@code life_assured_party_id} stays NULL on a group case, meaning "not a single
     * person" rather than "unknown" — the lives are the proposed schedule.
     */
    public void recordGroupProposal(String proposalNumber, LocalDate proposedCommencementDate) {
        this.proposalNumber = proposalNumber;
        this.proposedCommencementDate = proposedCommencementDate;
    }
```

and the writer, beside the existing `proposalBeneficiaryRepository.save(...)` loop it is modelled on:

```java
    /**
     * The proposal's three tables, written in one go. Mirrors how {@code openCase} already
     * persists {@code ProposalBeneficiary} rows: the case row first, then its children, all in
     * the caller's transaction.
     */
    private void persistGroupProposal(UUID tenantId, UUID caseId, GroupProposal proposal, String createdBy) {
        proposalGroupSchemeRepository.save(new ProposalGroupScheme(tenantId, caseId,
            proposal.benefitBasis().name(), proposal.flatBenefitAmount(), proposal.salaryMultiple(),
            proposal.fclAmount(), proposal.currency(),
            proposal.premiumAmount(), proposal.premiumCurrency(), proposal.premiumFrequency(),
            proposal.commencementDate(), proposal.policyTermMonths(), createdBy));
        for (GroupProposal.GradeLine grade : proposal.grades()) {
            proposalGroupGradeRepository.save(
                new ProposalGroupGrade(tenantId, caseId, grade.gradeCode(), grade.benefitAmount()));
        }
        for (GroupProposal.MemberLine line : proposal.openingSchedule()) {
            // Each life validated the same way the applicant is, and for the same reason: a
            // schedule naming somebody who does not exist in this tenant is unassessable, and
            // PartyApi.getParty already refuses cross-tenant reads.
            partyApi.getParty(line.memberPartyId());
            proposalGroupMemberRepository.save(new ProposalGroupMember(tenantId, caseId,
                line.memberPartyId(), line.gradeCode(), line.salaryAmount()));
        }
    }
```

and guard the engine (leave the rest of the method exactly as it is — this adds a guard clause at the top and changes nothing below it):

```java
    private void recommendFromEvidence(UnderwritingCase underwritingCase) {
        // NO ENGINE OPINION ON A GROUP SCHEME, and this is a decision rather than a gap.
        //
        // RiskProfile is age band x sum assured band plus assessment scores. Group underwriting
        // looks at scheme size, industry, claims experience and average age -- none of which
        // this platform holds -- and the age band it WOULD resolve is the employer's, which
        // insures nobody. Running the engine anyway would produce a confident number about the
        // wrong person.
        //
        // A case with no recommendation is already legal and already handled: decide()'s own
        // comment says "An ABSENT recommendation is not a disagreement", so an underwriter
        // decides a scheme without a senior being demanded for departing from advice that was
        // never given. That is the intended behaviour here, not a side effect.
        //
        // The rating multiplier is skipped for the same reason -- it is the product of the
        // applicant's age band and sum assured band, and neither means anything for a company.
        if (proposalGroupSchemeRepository.existsById(underwritingCase.getCaseId())) {
            return;
        }
        ... existing body unchanged ...
    }
```

- [ ] **Step 7: Expose it on the view**

`UnderwritingCaseView` gains two components after `premiumFrequency`:

```java
                                    // A scheme case, and what it asks for (V9).
                                    //
                                    // groupScheme IS serialized -- the queue must be able to tell
                                    // a 500-life scheme from an individual proposal in a list, and
                                    // sumAssuredAmount alone cannot say which it is looking at.
                                    // openapi-underwriting.yaml's schema grows to match, because
                                    // it declares additionalProperties: false.
                                    boolean groupScheme,
                                    // @JsonIgnore for the same reason beneficiaries below are: the
                                    // console reads the full proposal from the case detail endpoint,
                                    // and smuggling a 500-row schedule onto every row of a
                                    // twenty-case queue page is not a list response.
                                    @JsonIgnore GroupProposal groupProposal,
```

- [ ] **Step 8: Clean compile and run**

```
cd backend && rm -rf target/classes target/test-classes && ./mvnw -o test-compile -DskipTests \
  && ./mvnw -o test -Dtest='GroupProposalIntegrationTest,UnderwritingApiIntegrationTest,UnderwritingContractTest' -DfailIfNoTests=false
```

Expected: BUILD SUCCESS. Every existing `UnderwritingCaseView` construction site needs the two new components; the compiler lists them.

- [ ] **Step 9: Apply to the dev database and commit**

```bash
cd backend
docker exec -i infra-postgres-1 psql -U postgres -d lifeplatform -v ON_ERROR_STOP=1 < db-migrations/underwriting/V9__group_proposal.sql
git add -A backend/db-migrations/underwriting backend/src
git commit -m "feat(underwriting): a case can carry a proposed group scheme"
```

---

## Task 2: A scheme can be issued as an offer

Independent of Task 1 and worth its own gate: it changes what `issueGroupScheme` produces without changing who calls it.

**Files:**
- Modify: `policy/api/PolicyApi.java`, `policy/application/PolicyApiImpl.java`, `backend/api/openapi/openapi-policy.yaml`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/GroupSchemeIntegrationTest.java`

**Interfaces:**
- Consumes: `IssuanceBasis.startsCoverImmediately()` and `Policy.recordIssuedOn` / `activate()`, both already used by `issuePolicy`.
- Produces, for Task 3: `IssueGroupSchemeRequest` gains a trailing `IssuanceBasis issuanceBasis` component. Null means an ordinary offer — the scheme lands `PROPOSED` and `activateOnFirstPremium` starts cover.

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void aSchemeIsIssuedAsAnOfferAndGoesOnRiskOnTheFirstPremium() {
        // Reverses build5 §2.6 ("a scheme goes on risk at issuance"), deliberately: an employer
        // buys cover the same way an individual does, and the first premium is what accepts it.
        TenantContext.set(tenantId);
        GroupProduct product = groupProduct("GRP-OFFER-01");
        GroupSchemeView scheme = policyApi.issueGroupScheme(
            flatScheme(product, person("ABC Company"), new BigDecimal("5000000.00"), null,
                List.of(new PolicyApi.MemberInput(person("A Life"), null, null, null)), null),
            "staff1");

        assertThat(policyApi.getPolicy(scheme.policyNumber()).status()).isEqualTo(PolicyStatus.PROPOSED);

        policyApi.activateOnFirstPremium(scheme.policyNumber());

        assertThat(policyApi.getPolicy(scheme.policyNumber()).status()).isEqualTo(PolicyStatus.ACTIVE);
    }

    @Test
    void aSchemeMigratedFromAnotherInsurerIsOnRiskImmediately() {
        // The same exception individual business has: an issuance basis that already carries
        // cover skips the wait, because the contract is in force somewhere else already.
        TenantContext.set(tenantId);
        GroupProduct product = groupProduct("GRP-OFFER-MIGRATION");
        GroupSchemeView scheme = policyApi.issueGroupScheme(
            flatScheme(product, person("ABC Company"), new BigDecimal("5000000.00"), null,
                List.of(new PolicyApi.MemberInput(person("A Life"), null, null, null)),
                IssuanceBasis.MIGRATION),
            "staff1");

        assertThat(policyApi.getPolicy(scheme.policyNumber()).status()).isEqualTo(PolicyStatus.ACTIVE);
    }
```

`flatScheme` already exists in `GroupSchemeIntegrationTest` as
`flatScheme(GroupProduct product, UUID employer, BigDecimal flatBenefit, BigDecimal fcl, List<MemberInput> members)`,
building an `IssueGroupSchemeRequest` with a fixed 1,200,000 ANNUALLY premium commencing today.
Give it a trailing `IssuanceBasis` parameter and pass it into the request's new last component;
every existing call site then needs a trailing `null`, which is the ordinary-offer case. `person`
and `groupProduct` are that class's existing fixtures — do not write new ones.

- [ ] **Step 2: Run them and watch the first fail**

```
cd backend && ./mvnw -o test -Dtest='GroupSchemeIntegrationTest' -DfailIfNoTests=false
```

Expected: the offer test FAILS with `expected PROPOSED but was ACTIVE` — that is the current behaviour, stated.

- [ ] **Step 3: Make issuance conditional**

In `PolicyApiImpl.issueGroupScheme`, replace the unconditional `policy.activate()`:

```java
        policy.recordIssuedOn(today);
        // AN OFFER, not cover, unless the basis already carries cover. Identical to issuePolicy:
        // a scheme is a contract an employer accepts by paying for it, and the first cleared
        // premium is that acceptance.
        //
        // This REVERSES build5 §2.6 ("a scheme goes on risk at issuance, outside
        // offer-and-acceptance"). That was written before group business had a pipeline, when
        // POST /group-schemes was the only way a scheme could exist and waiting for a premium
        // would have meant nobody was ever covered. It now has one.
        //
        // activateOnFirstPremium needs no change at all: it activates any PROPOSED policy, and
        // billing raises the employer's schedule off PolicyIssued exactly as before.
        boolean startsCoverNow = request.issuanceBasis() != null
            && request.issuanceBasis().startsCoverImmediately();
        if (startsCoverNow) {
            policy.activate();
        }
```

and put the real status on the event rather than the hardcoded literal:

```java
        // Was hardcoded "ACTIVE" with the comment "a scheme goes on risk at issuance". Now the
        // policy's own status, because it is no longer always the same one -- and communication's
        // offerMade branches on exactly this key.
        payload.put("status", policy.getStatus());
```

- [ ] **Step 4: Run the group and policy suites**

```
cd backend && rm -rf target/classes target/test-classes && ./mvnw -o test-compile -DskipTests \
  && ./mvnw -o test -Dtest='GroupSchemeIntegrationTest,PolicyContractTest,PolicyApiIntegrationTest,PolicyClaimClosureTest' -DfailIfNoTests=false
```

Expected: BUILD SUCCESS. **Several existing tests will need `activateOnFirstPremium` added** — anything that issued a scheme and expected it usable immediately, including `ClaimSettlementEndToEndTest`'s group fixture and `PolicyClaimClosureTest`'s, because a claim cannot be registered against a PROPOSED policy. That is the change working, not breaking.

- [ ] **Step 5: Check what the employer is now told**

A scheme issued PROPOSED now publishes `PolicyIssued` with `status = PROPOSED`, which means `communication`'s `offerMade` fires and the employer gets the `OFFER_MADE` SMS — *"pay by DATE to start your cover"*. That is correct and is the point, but confirm it reads sensibly for a company rather than a person before shipping, and check `GroupSchemeNotificationTest` still passes if that plan has landed.

- [ ] **Step 6: Commit**

```bash
git add -A backend/src backend/api/openapi/openapi-policy.yaml
git commit -m "feat(policy): a group scheme is issued as an offer, on risk at the first premium"
```

---

## Task 3: A decided group case issues its scheme

**Files:**
- Modify: `policy/application/UnderwritingDecisionEventListener.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/GroupPipelineEndToEndTest.java` (new)

**Interfaces:**
- Consumes: `UnderwritingCaseView.groupScheme()` / `groupProposal()` from Task 1; `IssueGroupSchemeRequest` with `issuanceBasis` from Task 2.
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Write the failing test**

New `GroupPipelineEndToEndTest`, with the migration list of `GroupProposalIntegrationTest` plus policy V1–V13 and refdata V3/V5:

```java
    @Test
    void anAcceptedGroupCaseBecomesASchemeOfferThatGoesOnRiskOnTheFirstPremium() {
        TenantContext.set(tenantId);
        UUID employer = person("ABC Company");
        UUID first = person("Juma Employee");
        UUID second = person("Asha Employee");
        ProductFixture product = groupProduct("GRP-PIPE-01");

        UnderwritingCaseView opened = underwritingApi.openCase(employer, product.productId(),
            product.productVersionId(), null,
            new GroupProposal(GroupBenefitBasis.FLAT, new BigDecimal("5000000.00"), null, null, "TZS",
                List.of(),
                List.of(new GroupProposal.MemberLine(first, null, null),
                        new GroupProposal.MemberLine(second, null, null)),
                new BigDecimal("1200000.00"), "TZS", "ANNUALLY", LocalDate.now(), null),
            "underwriter1");

        underwritingApi.submitAssessment(opened.caseId(), AssessmentType.FINANCIAL,
            "Employer accounts reviewed", new BigDecimal("10"), "uw");

        // No engine opinion on a scheme, so no senior is demanded for "departing" from one.
        assertThat(underwritingApi.getCase(opened.caseId()).recommendationOutcome()).isNull();

        underwritingApi.decide(opened.caseId(),
            new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Scheme accepted"),
            "uw", false);

        String policyNumber = awaitSchemeFor(employer);
        GroupSchemeView scheme = policyApi.getGroupScheme(policyNumber);

        // Issued as an OFFER, with the proposal's own premium -- not a computed one.
        assertThat(policyApi.getPolicy(policyNumber).status()).isEqualTo(PolicyStatus.PROPOSED);
        assertThat(policyApi.getPolicy(policyNumber).premiumAmount())
            .as("a scheme's premium is agreed with the employer, never derived from an age band")
            .isEqualByComparingTo(new BigDecimal("1200000.00"));
        // And the schedule came across.
        assertThat(scheme.activeMemberCount()).isEqualTo(2);
        assertThat(scheme.totalCoveredAmount()).isEqualByComparingTo(new BigDecimal("10000000.00"));

        policyApi.activateOnFirstPremium(policyNumber);
        assertThat(policyApi.getPolicy(policyNumber).status()).isEqualTo(PolicyStatus.ACTIVE);
    }

    @Test
    void aDeclinedGroupCaseIssuesNothing() {
        TenantContext.set(tenantId);
        UUID employer = person("Rejected Company");
        ProductFixture product = groupProduct("GRP-PIPE-DECLINE");
        UnderwritingCaseView opened = underwritingApi.openCase(employer, product.productId(),
            product.productVersionId(), null,
            new GroupProposal(GroupBenefitBasis.FLAT, new BigDecimal("5000000.00"), null, null, "TZS",
                List.of(), List.of(new GroupProposal.MemberLine(person("A Life"), null, null)),
                new BigDecimal("1200000.00"), "TZS", "ANNUALLY", LocalDate.now(), null),
            "underwriter1");
        underwritingApi.submitAssessment(opened.caseId(), AssessmentType.FINANCIAL, "Poor experience",
            new BigDecimal("80"), "uw");

        underwritingApi.decide(opened.caseId(),
            new UnderwritingApi.DecisionInput(DecisionOutcome.DECLINED, null, "Claims experience"),
            "uw", false);

        TenantContext.set(tenantId);
        assertThat(policyApi.searchPolicies(employer, null, null, null, null, PageRequest.of(0, 10))
            .getContent()).isEmpty();
    }
```

Plus the two helpers these tests need, and the fixtures from Task 1's class (`person`, `groupProduct`, `publishProduct`, `ProductFixture`) copied across:

```java
    /**
     * The scheme the decision listener issued for this employer.
     *
     * <p>Polled rather than read once. The listener is AFTER_COMMIT on the calling thread, so
     * in practice the row is already there when decide() returns — but a test that depends on
     * that reads as though it were asserting the timing, and fails confusingly if the listener
     * is ever made asynchronous. Same shape as PolicyApiIntegrationTest.issueFromProposal.
     */
    private String awaitSchemeFor(UUID employerPartyId) throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            TenantContext.set(tenantId);
            List<PolicyView> found = policyApi
                .searchPolicies(employerPartyId, null, null, null, null, PageRequest.of(0, 10))
                .getContent();
            if (!found.isEmpty()) {
                return found.get(0).policyNumber();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("No scheme was issued for employer " + employerPartyId);
    }
```

Both tests in this class therefore declare `throws InterruptedException`.

- [ ] **Step 2: Run it and watch it fail**

Expected: `awaitSchemeFor` times out — the listener runs the individual path, which calls `issuePolicy` with the employer as the life assured and no schedule at all.

- [ ] **Step 3: Branch the listener**

In `UnderwritingDecisionEventListener`, immediately after `getCase`:

```java
                UnderwritingCaseView decidedCase = underwritingApi.getCase(caseId);
                if (decidedCase.groupScheme()) {
                    // A scheme, not a policy. Everything below this line prices ONE LIFE from an
                    // age band and a sum assured band, which is meaningless for a company: the
                    // age it would read is the employer's, and the sum assured is five hundred
                    // people's cover added together. A scheme's premium was agreed with the
                    // employer and is carried on the proposal.
                    issueSchemeFromProposal(decidedCase);
                    return;
                }
```

and add:

```java
    /**
     * Turn a decided group case into a scheme offer.
     *
     * <p>This method is the ONE place allowed to see both {@code underwriting.api} and
     * {@code policy.api}'s group types. They are deliberately two identical sets of records —
     * underwriting may not depend on policy, because policy already depends on underwriting —
     * so the mapping below is a boundary crossing, not duplication to be tidied away. The same
     * arrangement already exists for {@code BeneficiaryNomination} / {@code BeneficiaryInput}.
     */
    private void issueSchemeFromProposal(UnderwritingCaseView decidedCase) {
        GroupProposal proposal = decidedCase.groupProposal();
        policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            decidedCase.applicantPartyId(), decidedCase.productId(), decidedCase.productVersionId(),
            decidedCase.agentOfRecordId(),
            BenefitBasis.valueOf(proposal.benefitBasis().name()),
            proposal.flatBenefitAmount(), proposal.salaryMultiple(), proposal.fclAmount(),
            proposal.currency(),
            proposal.grades().stream()
                .map(g -> new PolicyApi.GradeInput(g.gradeCode(), g.benefitAmount())).toList(),
            proposal.openingSchedule().stream()
                // joinedOn null: every life on an opening schedule joins when the scheme
                // commences, which issueGroupScheme resolves from commencementDate.
                .map(m -> new PolicyApi.MemberInput(m.memberPartyId(), m.gradeCode(), m.salaryAmount(), null))
                .toList(),
            proposal.premiumAmount(), proposal.premiumCurrency(), proposal.premiumFrequency(),
            proposal.commencementDate(), proposal.policyTermMonths(),
            "Issued on underwriting decision " + decidedCase.caseId(),
            // Null basis: an ordinary offer. The employer accepts by paying, exactly as an
            // individual customer does.
            null),
            "system:underwriting-decision-listener");
    }
```

- [ ] **Step 4: Run it, then the module**

```
cd backend && rm -rf target/classes target/test-classes && ./mvnw -o test-compile -DskipTests \
  && ./mvnw -o test -Dtest='GroupPipelineEndToEndTest,GroupSchemeIntegrationTest,PolicyApiIntegrationTest' -DfailIfNoTests=false
```

- [ ] **Step 5: Prove the premium is not computed**

Temporarily change `proposal.premiumAmount()` to `BigDecimal.TEN` and confirm the assertion fails with `expected 1200000.00`. Restore. Without this the test would pass for an implementation that priced the scheme with the individual formula and coincidentally landed nearby.

- [ ] **Step 6: Commit**

```bash
git add -A backend/src
git commit -m "feat(policy): a decided group case issues its scheme as an offer"
```

---

## Task 4: The queue shows a scheme as a scheme

**Files:**
- Modify: `frontend/src/features/underwriting/UnderwritingQueuePage.tsx`, `UnderwritingCaseDetailPage.tsx`, `src/api/underwriting.ts`, `src/store/underwritingStore.ts`, `backend/api/openapi/openapi-underwriting.yaml`
- Test: `frontend/src/features/underwriting/UnderwritingQueuePage.test.tsx` (or the nearest existing test for that page)

**Interfaces:**
- Consumes: `groupScheme: boolean` on the case view (serialized), and the full `GroupProposal` on `GET /underwriting/cases/{caseId}`.

- [ ] **Step 1: Expose the proposal on the detail endpoint**

Add `groupScheme` (boolean, required) to `openapi-underwriting.yaml`'s `UnderwritingCaseView`, and a `groupProposal` object to the single-case response only — the schema declares `additionalProperties: false`, so both must be spelled out. Regenerate types: `cd frontend && npm run generate:api`.

- [ ] **Step 2: Write the failing test**

Follow whatever rendering harness the existing tests in `src/features/underwriting/` use — `DecisionPanel.test.tsx` is the nearest example of a component test in that folder, and the queue's own test file (create one if there is none) should mount the page the same way, with the store seeded rather than the network mocked.

```tsx
it('marks a group case in the queue so it is not read as one enormous individual proposal', () => {
  // A group case has a NULL sum assured (policy derives it at issuance), so a queue that
  // renders only applicant and amount shows a company name against a blank figure and gives
  // an underwriter nothing to judge the size of the work by.
  seedQueue([
    { caseId: 'c1', groupScheme: true, applicantPartyId: EMPLOYER, sumAssuredAmount: null },
    { caseId: 'c2', groupScheme: false, applicantPartyId: PERSON, sumAssuredAmount: '2000000.00' },
  ]);
  renderUnderwritingQueue();

  expect(screen.getByRole('row', { name: /Group scheme/ })).toBeInTheDocument();
  expect(screen.queryAllByRole('row', { name: /Group scheme/ })).toHaveLength(1);
});
```

`seedQueue` and `renderUnderwritingQueue` are this test file's own two helpers: the first pushes rows into `useUnderwritingStore`, the second renders `<UnderwritingQueuePage />` inside whatever router/provider wrapper the neighbouring tests already use. Copy that wrapper rather than inventing one.

- [ ] **Step 3: Render it**

Add a "Group scheme" badge to the queue row when `groupScheme` is true, and on the case detail page render the proposal — basis, free cover limit, premium, and the schedule with a count — instead of the life-assured block, which is null for a scheme.

- [ ] **Step 4: Run the console checks**

```
cd frontend && npx vitest run src/features/underwriting ; npm run typecheck ; npm run lint
```

- [ ] **Step 5: Commit**

```bash
git add -A frontend/src backend/api/openapi/openapi-underwriting.yaml
git commit -m "feat(console): show a group case as a scheme in the underwriting queue"
```

---

## Task 5: Setting up a scheme proposes it

**Files:**
- Modify: `frontend/src/features/policies/IssueGroupSchemePage.tsx`, `src/features/policies/groupSchemeIssueForm.ts` (+ `.test.ts`), `src/api/underwriting.ts`, `src/store/underwritingStore.ts`

- [ ] **Step 1: Repoint the form**

`IssueGroupSchemePage` posts to `POST /underwriting/cases` with a `groupProposal` instead of `POST /group-schemes`, and on success navigates to the new case, not to a scheme. Retitle: **"Propose a group scheme"**, with the subtitle *"An underwriter decides it, and cover starts when the employer's first premium clears."*

The running-total preview stays exactly as it is — it is still the sum of the schedule, and it is still labelled as derived.

- [ ] **Step 2: Keep the direct path, and say what it is for**

`POST /group-schemes` stays, `UNDERWRITER`-gated, as the manual/override route — the group counterpart of `POST /policies/manual-issue`. It already carries `reasonForManualIssue`, so it is already shaped as an exception. Add to its OpenAPI description: *"The exception route. Ordinary group business is proposed as an underwriting case and issued by the decision; this is for a scheme already in force elsewhere, or a correction, and the reason is recorded."*

- [ ] **Step 3: Run the console checks and commit**

```
cd frontend && npx vitest run ; npm run typecheck ; npm run lint
git add -A frontend/src && git commit -m "feat(console): propose a group scheme as an underwriting case"
```

---

## Task 6: End to end against the real stack

- [x] **Step 1: Apply migrations, rebuild, restart**

`underwriting/V9` must be applied to the dev database. Stop the backend (it holds the jar open), `./mvnw -o clean package -DskipTests`, restart with `.env` exported and `--communication.sms-gateway.live=true`.

- [x] **Step 2: Rewrite the group e2e as a pipeline journey**

`staff-group-schemes.spec.ts`'s first test currently creates a scheme directly. It becomes: propose the scheme → it appears in the underwriting queue marked as a scheme → assess it → decide ACCEPT as a plain underwriter (no senior, because there is no recommendation to depart from) → the scheme exists as an OFFER → the member schedule is right → the employer's premium activates it.

**DONE except the last step, and that one cannot be done from a browser.** There is no console action that accepts an offer — for group or individual business — so the journey stops at the scheme existing as an OFFER. Two assertions were removed rather than contrived:

- the joiner and the restated total (`addMember` requires the scheme IN FORCE), covered by `GroupSchemeIntegrationTest`;
- the final successful claim registration (the server refuses a claim on a scheme not in force), covered by `GroupClaimIntegrationTest` and `ClaimSettlementEndToEndTest`.

This is a pre-existing gap this task exposed rather than created: `e2e/policies.ts` already works around the same wall for individual policies by issuing on a MIGRATION basis "so the policy is in force on arrival". Recording a first premium from the console is worth its own piece of work; it is the one step of offer-and-acceptance with no screen.

```ts
    // The whole point of the change, in one assertion: a scheme is not on risk until the
    // employer has paid for it, exactly like an individual policy.
    await expect(page.getByText('Proposed', { exact: true })).toBeVisible();
```

- [x] **Step 3: Run the group and underwriting specs, then the whole suite**

```
cd frontend && npx playwright test staff-group-schemes.spec.ts staff-underwriting.spec.ts
cd frontend && npx playwright test
```

No concurrent Maven, and not across a machine sleep.

- [x] **Step 4: Run the full backend suite**

```
cd backend && rm -rf target/classes target/test-classes target/surefire-reports && ./mvnw -o test
```

Nothing else may touch `target/` while this runs.

- [x] **Step 5: Commit**

```bash
git add -A frontend/e2e && git commit -m "test(e2e): a group scheme is proposed, decided and accepted by payment"
```

---

## Deliberately not in this plan

- **A rules-engine opinion on a group case.** Confirmed: none. Scheme size, industry and claims experience are not on this platform, and inventing a group rating rule is actuarial content nobody has signed.
- **A per-member underwriting case above the free cover limit.** Confirmed: no. The consequence, stated so it is not rediscovered as a bug: the FCL is a hard cap on any member's cover, and `MemberUnderwritingStatus.ACCEPTED` is unreachable in practice.
- **`POST /policies/manual-issue`'s role gate.** Still bare `REALM_STAFF`, and the same bypass on the individual side. Out of scope here only because it is individual business; it should be closed next, and closing it is one annotation and one test.
- **Freeform group members**, and **fixed-headcount substitution** — both in `2026-09-10-group-scheme-substitution-and-notices.md`, which is parked on the employer-versus-family question. Nothing here conflicts with either.
- **Repricing on member movement.** A scheme's premium is agreed for the period and carried from the proposal; nothing in this plan re-rates it.
