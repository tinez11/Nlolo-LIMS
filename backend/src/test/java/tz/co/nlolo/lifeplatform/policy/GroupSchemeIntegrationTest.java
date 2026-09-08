package tz.co.nlolo.lifeplatform.policy;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.*;
import tz.co.nlolo.lifeplatform.policy.domain.PolicyMemberBenefit;
import tz.co.nlolo.lifeplatform.policy.infrastructure.PolicyMemberBenefitRepository;
import tz.co.nlolo.lifeplatform.product.api.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Group business, slice 1: one master policy carrying many insured lives.
 *
 * <p>The assertions this file exists for are the two that no unit test can make:
 * {@code GroupBenefitCalculatorTest} already proves the arithmetic against fixtures, so
 * what is left is whether the <b>stored contract total and the derived member total
 * agree</b> after every write, and whether the batched "benefit in force" projection
 * actually binds — a native query with quoted aliases is exactly the kind of thing that
 * compiles, passes review, and returns nulls.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class GroupSchemeIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void applyMigrations() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/party/V2__individual_person_record.sql",
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/product/V2__base_rate_table.sql",
            "db-migrations/product/V3__base_rate_structured_age.sql",
            "db-migrations/product/V4__rating_table_unique_band.sql",
            "db-migrations/product/V5__rating_table_age_bounds.sql",
            "db-migrations/product/V6__eligibility_bounds.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/underwriting/V2__agent_of_record.sql",
            "db-migrations/underwriting/V3__medical_disclosure_recorded_by.sql",
            "db-migrations/underwriting/V4__proposal_identity.sql",
            "db-migrations/underwriting/V5__explicit_decision.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
            "db-migrations/policy/V3__premium_fields.sql",
            "db-migrations/policy/V4__underwriting_case_id.sql",
            "db-migrations/policy/V5__beneficiary_party_index.sql",
            "db-migrations/policy/V6__policy_term.sql",
            "db-migrations/policy/V7__life_assured.sql",
            "db-migrations/policy/V8__group_policies_have_no_single_life_assured.sql",
            "db-migrations/policy/V9__group_scheme_and_members.sql",
            "db-migrations/audit/V1__create_audit_schema.sql");
    }

    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private PolicyMemberBenefitRepository benefitRepository;

    /** Keeps the generated phone numbers unique across every party this class registers. */
    private static final AtomicInteger PHONE_SEQ = new AtomicInteger(1000);

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    // ---------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------

    private record GroupProduct(UUID productId, UUID productVersionId) {}

    private GroupProduct groupProduct(String code) {
        ProductSummaryView product = productApi.createProduct(code, "Group Life " + code,
            ProductCategory.GROUP_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        return new GroupProduct(product.productId(), snapshot.productVersionId());
    }

    private UUID person(String name) {
        return partyApi.registerIndividual(name, LocalDate.of(1985, 6, 15),
            "+2557" + String.format("%08d", PHONE_SEQ.incrementAndGet()), null, "test-agent").partyId();
    }

    private PolicyApi.IssueGroupSchemeRequest flatScheme(GroupProduct product, UUID employer,
                                                          BigDecimal flatBenefit, BigDecimal fcl,
                                                          List<PolicyApi.MemberInput> members) {
        return new PolicyApi.IssueGroupSchemeRequest(employer, product.productId(), product.productVersionId(),
            null, BenefitBasis.FLAT, flatBenefit, null, fcl, "TZS", null, members,
            new BigDecimal("1200000.00"), "TZS", "ANNUALLY", LocalDate.now(), null, "group onboarding");
    }

    // ---------------------------------------------------------------------------------
    // Issuance
    // ---------------------------------------------------------------------------------

    @Test
    void aSchemeIsIssuedWithItsOpeningScheduleAndItsTotalIsTheSumOfItsMembers() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = groupProduct("GRP-FLAT-1");
        UUID employer = person("ABC Company");

        GroupSchemeView scheme = policyApi.issueGroupScheme(flatScheme(product, employer,
            new BigDecimal("5000000.00"), null,
            List.of(new PolicyApi.MemberInput(person("Employee One"), null, null, null),
                    new PolicyApi.MemberInput(person("Employee Two"), null, null, null),
                    new PolicyApi.MemberInput(person("Employee Three"), null, null, null))), "staff-1");

        assertThat(scheme.activeMemberCount()).isEqualTo(3);
        // Derived from the schedule, never supplied: 3 lives at 5,000,000 each.
        assertThat(scheme.totalCoveredAmount()).isEqualByComparingTo("15000000.00");
        assertThat(scheme.benefitBasis()).isEqualTo(BenefitBasis.FLAT);
        assertThat(scheme.membersRequiringEvidence()).isZero();

        PolicyView master = policyApi.getPolicy(scheme.policyNumber());
        assertThat(master.sumAssuredAmount()).isEqualByComparingTo(scheme.totalCoveredAmount());
        // Migration V8: the employer is the policyholder, and an employer is not a life.
        // The lives are the schedule.
        assertThat(master.lifeAssuredPartyId()).isNull();
        assertThat(master.policyholderPartyId()).isEqualTo(employer);
    }

    /**
     * The assertion this whole slice rests on.
     *
     * <p>The master policy's sum assured is stored (the policy list reads it, and a
     * reinsurance return reads it) while the real total is derived from the schedule. Two
     * numbers that must never disagree, so this walks a joiner through and checks them
     * against each other on both sides of the write.
     */
    @Test
    void theStoredContractTotalAndTheDerivedMemberTotalAgreeAfterAJoiner() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = groupProduct("GRP-FLAT-2");
        UUID employer = person("Joiner Co");

        GroupSchemeView atInception = policyApi.issueGroupScheme(flatScheme(product, employer,
            new BigDecimal("2000000.00"), null,
            List.of(new PolicyApi.MemberInput(person("Founding Member"), null, null, null))), "staff-1");
        assertThat(policyApi.getPolicy(atInception.policyNumber()).sumAssuredAmount())
            .isEqualByComparingTo(atInception.totalCoveredAmount());

        policyApi.addMember(atInception.policyNumber(),
            new PolicyApi.MemberInput(person("New Joiner"), null, null, null), "staff-1");

        GroupSchemeView afterJoin = policyApi.getGroupScheme(atInception.policyNumber());
        assertThat(afterJoin.activeMemberCount()).isEqualTo(2);
        assertThat(afterJoin.totalCoveredAmount()).isEqualByComparingTo("4000000.00");
        // Restated in the same transaction as the membership change, so the contract and
        // its schedule cannot disagree even for an instant.
        assertThat(policyApi.getPolicy(atInception.policyNumber()).sumAssuredAmount())
            .isEqualByComparingTo("4000000.00");
        // And the coverage row the coverage-status endpoint answers from moved with it,
        // rather than leaving two screens quoting different sums insured.
        assertThat(policyApi.getCoverageStatus(atInception.policyNumber(), LocalDate.now()))
            .isNotNull();
    }

    @Test
    void aSchemeCannotBeIssuedWithNobodyOnIt() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = groupProduct("GRP-EMPTY");
        UUID employer = person("Empty Co");

        assertThatThrownBy(() -> policyApi.issueGroupScheme(
            flatScheme(product, employer, new BigDecimal("1000000.00"), null, List.of()), "staff-1"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("at least one member");
    }

    @Test
    void aSchemeCannotBeHungOffAnIndividualProduct() {
        TenantContext.set(UUID.randomUUID());
        ProductSummaryView termLife = productApi.createProduct("GRP-WRONG-CAT", "Term Life",
            ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(termLife.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")), null, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(termLife.productId(), LocalDate.now());
        UUID employer = person("Wrong Category Co");

        assertThatThrownBy(() -> policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            employer, termLife.productId(), snapshot.productVersionId(), null,
            BenefitBasis.FLAT, new BigDecimal("1000000.00"), null, null, "TZS", null,
            List.of(new PolicyApi.MemberInput(person("Somebody"), null, null, null)),
            new BigDecimal("100000.00"), "TZS", "ANNUALLY", LocalDate.now(), null, null), "staff-1"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("GROUP_LIFE");
    }

    @Test
    void thesamePersonCannotAppearTwiceOnTheOpeningSchedule() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = groupProduct("GRP-DUP");
        UUID employer = person("Duplicate Co");
        UUID twice = person("Counted Twice");

        assertThatThrownBy(() -> policyApi.issueGroupScheme(flatScheme(product, employer,
            new BigDecimal("1000000.00"), null,
            List.of(new PolicyApi.MemberInput(twice, null, null, null),
                    new PolicyApi.MemberInput(twice, null, null, null))), "staff-1"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("twice");
    }

    // ---------------------------------------------------------------------------------
    // Free cover limit
    // ---------------------------------------------------------------------------------

    @Test
    void aMemberAboveTheFreeCoverLimitIsCoveredUpToItAndCounted() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = groupProduct("GRP-FCL");
        UUID employer = person("Salaried Co");

        // 4x salary, 100,000,000 free cover limit. The senior manager on 30,000,000 is
        // worth 120,000,000 and is therefore 20,000,000 over the limit.
        GroupSchemeView scheme = policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            employer, product.productId(), product.productVersionId(), null,
            BenefitBasis.SALARY_MULTIPLE, null, new BigDecimal("4"), new BigDecimal("100000000.00"), "TZS",
            null,
            List.of(new PolicyApi.MemberInput(person("Junior Clerk"), null, new BigDecimal("5000000.00"), null),
                    new PolicyApi.MemberInput(person("Senior Manager"), null, new BigDecimal("30000000.00"), null)),
            new BigDecimal("900000.00"), "TZS", "ANNUALLY", LocalDate.now(), null, null), "staff-1");

        assertThat(scheme.membersRequiringEvidence()).isEqualTo(1);
        // 20,000,000 (junior, fully covered) + 100,000,000 (manager, capped at the limit).
        // NOT 140,000,000: the scheme is not on risk for the excess until it is accepted.
        assertThat(scheme.totalCoveredAmount()).isEqualByComparingTo("120000000.00");

        List<PolicyMemberView> members = policyApi
            .listMembers(scheme.policyNumber(), MemberStatus.ACTIVE, null, PageRequest.of(0, 20, Sort.by("joinedOn")))
            .getContent();
        PolicyMemberView manager = members.stream()
            .filter(m -> m.underwritingStatus() == MemberUnderwritingStatus.EVIDENCE_REQUIRED)
            .findFirst().orElseThrow();
        assertThat(manager.benefitAmount()).isEqualByComparingTo("120000000.00");
        assertThat(manager.coveredAmount()).isEqualByComparingTo("100000000.00");
        // Both facts are kept. Neither is derivable from the other once a decision is
        // made, since ACCEPTED and DECLINED produce different cover from identical inputs.
        assertThat(manager.salaryAmount()).isEqualByComparingTo("30000000.00");
    }

    /**
     * A scheme with no free cover limit is not a scheme with a limit of zero.
     *
     * <p>Null means "every member is covered in full with no evidence", which is a real
     * design for small flat schemes. Reading it as zero would send all 500 employees to
     * underwriting.
     */
    @Test
    void aSchemeWithNoFreeCoverLimitCoversEverybodyInFull() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = groupProduct("GRP-NOFCL");
        UUID employer = person("No Limit Co");

        GroupSchemeView scheme = policyApi.issueGroupScheme(flatScheme(product, employer,
            new BigDecimal("900000000.00"), null,
            List.of(new PolicyApi.MemberInput(person("Very Well Insured"), null, null, null))), "staff-1");

        assertThat(scheme.fclAmount()).isNull();
        assertThat(scheme.membersRequiringEvidence()).isZero();
        assertThat(scheme.totalCoveredAmount()).isEqualByComparingTo("900000000.00");
    }

    // ---------------------------------------------------------------------------------
    // Basis and its inputs must agree
    // ---------------------------------------------------------------------------------

    @Test
    void aGradedSchemeRefusesAGradeItDoesNotHave() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = groupProduct("GRP-GRADED");
        UUID employer = person("Graded Co");

        PolicyApi.IssueGroupSchemeRequest request = new PolicyApi.IssueGroupSchemeRequest(
            employer, product.productId(), product.productVersionId(), null,
            BenefitBasis.GRADED, null, null, null, "TZS",
            List.of(new PolicyApi.GradeInput("MANAGEMENT", new BigDecimal("50000000.00")),
                    new PolicyApi.GradeInput("STAFF", new BigDecimal("10000000.00"))),
            List.of(new PolicyApi.MemberInput(person("Mystery Grade"), "DIRECTORS", null, null)),
            new BigDecimal("500000.00"), "TZS", "ANNUALLY", LocalDate.now(), null, null);

        assertThatThrownBy(() -> policyApi.issueGroupScheme(request, "staff-1"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("DIRECTORS")
            .hasMessageContaining("MANAGEMENT"); // the message lists what the scheme does have
    }

    @Test
    void aGradedSchemeValuesEachMemberFromItsGradeTable() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = groupProduct("GRP-GRADED-OK");
        UUID employer = person("Graded Working Co");

        GroupSchemeView scheme = policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            employer, product.productId(), product.productVersionId(), null,
            BenefitBasis.GRADED, null, null, null, "TZS",
            List.of(new PolicyApi.GradeInput("MANAGEMENT", new BigDecimal("50000000.00")),
                    new PolicyApi.GradeInput("STAFF", new BigDecimal("10000000.00"))),
            List.of(new PolicyApi.MemberInput(person("A Manager"), "MANAGEMENT", null, null),
                    new PolicyApi.MemberInput(person("A Staffer"), "STAFF", null, null),
                    new PolicyApi.MemberInput(person("Another Staffer"), "STAFF", null, null)),
            new BigDecimal("500000.00"), "TZS", "ANNUALLY", LocalDate.now(), null, null), "staff-1");

        assertThat(scheme.grades()).extracting(GroupSchemeGradeView::gradeCode)
            .containsExactly("MANAGEMENT", "STAFF");
        assertThat(scheme.totalCoveredAmount()).isEqualByComparingTo("70000000.00");
    }

    @Test
    void aFlatSchemeRefusesASalaryItWouldOnlyIgnore() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = groupProduct("GRP-FLAT-SALARY");
        UUID employer = person("Confused Co");

        // Uploading a salaried schedule to a flat scheme must fail rather than quietly
        // produce plausible, wrong numbers.
        assertThatThrownBy(() -> policyApi.issueGroupScheme(flatScheme(product, employer,
            new BigDecimal("1000000.00"), null,
            List.of(new PolicyApi.MemberInput(person("Has A Salary"), null, new BigDecimal("4000000.00"), null))),
            "staff-1"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("flat benefit");
    }

    @Test
    void aSalaryMultipleSchemeRefusesAMemberWithNoSalary() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = groupProduct("GRP-NOSALARY");
        UUID employer = person("Missing Salary Co");

        assertThatThrownBy(() -> policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            employer, product.productId(), product.productVersionId(), null,
            BenefitBasis.SALARY_MULTIPLE, null, new BigDecimal("3"), null, "TZS", null,
            List.of(new PolicyApi.MemberInput(person("Unpriced Person"), null, null, null)),
            new BigDecimal("100000.00"), "TZS", "ANNUALLY", LocalDate.now(), null, null), "staff-1"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("salary");
    }

    // ---------------------------------------------------------------------------------
    // Membership
    // ---------------------------------------------------------------------------------

    @Test
    void thesamePersonCannotJoinTheSameSchemeTwice() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = groupProduct("GRP-REJOIN");
        UUID employer = person("Rejoin Co");
        UUID alreadyOn = person("Already A Member");

        GroupSchemeView scheme = policyApi.issueGroupScheme(flatScheme(product, employer,
            new BigDecimal("1000000.00"), null,
            List.of(new PolicyApi.MemberInput(alreadyOn, null, null, null))), "staff-1");

        assertThatThrownBy(() -> policyApi.addMember(scheme.policyNumber(),
            new PolicyApi.MemberInput(alreadyOn, null, null, null), "staff-1"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("already an active member");
    }

    @Test
    void aMemberCannotBeAddedWithAFutureJoinDate() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = groupProduct("GRP-FUTURE");
        UUID employer = person("Future Co");

        GroupSchemeView scheme = policyApi.issueGroupScheme(flatScheme(product, employer,
            new BigDecimal("1000000.00"), null,
            List.of(new PolicyApi.MemberInput(person("Present Member"), null, null, null))), "staff-1");

        assertThatThrownBy(() -> policyApi.addMember(scheme.policyNumber(),
            new PolicyApi.MemberInput(person("Starts Next Month"), null, null, LocalDate.now().plusMonths(1)),
            "staff-1"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("future join date");
    }

    @Test
    void anIndividualPolicyReadAsASchemeSaysSoRatherThanSayingItDoesNotExist() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = groupProduct("GRP-NOT-A-SCHEME");
        UUID employer = person("Real Co");
        GroupSchemeView scheme = policyApi.issueGroupScheme(flatScheme(product, employer,
            new BigDecimal("1000000.00"), null,
            List.of(new PolicyApi.MemberInput(person("A Member"), null, null, null))), "staff-1");

        // The scheme itself reads back.
        assertThat(policyApi.getGroupScheme(scheme.policyNumber())).isNotNull();
        // A policy number from another tenant, or none at all, is not-found -- which is a
        // different answer from "this exists but is an individual policy".
        assertThatThrownBy(() -> policyApi.getGroupScheme("POL-NOTHERE"))
            .isInstanceOf(PolicyNotFoundException.class);
    }

    // ---------------------------------------------------------------------------------
    // Effective dating -- the load-bearing decision
    // ---------------------------------------------------------------------------------

    /**
     * A salary change writes a new row; the old one still answers for its own dates.
     *
     * <p>This is what makes a claim payable on the benefit in force at the date of event
     * rather than at whatever today's payroll says. Written through the repository because
     * the endorsement API that will create these rows is the next slice — the storage
     * contract is what needs proving now, before anything is built on top of it.
     */
    @Test
    void anOlderBenefitStillAnswersForItsOwnDateAfterARestatement() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        GroupProduct product = groupProduct("GRP-EFFDATE");
        UUID employer = person("Payrise Co");

        GroupSchemeView scheme = policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            employer, product.productId(), product.productVersionId(), null,
            BenefitBasis.SALARY_MULTIPLE, null, new BigDecimal("3"), null, "TZS", null,
            List.of(new PolicyApi.MemberInput(person("Promoted Person"), null, new BigDecimal("10000000.00"),
                LocalDate.now().minusYears(2))),
            new BigDecimal("100000.00"), "TZS", "ANNUALLY", LocalDate.now().minusYears(2), null, null), "staff-1");

        PolicyMemberView member = policyApi
            .listMembers(scheme.policyNumber(), MemberStatus.ACTIVE, null, PageRequest.of(0, 10, Sort.by("joinedOn")))
            .getContent().getFirst();
        assertThat(member.coveredAmount()).isEqualByComparingTo("30000000.00");

        LocalDate riseDate = LocalDate.now().minusYears(1);
        benefitRepository.save(new PolicyMemberBenefit(tenantId, member.policyMemberId(), riseDate,
            new BigDecimal("20000000.00"), new BigDecimal("60000000.00"), new BigDecimal("60000000.00"),
            "renewal"));

        // A death eighteen months ago is valued at the old benefit, not the new one.
        assertThat(benefitRepository.findInForce(member.policyMemberId(), tenantId,
                LocalDate.now().minusMonths(18), PageRequest.of(0, 1)).getFirst().getCoveredAmount())
            .isEqualByComparingTo("30000000.00");
        // A death today is valued at the new one.
        assertThat(benefitRepository.findInForce(member.policyMemberId(), tenantId,
                LocalDate.now(), PageRequest.of(0, 1)).getFirst().getCoveredAmount())
            .isEqualByComparingTo("60000000.00");

        // And the page projection resolves the same row the point lookup does, rather
        // than whichever the database happened to return first.
        PolicyMemberView today = policyApi
            .listMembers(scheme.policyNumber(), MemberStatus.ACTIVE, null, PageRequest.of(0, 10, Sort.by("joinedOn")))
            .getContent().getFirst();
        assertThat(today.coveredAmount()).isEqualByComparingTo("60000000.00");
        assertThat(today.benefitEffectiveFrom()).isEqualTo(riseDate);
    }

    /**
     * The batched projection binds.
     *
     * <p>A native query whose aliases Postgres folds to lowercase returns a row per member
     * with every field null, and every screen quietly shows a blank benefit column. Cheap
     * to assert, and invisible until somebody looks at the page.
     */
    @Test
    void everyMemberOnAPageCarriesItsOwnBenefit() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = groupProduct("GRP-PAGE");
        UUID employer = person("Paged Co");

        GroupSchemeView scheme = policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            employer, product.productId(), product.productVersionId(), null,
            BenefitBasis.SALARY_MULTIPLE, null, new BigDecimal("2"), null, "TZS", null,
            List.of(new PolicyApi.MemberInput(person("Paid One"), null, new BigDecimal("1000000.00"), null),
                    new PolicyApi.MemberInput(person("Paid Two"), null, new BigDecimal("2000000.00"), null),
                    new PolicyApi.MemberInput(person("Paid Three"), null, new BigDecimal("3000000.00"), null)),
            new BigDecimal("100000.00"), "TZS", "ANNUALLY", LocalDate.now(), null, null), "staff-1");

        List<PolicyMemberView> members = policyApi.listMembers(scheme.policyNumber(), MemberStatus.ACTIVE, null,
            // Total order: joinedOn alone ties for every row of a bulk schedule.
            PageRequest.of(0, 10, Sort.by("joinedOn").and(Sort.by("policyMemberId")))).getContent();

        assertThat(members).hasSize(3);
        assertThat(members).allSatisfy(m -> {
            assertThat(m.coveredAmount()).isNotNull();
            assertThat(m.benefitAmount()).isNotNull();
            assertThat(m.benefitEffectiveFrom()).isNotNull();
        });
        assertThat(members).extracting(PolicyMemberView::coveredAmount)
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactlyInAnyOrder(new BigDecimal("2000000.00"), new BigDecimal("4000000.00"),
                new BigDecimal("6000000.00"));
    }
}
