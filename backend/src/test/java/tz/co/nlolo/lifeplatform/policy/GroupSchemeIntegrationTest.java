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
import tz.co.nlolo.lifeplatform.underwriting.api.AssessmentType;
import tz.co.nlolo.lifeplatform.underwriting.api.DecisionOutcome;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseView;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingValidationException;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;

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
            "db-migrations/party/V4__registered_by_agent.sql",
            "db-migrations/party/V5__registered_by_name.sql",
            "db-migrations/party/V6__client_reference.sql",
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/product/V2__base_rate_table.sql",
            "db-migrations/product/V3__base_rate_structured_age.sql",
            "db-migrations/product/V4__rating_table_unique_band.sql",
            "db-migrations/product/V5__rating_table_age_bounds.sql",
            "db-migrations/product/V6__eligibility_bounds.sql",
            "db-migrations/product/V9__rating_table_sum_assured_bounds.sql",
            "db-migrations/product/V10__ifrs_measurement_model_on_version.sql",
            "db-migrations/product/V11__frequency_loading.sql",
            "db-migrations/product/V12__tira_filing.sql",
            "db-migrations/product/V13__benefit_calculation_method.sql",
            // Admits CREDIT_LIFE. Without it creditLifeProduct() fails on
            // product_definition_category_check, which is the honest error only because
            // createProduct stopped reporting every integrity violation as a duplicate code.
            "db-migrations/product/V14__credit_life_category.sql",
            "db-migrations/product/V15__exclusion_periods.sql",
            "db-migrations/product/V16__base_rate_term_bands.sql",
            "db-migrations/product/V17__cash_value.sql",
            "db-migrations/product/V18__payout_schedule.sql",
            "db-migrations/product/V19__accumulation_terms.sql",

            "db-migrations/product/V20__deposit_rate_grid.sql",
            "db-migrations/benefitpayout/V1__create_benefitpayout_schema.sql",
            "db-migrations/accumulation/V1__create_accumulation_schema.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/underwriting/V2__agent_of_record.sql",
            "db-migrations/underwriting/V3__medical_disclosure_recorded_by.sql",
            "db-migrations/underwriting/V4__proposal_identity.sql",
            "db-migrations/underwriting/V5__explicit_decision.sql",
            "db-migrations/underwriting/V6__proposal_terms_and_beneficiaries.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/underwriting/V8__rating_multiplier.sql",
            "db-migrations/underwriting/V9__group_proposal.sql",
            "db-migrations/underwriting/V10__issuance_failure.sql",
            "db-migrations/underwriting/V11__member_evidence_case.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
            "db-migrations/policy/V3__premium_fields.sql",
            "db-migrations/policy/V4__underwriting_case_id.sql",
            "db-migrations/policy/V5__beneficiary_party_index.sql",
            "db-migrations/policy/V6__policy_term.sql",
            "db-migrations/policy/V7__life_assured.sql",
            "db-migrations/policy/V10__one_policy_per_underwriting_case.sql",
            "db-migrations/policy/V11__not_taken_up_status.sql",
            "db-migrations/policy/V8__group_policies_have_no_single_life_assured.sql",
            "db-migrations/policy/V9__group_scheme_and_members.sql",
            "db-migrations/policy/V13__freeform_members.sql",
            "db-migrations/policy/V14__credit_life_scheme.sql",
            "db-migrations/policy/V15__enrolment_submission.sql",
            "db-migrations/policy/V16__insurer_issued_member_reference.sql",
            "db-migrations/policy/V18__scheme_premium_rate.sql",
            "db-migrations/policy/V19__enrolment_premium.sql",
            "db-migrations/policy/V20__member_exit_reason.sql",
            "db-migrations/policy/V22__member_promoted_party.sql",
            "db-migrations/policy/V23__member_open_death_claim.sql",
            "db-migrations/policy/V24__issuance_record.sql",
            "db-migrations/policy/V25__credit_life_premium_basis.sql",
            "db-migrations/policy/V26__enrolment_stated_premium.sql",
            "db-migrations/policy/V27__expired_status.sql",
            "db-migrations/policy/V28__policies_due_to_expire.sql",
            "db-migrations/policy/V29__paid_up.sql",
            "db-migrations/policy/V30__surrender.sql",
            "db-migrations/policy/V31__free_look_status.sql",
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql");
    }

    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private UnderwritingApi underwritingApi;
    @Autowired private PolicyMemberBenefitRepository benefitRepository;
    /** Only for proving V14's constraints bite: no API can write these columns until task 6. */
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

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
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        return new GroupProduct(product.productId(), snapshot.productVersionId());
    }

    private UUID person(String name) {
        return partyApi.registerIndividual(name, LocalDate.of(1985, 6, 15),
            "+2557" + String.format("%08d", PHONE_SEQ.incrementAndGet()), null, "test-agent").partyId();
    }

    /**
     * A flat scheme, issued as an OFFER.
     *
     * <p>MIGRATION, not null, and that is what keeps the rest of this class about what it is
     * about. A scheme is now issued PROPOSED and goes on risk when the employer's first
     * premium clears; every test below asserts something about members, totals or the free
     * cover limit, none of which needs the contract to be on risk — but several DO need it
     * (adding a member requires the scheme in force), and threading activateOnFirstPremium
     * through all of them would bury the subject under acceptance plumbing. The offer path
     * itself is asserted directly by the two tests that exist for it.
     */
    private PolicyApi.IssueGroupSchemeRequest flatScheme(GroupProduct product, UUID employer,
                                                          BigDecimal flatBenefit, BigDecimal fcl,
                                                          List<PolicyApi.MemberInput> members) {
        return flatScheme(product, employer, flatBenefit, fcl, members, IssuanceBasis.MIGRATION);
    }

    private PolicyApi.IssueGroupSchemeRequest flatScheme(GroupProduct product, UUID employer,
                                                          BigDecimal flatBenefit, BigDecimal fcl,
                                                          List<PolicyApi.MemberInput> members,
                                                          IssuanceBasis issuanceBasis) {
        return new PolicyApi.IssueGroupSchemeRequest(employer, product.productId(), product.productVersionId(),
            null, BenefitBasis.FLAT, flatBenefit, null, fcl, "TZS", null, members,
            new BigDecimal("1200000.00"), "TZS", "ANNUALLY", LocalDate.now(), null, "group onboarding",
            issuanceBasis);
    }

    @Test
    void aSchemeIsIssuedAsAnOfferAndGoesOnRiskOnTheFirstPremium() {
        // Reverses build5 §2.6 ("a scheme goes on risk at issuance"), deliberately: an employer
        // buys cover the same way an individual does, and the first premium is what accepts it.
        TenantContext.set(UUID.randomUUID());
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
        // The same exception individual business has: a basis that already carries cover skips
        // the wait, because the contract is in force somewhere else already.
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = groupProduct("GRP-OFFER-MIGRATION");
        GroupSchemeView scheme = policyApi.issueGroupScheme(
            flatScheme(product, person("ABC Company"), new BigDecimal("5000000.00"), null,
                List.of(new PolicyApi.MemberInput(person("A Life"), null, null, null)),
                IssuanceBasis.MIGRATION),
            "staff1");

        assertThat(policyApi.getPolicy(scheme.policyNumber()).status()).isEqualTo(PolicyStatus.ACTIVE);
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
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)), null, ANY_FILING, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(termLife.productId(), LocalDate.now());
        UUID employer = person("Wrong Category Co");

        assertThatThrownBy(() -> policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            employer, termLife.productId(), snapshot.productVersionId(), null,
            BenefitBasis.FLAT, new BigDecimal("1000000.00"), null, null, "TZS", null,
            List.of(new PolicyApi.MemberInput(person("Somebody"), null, null, null)),
            new BigDecimal("100000.00"), "TZS", "ANNUALLY", LocalDate.now(), null, null, IssuanceBasis.MIGRATION), "staff-1"))
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
            new BigDecimal("900000.00"), "TZS", "ANNUALLY", LocalDate.now(), null, null, IssuanceBasis.MIGRATION), "staff-1");

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
            new BigDecimal("500000.00"), "TZS", "ANNUALLY", LocalDate.now(), null, null, IssuanceBasis.MIGRATION);

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
            new BigDecimal("500000.00"), "TZS", "ANNUALLY", LocalDate.now(), null, null, IssuanceBasis.MIGRATION), "staff-1");

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
            new BigDecimal("100000.00"), "TZS", "ANNUALLY", LocalDate.now(), null, null, IssuanceBasis.MIGRATION), "staff-1"))
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

    // ---- Freeform members: a life may be a name rather than a registered party ----

    private PolicyApi.MemberInput freeform(String name) {
        return new PolicyApi.MemberInput(MemberType.FREEFORM, null, name,
            LocalDate.of(1990, 4, 5), null, null, null);
    }

    @Test
    void aFreeformMemberIsCoveredWithoutBeingRegisteredAsAParty() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = groupProduct("GRP-FREEFORM");
        UUID employer = person("Freeform Co");

        GroupSchemeView scheme = policyApi.issueGroupScheme(flatScheme(product, employer,
            new BigDecimal("1000000.00"), null,
            List.of(new PolicyApi.MemberInput(person("Registered One"), null, null, null))), "staff-1");

        PolicyMemberView member = policyApi.addMember(scheme.policyNumber(),
            freeform("Amina Hassan Mwinyi"), "staff-1");

        assertThat(member.memberType()).isEqualTo(MemberType.FREEFORM);
        assertThat(member.memberName()).isEqualTo("Amina Hassan Mwinyi");
        assertThat(member.memberPartyId()).isNull();
        // Valued and covered exactly like anyone else -- the scheme's basis does not care
        // whether the insurer holds a KYC file on the life it is insuring.
        assertThat(member.coveredAmount()).isEqualByComparingTo("1000000.00");
        assertThat(policyApi.getGroupScheme(scheme.policyNumber()).totalCoveredAmount())
            .isEqualByComparingTo("2000000.00");
    }

    @Test
    void anOpeningScheduleMayCarryFreeformMembers() {
        // The opening schedule had its OWN designation check, separate from addMember's,
        // and it rejected every freeform row -- so a scheme could gain a freeform member
        // only after issuance. Credit life enrols its whole first batch at issuance.
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = groupProduct("GRP-OPENFREE");

        GroupSchemeView scheme = policyApi.issueGroupScheme(flatScheme(product, person("Opening Co"),
            new BigDecimal("1000000.00"), null,
            List.of(freeform("Schedule One"), freeform("Schedule Two"),
                new PolicyApi.MemberInput(person("Registered Three"), null, null, null))), "staff-1");

        assertThat(scheme.activeMemberCount()).isEqualTo(3);
        assertThat(scheme.totalCoveredAmount()).isEqualByComparingTo("3000000.00");
        assertThat(policyApi.listMembers(scheme.policyNumber(), null, "Schedule",
            PageRequest.of(0, 10)).getTotalElements()).isEqualTo(2);
    }

    @Test
    void aPartyMemberStillCarriesItsPartyIdAndNoLooseName() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = groupProduct("GRP-STILLPARTY");
        UUID employer = person("Still Party Co");
        UUID registered = person("Registered Two");

        GroupSchemeView scheme = policyApi.issueGroupScheme(flatScheme(product, employer,
            new BigDecimal("1000000.00"), null,
            List.of(new PolicyApi.MemberInput(registered, null, null, null))), "staff-1");

        PolicyMemberView member = policyApi.listMembers(scheme.policyNumber(), null, null,
            PageRequest.of(0, 10)).getContent().get(0);

        assertThat(member.memberType()).isEqualTo(MemberType.PARTY);
        assertThat(member.memberPartyId()).isEqualTo(registered);
        assertThat(member.memberName()).isNull();
    }

    @Test
    void aMemberNamingBothAPartyAndALooseNameIsRefused() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = groupProduct("GRP-BOTH");
        UUID employer = person("Both Co");

        GroupSchemeView scheme = policyApi.issueGroupScheme(flatScheme(product, employer,
            new BigDecimal("1000000.00"), null,
            List.of(new PolicyApi.MemberInput(person("Opening"), null, null, null))), "staff-1");

        assertThatThrownBy(() -> policyApi.addMember(scheme.policyNumber(),
            new PolicyApi.MemberInput(MemberType.PARTY, person("Confused"), "Also A Name",
                LocalDate.of(1990, 4, 5), null, null, null), "staff-1"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("must not also carry a loose name");
    }

    @Test
    void aFreeformMemberWithNoNameIsRefused() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = groupProduct("GRP-NONAME");
        UUID employer = person("No Name Co");

        GroupSchemeView scheme = policyApi.issueGroupScheme(flatScheme(product, employer,
            new BigDecimal("1000000.00"), null,
            List.of(new PolicyApi.MemberInput(person("Opening"), null, null, null))), "staff-1");

        assertThatThrownBy(() -> policyApi.addMember(scheme.policyNumber(), freeform("   "), "staff-1"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("must have a name");
    }

    @Test
    void twoFreeformMembersMayShareAName() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = groupProduct("GRP-SAMENAME");
        UUID employer = person("Same Name Co");

        GroupSchemeView scheme = policyApi.issueGroupScheme(flatScheme(product, employer,
            new BigDecimal("1000000.00"), null,
            List.of(new PolicyApi.MemberInput(person("Opening"), null, null, null))), "staff-1");

        // A father and a son, or two dependants of the same household. A name is not an
        // identity, and refusing the second would leave a real life uninsured to enforce
        // a uniqueness the data cannot support.
        policyApi.addMember(scheme.policyNumber(), freeform("Juma Juma"), "staff-1");
        policyApi.addMember(scheme.policyNumber(), freeform("Juma Juma"), "staff-1");

        assertThat(policyApi.getGroupScheme(scheme.policyNumber()).activeMemberCount()).isEqualTo(3);
    }

    // ---- Task 5: a credit-life product may be issued as a scheme ----
    //
    // Every assertion below checks the MESSAGE, not just the exception type. Three of
    // these four cases already threw InvalidPolicyStateException before the guard
    // existed -- for entirely the wrong reason -- so asserting the type alone would pass
    // against code that does nothing.

    private GroupProduct creditLifeProduct(String code) {
        ProductSummaryView product = productApi.createProduct(code, "Credit Life " + code,
            ProductCategory.CREDIT_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        return new GroupProduct(product.productId(), snapshot.productVersionId());
    }

    private PolicyApi.IssueGroupSchemeRequest loanScheme(GroupProduct product, UUID lender,
                                                          BenefitBasis basis, BigDecimal flatBenefit,
                                                          InterestMethod interestMethod) {
        return loanSchemeRequest(product, lender, basis, flatBenefit, interestMethod,
            List.of(new PolicyApi.MemberInput(MemberType.FREEFORM, null, "Amina Hassan Mwinyi",
                LocalDate.of(1988, 3, 14), null, null, null)));
    }

    private PolicyApi.IssueGroupSchemeRequest loanSchemeWith(GroupProduct product, UUID lender,
                                                              InterestMethod interestMethod,
                                                              List<PolicyApi.MemberInput> borrowers) {
        return loanSchemeRequest(product, lender, BenefitBasis.AMORTISING_LOAN, null,
            interestMethod, borrowers);
    }

    private PolicyApi.IssueGroupSchemeRequest loanSchemeRequest(GroupProduct product, UUID lender,
                                                                 BenefitBasis basis, BigDecimal flatBenefit,
                                                                 InterestMethod interestMethod,
                                                                 List<PolicyApi.MemberInput> members) {
        return new PolicyApi.IssueGroupSchemeRequest(lender, product.productId(),
            product.productVersionId(), null, basis, flatBenefit, null,
            new BigDecimal("25000000.00"), "TZS", null, members,
            new BigDecimal("52000.00"), "TZS",
            // SINGLE on a loan basis, a cycle on anything else. A credit-life scheme is paid
            // once per accepted file; issuing one ANNUALLY -- which this fixture used to do --
            // had billing raise a year of invoices against the master policy.
            basis == BenefitBasis.AMORTISING_LOAN ? "SINGLE" : "ANNUALLY",
            // Commences BEFORE the loans it covers. A lender scheme is signed first and
            // then fed monthly files of loans disbursed under it; a loan paid out before
            // commencement belongs to whatever arrangement preceded this contract.
            LocalDate.of(2026, 6, 1), null,
            "credit life onboarding", IssuanceBasis.MIGRATION, interestMethod,
            basis == BenefitBasis.AMORTISING_LOAN ? new BigDecimal("0.5000") : null,
            basis == BenefitBasis.AMORTISING_LOAN ? CreditLifePremiumBasis.PER_ANNUM_ON_PRINCIPAL : null);
    }

    /** LOLC's real shape: 10,400,000 over 18 months, disbursed 2026-06-30, no rate given. */
    private static LoanTerms lolcLoan() {
        return new LoanTerms(new BigDecimal("10400000.00"), BigDecimal.ZERO, 18,
            RepaymentFrequency.MONTHLY, LocalDate.of(2026, 6, 30), LocalDate.of(2026, 7, 30));
    }

    /**
     * A borrower who is nobody else on the scheme.
     *
     * <p>Used wherever a scheme just needs an opening member: since the lender supplies
     * no identifier, two rows with the same name, birth date, disbursement date and
     * principal ARE the same loan as far as the platform can tell, so fixture members
     * have to differ in one of those.
     */
    private static PolicyApi.MemberInput openingBorrower() {
        return PolicyApi.MemberInput.borrower("Opening Borrower", LocalDate.of(1980, 1, 1), null,
            new LoanTerms(new BigDecimal("1000000.00"), BigDecimal.ZERO, 12,
                RepaymentFrequency.MONTHLY, LocalDate.of(2026, 6, 5), LocalDate.of(2026, 7, 5)));
    }

    private static PolicyApi.MemberInput borrower(String loanAccountNumber) {
        return PolicyApi.MemberInput.borrower("Amina Hassan Mwinyi", LocalDate.of(1988, 3, 14),
            loanAccountNumber, lolcLoan());
    }

    @Test
    void aCreditLifeSchemeIsIssuedWithItsBorrowers() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = creditLifeProduct("CL-HAPPY");

        GroupSchemeView scheme = policyApi.issueGroupScheme(loanSchemeWith(product,
            person("Lender Co"), InterestMethod.FLAT_RATE,
            List.of(borrower("LN-2026-00417"), borrower("LN-2026-00418"))), "staff-1");

        assertThat(scheme.benefitBasis()).isEqualTo(BenefitBasis.AMORTISING_LOAN);
        assertThat(scheme.activeMemberCount()).isEqualTo(2);
        // Each borrower is covered for their own principal at inception, so the scheme
        // total is the sum of the loans it insures -- not a flat amount per head.
        assertThat(scheme.totalCoveredAmount()).isEqualByComparingTo("20800000.00");
    }

    @Test
    void coverStartsOnTheDayTheLoanWasDisbursed() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = creditLifeProduct("CL-DISBURSED");
        GroupSchemeView scheme = policyApi.issueGroupScheme(loanSchemeWith(product,
            person("Disburse Co"), InterestMethod.FLAT_RATE,
            List.of(borrower("LN-2026-00417"))), "staff-1");

        PolicyMemberView member = policyApi.listMembers(scheme.policyNumber(), null, null,
            PageRequest.of(0, 10)).getContent().get(0);

        // Backdated to the disbursement date, not the day the schedule arrived. The gap
        // between the two is the window a lender argues about after a death.
        assertThat(member.joinedOn()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(member.loanAccountNumber()).isEqualTo("LN-2026-00417");
        assertThat(member.coveredAmount()).isEqualByComparingTo("10400000.00");
    }

    @Test
    void aJoinedOnThatContradictsTheDisbursementDateIsRefused() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = creditLifeProduct("CL-CONTRADICT");
        GroupSchemeView scheme = policyApi.issueGroupScheme(loanSchemeWith(product,
            person("Contradict Co"), InterestMethod.FLAT_RATE,
            List.of(openingBorrower())), "staff-1");

        PolicyApi.MemberInput contradictory = new PolicyApi.MemberInput(MemberType.FREEFORM, null,
            "Amina Hassan Mwinyi", LocalDate.of(1988, 3, 14), null, null,
            LocalDate.of(2026, 9, 1), "LN-2026-00417", lolcLoan());

        assertThatThrownBy(() -> policyApi.addMember(scheme.policyNumber(), contradictory, "staff-1"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("starts on the disbursement date");
    }

    @Test
    void aLoanDisbursedInTheFutureIsRefused() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = creditLifeProduct("CL-FUTURE");
        GroupSchemeView scheme = policyApi.issueGroupScheme(loanSchemeWith(product,
            person("Future Lender"), InterestMethod.FLAT_RATE,
            List.of(openingBorrower())), "staff-1");

        PolicyApi.MemberInput future = PolicyApi.MemberInput.borrower("Zainabu Ally",
            LocalDate.of(1987, 10, 30), "LN-2026-00428",
            new LoanTerms(new BigDecimal("9000000.00"), new BigDecimal("18.00"), 36,
                RepaymentFrequency.MONTHLY,
                LocalDate.now().plusDays(7), LocalDate.now().plusMonths(1).plusDays(7)));

        assertThatThrownBy(() -> policyApi.addMember(scheme.policyNumber(), future, "staff-1"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("is in the future");
    }

    @Test
    void theSameLoanCannotBeEnrolledTwiceWhileActive() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = creditLifeProduct("CL-DUPLOAN");
        GroupSchemeView scheme = policyApi.issueGroupScheme(loanSchemeWith(product,
            person("Dup Lender"), InterestMethod.FLAT_RATE,
            List.of(borrower("LN-2026-00417"))), "staff-1");

        // What makes a resubmitted enrolment file idempotent rather than doubling cover.
        // The lender supplies no identifier, so the LOAN is the identity: who, born when,
        // borrowed how much, on what day.
        assertThatThrownBy(() -> policyApi.addMember(scheme.policyNumber(),
            borrower("LN-2026-00417"), "staff-1"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("already has an active loan");
    }

    @Test
    void aGenuinelySecondLoanToTheSamePersonIsRefusedUntilTheyQuoteTheReference() {
        // The honest limit of a composite key. A second loan to one borrower on a
        // DIFFERENT day or for a different amount goes through; same day, same amount is
        // indistinguishable from a resubmission, so it is refused with the way out named.
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = creditLifeProduct("CL-SECONDLOAN");
        GroupSchemeView scheme = policyApi.issueGroupScheme(loanSchemeWith(product,
            person("Second Loan Lender"), InterestMethod.FLAT_RATE,
            List.of(borrower(null))), "staff-1");

        PolicyApi.MemberInput differentDay = PolicyApi.MemberInput.borrower(
            "Amina Hassan Mwinyi", LocalDate.of(1988, 3, 14), null,
            new LoanTerms(new BigDecimal("8500000.00"), BigDecimal.ZERO, 48,
                RepaymentFrequency.MONTHLY,
                LocalDate.of(2026, 7, 15), LocalDate.of(2026, 8, 15)));

        assertThatCode(() -> policyApi.addMember(scheme.policyNumber(), differentDay, "staff-1"))
            .doesNotThrowAnyException();

        assertThatThrownBy(() -> policyApi.addMember(scheme.policyNumber(), borrower(null), "staff-1"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("quote the existing member's reference");
    }

    @Test
    void aBorrowerNeedsNoLoanAccountNumberBecauseTheLenderHasNone() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = creditLifeProduct("CL-NOKEY");
        GroupSchemeView scheme = policyApi.issueGroupScheme(loanSchemeWith(product,
            person("Keyless Co"), InterestMethod.FLAT_RATE,
            List.of(borrower(null))), "staff-1");

        // Reversed by the client on 2026-09-22: neither lender holds a per-loan
        // identifier, so requiring one would have rejected every real file. The insurer
        // issues the reference instead, and the account number is kept only when a lender
        // does happen to send one.
        PolicyMemberView member = policyApi.addMember(scheme.policyNumber(),
            PolicyApi.MemberInput.borrower("Joseph Mkenda", LocalDate.of(1975, 11, 2), null,
                new LoanTerms(new BigDecimal("2400000.00"), BigDecimal.ZERO, 24,
                    RepaymentFrequency.MONTHLY,
                    LocalDate.of(2026, 7, 10), LocalDate.of(2026, 8, 10))), "staff-1");

        assertThat(member.loanAccountNumber()).isNull();
        assertThat(member.memberReference()).isNotNull().startsWith("CL-");
    }

    @Test
    void aBorrowerAboveTheFreeCoverLimitIsEnrolledCapped() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = creditLifeProduct("CL-FCL");
        GroupSchemeView scheme = policyApi.issueGroupScheme(loanSchemeWith(product,
            person("FCL Lender"), InterestMethod.FLAT_RATE,
            List.of(openingBorrower())), "staff-1");

        // 30m loan against the scheme's 25m free cover limit. Rejecting the row would
        // make the LARGEST exposures systematically the uninsured ones.
        PolicyMemberView big = policyApi.addMember(scheme.policyNumber(),
            PolicyApi.MemberInput.borrower("Peter Massawe", LocalDate.of(1980, 7, 19),
                "LN-2026-00424",
                new LoanTerms(new BigDecimal("30000000.00"), new BigDecimal("17.00"), 72,
                    RepaymentFrequency.MONTHLY,
                    LocalDate.of(2026, 8, 13), LocalDate.of(2026, 9, 13))), "staff-1");

        assertThat(big.benefitAmount()).isEqualByComparingTo("30000000.00");
        assertThat(big.coveredAmount()).isEqualByComparingTo("25000000.00");
        assertThat(big.underwritingStatus()).isEqualTo(MemberUnderwritingStatus.EVIDENCE_REQUIRED);
    }

    @Test
    void anAlreadyRegisteredClientMayBorrowToo() {
        // A lender's schedule will sometimes name somebody the insurer already holds a
        // party record for. Refusing that would force a duplicate identity, and it is
        // also the shape promotion produces for an above-FCL borrower.
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = creditLifeProduct("CL-PARTYLOAN");
        GroupSchemeView scheme = policyApi.issueGroupScheme(loanSchemeWith(product,
            person("Party Lender"), InterestMethod.FLAT_RATE,
            List.of(openingBorrower())), "staff-1");

        UUID known = person("Registered Borrower");
        PolicyMemberView member = policyApi.addMember(scheme.policyNumber(),
            new PolicyApi.MemberInput(MemberType.PARTY, known, null, null, null, null, null,
                "LN-2026-00999", lolcLoan()), "staff-1");

        assertThat(member.memberPartyId()).isEqualTo(known);
        assertThat(member.loanAccountNumber()).isEqualTo("LN-2026-00999");
        assertThat(member.coveredAmount()).isEqualByComparingTo("10400000.00");
    }

    @Test
    void anAboveFclBorrowerIsPromotedToAPartyAndReferredForEvidence() {
        // PolicyMember.referForEvidence(UUID) has existed since build 5 with ZERO callers:
        // a member over the limit had their cover capped and nothing was ever opened, so
        // the excess could not be granted even if the evidence arrived.
        //
        // Opening a case needs an identity -- underwriting_case.applicant_party_id is NOT
        // NULL and ProposalDetails carries a party id, not a name. So an above-FCL
        // borrower is promoted at enrolment. That is not a hole in the freeform rule:
        // freeform exists to keep the KYC queue clear of people nobody needs to identify,
        // and somebody borrowing over the free cover limit is precisely somebody you do.
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = creditLifeProduct("CL-REFER");
        GroupSchemeView scheme = policyApi.issueGroupScheme(loanSchemeWith(product,
            person("Refer Lender"), InterestMethod.FLAT_RATE,
            List.of(openingBorrower())), "staff-1");

        PolicyMemberView big = policyApi.addMember(scheme.policyNumber(),
            PolicyApi.MemberInput.borrower("Peter Massawe", LocalDate.of(1980, 7, 19),
                "LN-2026-00424",
                new LoanTerms(new BigDecimal("30000000.00"), new BigDecimal("17.00"), 72,
                    RepaymentFrequency.MONTHLY,
                    LocalDate.of(2026, 8, 13), LocalDate.of(2026, 9, 13))), "staff-1");

        assertThat(big.underwritingStatus()).isEqualTo(MemberUnderwritingStatus.EVIDENCE_REQUIRED);
        assertThat(big.underwritingCaseId())
            .as("an above-FCL member with no case is a referral nobody will ever action")
            .isNotNull();
        assertThat(big.memberType()).isEqualTo(MemberType.PARTY);
        assertThat(big.memberPartyId()).isNotNull();
        // Cover is still capped while the evidence is outstanding.
        assertThat(big.coveredAmount()).isEqualByComparingTo("25000000.00");
        assertThat(big.benefitAmount()).isEqualByComparingTo("30000000.00");
    }

    // ---- The evidence decision reaches the member -------------------------------------------
    //
    // PolicyMember.recordEvidenceDecision had no caller. An ACCEPTED evidence case fell through to
    // the single-life issuance path and issued the member a separate policy on the scheme's
    // product, while their own record stayed EVIDENCE_REQUIRED and the excess was never granted;
    // a DECLINED one recorded nothing at all.

    /** An above-FCL borrower on a fresh scheme, with their evidence case assessed and undecided. */
    private PolicyMemberView referredBorrower(String code) {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = creditLifeProduct(code);
        GroupSchemeView scheme = policyApi.issueGroupScheme(loanSchemeWith(product,
            person(code + " Lender"), InterestMethod.FLAT_RATE, List.of(openingBorrower())), "staff-1");
        PolicyMemberView big = policyApi.addMember(scheme.policyNumber(),
            PolicyApi.MemberInput.borrower("Peter Massawe", LocalDate.of(1980, 7, 19), "LN-2026-00424",
                new LoanTerms(new BigDecimal("30000000.00"), new BigDecimal("17.00"), 72,
                    RepaymentFrequency.MONTHLY, LocalDate.of(2026, 8, 13), LocalDate.of(2026, 9, 13))),
            "staff-1");
        underwritingApi.submitAssessment(big.underwritingCaseId(), AssessmentType.MEDICAL,
            "Specialist report", new BigDecimal("10"), "uw-assessor");
        return big;
    }

    private String schemeOf(PolicyMemberView member) {
        return underwritingApi.getCase(member.underwritingCaseId()).evidenceForPolicyNumber();
    }

    /**
     * The product's entry-age and term gates, on every route onto a scheme.
     *
     * <p>Only the lender's monthly file applied them. A borrower too old for the product, or a
     * loan longer than it covers, went on risk through the opening schedule or a single add.
     */
    @Test
    void theProductsEntryAgeAndTermGatesApplyOnEveryRouteOntoAScheme() {
        TenantContext.set(UUID.randomUUID());
        ProductSummaryView product = productApi.createProduct("CL-GATES", "Credit Life gates",
            ProductCategory.CREDIT_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "18-99", BigDecimal.ONE, 18, 99),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, null, new EligibilityBounds(18, 60, null, 24, null, null), ANY_FILING, "actuary");
        GroupProduct gated = new GroupProduct(product.productId(),
            productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId());

        // The opening schedule: born 1950, so 76 at disbursement against a maximum of 60.
        PolicyApi.MemberInput tooOld = PolicyApi.MemberInput.borrower("Too Old", LocalDate.of(1950, 1, 1), null,
            new LoanTerms(new BigDecimal("1000000.00"), BigDecimal.ZERO, 12,
                RepaymentFrequency.MONTHLY, LocalDate.of(2026, 6, 5), LocalDate.of(2026, 7, 5)));
        assertThatThrownBy(() -> policyApi.issueGroupScheme(loanSchemeWith(gated, person("Gated Lender"),
                InterestMethod.FLAT_RATE, List.of(tooOld)), "staff-1"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("entry ages up to 60");

        // One at a time: a 36-month loan against a 24-month maximum.
        GroupSchemeView scheme = policyApi.issueGroupScheme(loanSchemeWith(gated, person("Gated Lender 2"),
            InterestMethod.FLAT_RATE, List.of(openingBorrower())), "staff-1");
        assertThatThrownBy(() -> policyApi.addMember(scheme.policyNumber(),
                PolicyApi.MemberInput.borrower("Long Loan", LocalDate.of(1985, 1, 1), null,
                    new LoanTerms(new BigDecimal("1000000.00"), BigDecimal.ZERO, 36,
                        RepaymentFrequency.MONTHLY, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1))),
                "staff-1"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("longer than this product's maximum of 24");
    }

    @Test
    void anEvidenceCaseSaysWhoseEvidenceItIs() {
        PolicyMemberView big = referredBorrower("CL-EVID-0");
        UnderwritingCaseView evidence = underwritingApi.getCase(big.underwritingCaseId());
        assertThat(evidence.evidenceForPolicyNumber()).isNotNull();
        assertThat(evidence.evidenceForMemberId()).isEqualTo(big.policyMemberId());
    }

    @Test
    void acceptingTheEvidenceGrantsTheExcessAndIssuesNoPolicy() {
        PolicyMemberView big = referredBorrower("CL-EVID-1");
        String policyNumber = schemeOf(big);

        underwritingApi.decide(big.underwritingCaseId(),
            new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Evidence satisfactory"),
            "uw-decider", false);

        PolicyMemberView after = policyApi.getMember(policyNumber, big.policyMemberId());
        assertThat(after.underwritingStatus()).isEqualTo(MemberUnderwritingStatus.ACCEPTED);
        assertThat(after.coveredAmount()).as("covered for the full benefit from today")
            .isEqualByComparingTo("30000000.00");
        assertThat(policyApi.searchPolicies(big.memberPartyId(), null, null, null, null,
                PageRequest.of(0, 5)).getTotalElements())
            .as("the member is on the scheme; no policy of their own is issued")
            .isZero();
        assertThat(underwritingApi.getCase(big.underwritingCaseId()).issuanceFailureReason()).isNull();
    }

    @Test
    void decliningTheEvidenceKeepsCoverAtTheLimitAndRecordsIt() {
        PolicyMemberView big = referredBorrower("CL-EVID-2");
        String policyNumber = schemeOf(big);

        underwritingApi.decide(big.underwritingCaseId(),
            new UnderwritingApi.DecisionInput(DecisionOutcome.DECLINED, null, "Adverse history"),
            "uw-decider", true);

        PolicyMemberView after = policyApi.getMember(policyNumber, big.policyMemberId());
        assertThat(after.underwritingStatus()).isEqualTo(MemberUnderwritingStatus.DECLINED);
        assertThat(after.coveredAmount()).isEqualByComparingTo("25000000.00");
    }

    @Test
    void aLoadingIsRefusedOnAnEvidenceCase() {
        PolicyMemberView big = referredBorrower("CL-EVID-3");
        assertThatThrownBy(() -> underwritingApi.decide(big.underwritingCaseId(),
                new UnderwritingApi.DecisionInput(DecisionOutcome.LOADED, new BigDecimal("25"), "Rated"),
                "uw-decider", true))
            .isInstanceOf(UnderwritingValidationException.class)
            .hasMessageContaining("no premium of their own to load");
    }

    @Test
    void aWithinFclBorrowerOpensNoCaseAndStaysFreeform() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = creditLifeProduct("CL-NOREFER");
        GroupSchemeView scheme = policyApi.issueGroupScheme(loanSchemeWith(product,
            person("No Refer Lender"), InterestMethod.FLAT_RATE,
            List.of(openingBorrower())), "staff-1");

        PolicyMemberView ordinary = policyApi.addMember(scheme.policyNumber(),
            borrower("LN-2026-00417"), "staff-1");

        assertThat(ordinary.underwritingStatus()).isEqualTo(MemberUnderwritingStatus.WITHIN_FCL);
        assertThat(ordinary.underwritingCaseId()).isNull();
        // The other 399 stay off the KYC queue entirely, which is the whole point.
        assertThat(ordinary.memberType()).isEqualTo(MemberType.FREEFORM);
        assertThat(ordinary.memberPartyId()).isNull();
    }

    @Test
    void aLoanDisbursedBeforeTheSchemeCommencedIsRefusedOnTheOpeningSchedule() {
        // addMember always refused a pre-commencement join; the opening schedule never
        // did, because joinedOn used to default to commencement so the case could not
        // arise. A credit-life file carries real disbursement dates, and a loan paid out
        // before this contract existed is risk it never priced.
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = creditLifeProduct("CL-PRECOMMENCE");

        PolicyApi.MemberInput tooEarly = PolicyApi.MemberInput.borrower("Early Borrower",
            LocalDate.of(1988, 3, 14), "LN-TOO-EARLY",
            new LoanTerms(new BigDecimal("5000000.00"), BigDecimal.ZERO, 12,
                RepaymentFrequency.MONTHLY,
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 1)));

        assertThatThrownBy(() -> policyApi.issueGroupScheme(loanSchemeWith(product,
            person("Early Lender"), InterestMethod.FLAT_RATE, List.of(tooEarly)), "staff-1"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("before the scheme commenced")
            .hasMessageContaining("LN-TOO-EARLY");
    }

    @Test
    void aBorrowerClaimIsValuedAtTheirOwnLoanAndNotTheWholeBook() {
        // claimableCover keyed its scheme branch on "GROUP_LIFE".equals(category), so a
        // CREDIT_LIFE scheme fell through to the individual-policy path. Two failures,
        // both silent: a claim naming the borrower was REFUSED outright, and a claim
        // naming nobody was valued from the policy's own coverage row -- the total of
        // every loan on the scheme. One borrower dying would have paid out the book.
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = creditLifeProduct("CL-CLAIMCOVER");
        GroupSchemeView scheme = policyApi.issueGroupScheme(loanSchemeWith(product,
            person("Claim Lender"), InterestMethod.FLAT_RATE,
            List.of(borrower("LN-2026-00417"), borrower("LN-2026-00418"))), "staff-1");

        assertThat(scheme.totalCoveredAmount()).isEqualByComparingTo("20800000.00");

        UUID memberId = policyApi.listMembers(scheme.policyNumber(), null, null,
            PageRequest.of(0, 10)).getContent().get(0).policyMemberId();

        ClaimableCoverView cover = policyApi.claimableCover(scheme.policyNumber(), memberId,
            LocalDate.of(2026, 7, 15), BenefitType.DEATH.name());

        assertThat(cover.amount()).isEqualByComparingTo("10400000.00");
        assertThat(cover.policyMemberId()).isEqualTo(memberId);
    }

    @Test
    void theInsurerIssuesTheReferenceTheLenderWillQuoteBack() {
        // Client answer, 2026-09-22: the lender has no per-loan identifier to give us --
        // one policy number goes to the bank and sheets come back. So we mint one.
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = creditLifeProduct("CL-REFERENCE");
        GroupSchemeView scheme = policyApi.issueGroupScheme(loanSchemeWith(product,
            person("Reference Lender"), InterestMethod.FLAT_RATE,
            List.of(borrower(null))), "staff-1");

        PolicyMemberView member = policyApi.listMembers(scheme.policyNumber(), null, null,
            PageRequest.of(0, 10)).getContent().get(0);

        assertThat(member.memberReference())
            .as("a borrower with no reference is one the lender can never name again")
            .isNotNull()
            .startsWith("CL-")
            // Scheme-qualified, so a human reading it knows which contract it belongs to.
            .contains(scheme.policyNumber().substring(4));
    }

    @Test
    void everyBorrowerGetsADistinctReference() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = creditLifeProduct("CL-DISTINCT");
        GroupSchemeView scheme = policyApi.issueGroupScheme(loanSchemeWith(product,
            person("Distinct Lender"), InterestMethod.FLAT_RATE,
            List.of(borrower(null), borrower(null), borrower(null))), "staff-1");

        var references = policyApi.listMembers(scheme.policyNumber(), null, null,
                PageRequest.of(0, 10)).getContent().stream()
            .map(PolicyMemberView::memberReference)
            .toList();

        // Three borrowers who share a name and a loan shape still get three references:
        // the sequence, not anything about the row, is what makes them distinct.
        assertThat(references).hasSize(3).doesNotHaveDuplicates();
    }

    @Test
    void anOrdinaryGroupMemberGetsNoReference() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = groupProduct("GRP-NOREF");
        GroupSchemeView scheme = policyApi.issueGroupScheme(flatScheme(product, person("No Ref Co"),
            new BigDecimal("1000000.00"), null,
            List.of(new PolicyApi.MemberInput(person("Employee"), null, null, null))), "staff-1");

        assertThat(policyApi.listMembers(scheme.policyNumber(), null, null,
            PageRequest.of(0, 10)).getContent().get(0).memberReference()).isNull();
    }

    @Test
    void aBorrowerWithNoLoanAccountNumberIsStillCovered() {
        // THE REGRESSION THIS EXISTS FOR. PolicyMember.getLoanTerms() keyed on
        // loan_account_number, from back when that was the member key and was mandatory. V16
        // made it optional -- the insurer issues the reference now, precisely because neither
        // real lender has an account number to give -- and the guard was not moved with it.
        //
        // So every borrower enrolled from a real lender's file returned null loan terms, and
        // claimableCover threw IllegalStateException for all of them: credit-life cover could
        // not be valued at all on the only intake path that exists.
        //
        // It survived because of where the two test suites stop. Every other test here builds
        // its borrower by hand WITH an account number; EnrolmentIntegrationTest enrols from a
        // real file, with none, and never asks what anybody is covered for. The bug lived in
        // the gap between them, which is why this test passes null explicitly.
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = creditLifeProduct("CL-NOACCOUNTNO");
        GroupSchemeView scheme = policyApi.issueGroupScheme(loanSchemeWith(product,
            person("Referenceless Lender"), InterestMethod.FLAT_RATE,
            List.of(PolicyApi.MemberInput.borrower("Amina Hassan Mwinyi",
                LocalDate.of(1988, 3, 14), null,
                new LoanTerms(new BigDecimal("1000000.00"), BigDecimal.ZERO, 12,
                    RepaymentFrequency.MONTHLY,
                    LocalDate.of(2026, 6, 30), LocalDate.of(2026, 7, 30))))), "staff-1");

        UUID memberId = policyApi.listMembers(scheme.policyNumber(), null, null,
            PageRequest.of(0, 10)).getContent().get(0).policyMemberId();

        // Halfway through: six of twelve months repaid, so half the loan is still insured.
        assertThat(policyApi.claimableCover(scheme.policyNumber(), memberId,
            LocalDate.of(2026, 12, 30), BenefitType.DEATH.name()).amount())
            .isEqualByComparingTo("500000.00");
    }

    @Test
    void coverFallsInAStraightLineAsTheLoanIsRepaid() {
        // The client's own example, 2026-09-22: "1M, 12 months, 1000000/12 = 83,333.333,
        // so every month we reduce by that". Straight-line is what FLAT_RATE already
        // computes -- principal x (n-k)/n -- and it reads no interest rate, which is why
        // dropping that column from the template was safe.
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = creditLifeProduct("CL-STRAIGHTLINE");
        GroupSchemeView scheme = policyApi.issueGroupScheme(loanSchemeWith(product,
            person("Straight Lender"), InterestMethod.FLAT_RATE,
            List.of(PolicyApi.MemberInput.borrower("Amina Hassan Mwinyi",
                LocalDate.of(1988, 3, 14), "LN-STRAIGHT",
                new LoanTerms(new BigDecimal("1000000.00"), BigDecimal.ZERO, 12,
                    RepaymentFrequency.MONTHLY,
                    LocalDate.of(2026, 6, 30), LocalDate.of(2026, 7, 30))))), "staff-1");

        UUID memberId = policyApi.listMembers(scheme.policyNumber(), null, null,
            PageRequest.of(0, 10)).getContent().get(0).policyMemberId();

        // Day of disbursement: nothing repaid, the whole loan is insured.
        assertThat(policyApi.claimableCover(scheme.policyNumber(), memberId,
            LocalDate.of(2026, 6, 30), BenefitType.DEATH.name()).amount())
            .isEqualByComparingTo("1000000.00");

        // After one instalment: 1,000,000 x 11/12.
        assertThat(policyApi.claimableCover(scheme.policyNumber(), memberId,
            LocalDate.of(2026, 7, 30), BenefitType.DEATH.name()).amount())
            .isEqualByComparingTo("916666.67");

        // Six of twelve paid: exactly half. That is 2026-12-30, not 2027-01-30 -- the
        // first instalment falls due ON 2026-07-30, so six have been paid six months
        // later, not seven. Worth stating because the off-by-one is the whole difference
        // between paying a claim 500,000 and paying it 416,666.67.
        assertThat(policyApi.claimableCover(scheme.policyNumber(), memberId,
            LocalDate.of(2026, 12, 30), BenefitType.DEATH.name()).amount())
            .isEqualByComparingTo("500000.00");

        // And the month after, one instalment further down.
        assertThat(policyApi.claimableCover(scheme.policyNumber(), memberId,
            LocalDate.of(2027, 1, 30), BenefitType.DEATH.name()).amount())
            .isEqualByComparingTo("416666.67");

        // Fully repaid: nothing left to insure, and never a negative.
        assertThat(policyApi.claimableCover(scheme.policyNumber(), memberId,
            LocalDate.of(2027, 6, 30), BenefitType.DEATH.name()).amount())
            .isEqualByComparingTo("0.00");
    }

    @Test
    void anEmployerSchemesCoverDoesNotFall() {
        // The decline is credit life's alone. A flat employer scheme pays what it always
        // paid, whenever the death happened.
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = groupProduct("GRP-NODECLINE");
        GroupSchemeView scheme = policyApi.issueGroupScheme(flatScheme(product, person("Flat Co"),
            new BigDecimal("5000000.00"), null,
            List.of(new PolicyApi.MemberInput(person("Employee"), null, null, null))), "staff-1");

        UUID memberId = policyApi.listMembers(scheme.policyNumber(), null, null,
            PageRequest.of(0, 10)).getContent().get(0).policyMemberId();

        assertThat(policyApi.claimableCover(scheme.policyNumber(), memberId,
            LocalDate.now().plusYears(2), BenefitType.DEATH.name()).amount())
            .isEqualByComparingTo("5000000.00");
    }

    @Test
    void aCreditLifeClaimMustNameTheBorrower() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = creditLifeProduct("CL-NOMEMBER");
        GroupSchemeView scheme = policyApi.issueGroupScheme(loanSchemeWith(product,
            person("Nameless Lender"), InterestMethod.FLAT_RATE,
            List.of(borrower("LN-2026-00417"))), "staff-1");

        // Without naming the life, the only figure available is the scheme total.
        assertThatThrownBy(() -> policyApi.claimableCover(scheme.policyNumber(), null,
            LocalDate.of(2026, 7, 15), BenefitType.DEATH.name()))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("names a member");
    }

    @Test
    void anOrdinaryGroupMemberMayNotCarryALoan() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = groupProduct("GRP-STRAYLOAN");
        GroupSchemeView scheme = policyApi.issueGroupScheme(flatScheme(product, person("Stray Loan Co"),
            new BigDecimal("1000000.00"), null,
            List.of(new PolicyApi.MemberInput(person("Opening"), null, null, null))), "staff-1");

        assertThatThrownBy(() -> policyApi.addMember(scheme.policyNumber(),
            borrower("LN-STRAY"), "staff-1"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("only on a credit-life scheme");
    }

    @Test
    void anAmortisingLoanBasisNeedsACreditLifeProduct() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct employerProduct = groupProduct("GRP-WRONGBASIS");

        assertThatThrownBy(() -> policyApi.issueGroupScheme(
            loanScheme(employerProduct, person("Employer Co"), BenefitBasis.AMORTISING_LOAN, null,
                InterestMethod.FLAT_RATE), "staff-1"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("CREDIT_LIFE product");
    }

    @Test
    void aCreditLifeProductNeedsTheLoanBasis() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = creditLifeProduct("CL-WRONGBASIS");

        // A flat benefit on a lender scheme would insure every borrower for the same
        // amount regardless of what they borrowed.
        assertThatThrownBy(() -> policyApi.issueGroupScheme(
            loanScheme(product, person("Flat Lender"), BenefitBasis.FLAT,
                new BigDecimal("1000000.00"), null), "staff-1"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("AMORTISING_LOAN");
    }

    @Test
    void aCreditLifeSchemeMustStateHowItsLoansRepay() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = creditLifeProduct("CL-NOMETHOD");

        assertThatThrownBy(() -> policyApi.issueGroupScheme(
            loanScheme(product, person("Methodless Co"), BenefitBasis.AMORTISING_LOAN, null, null),
            "staff-1"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("repay principal");
    }

    @Test
    void anEmployerSchemeStillRefusesAnInterestMethod() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = groupProduct("GRP-STRAYMETHOD");

        assertThatThrownBy(() -> policyApi.issueGroupScheme(
            loanScheme(product, person("Stray Co"), BenefitBasis.FLAT,
                new BigDecimal("1000000.00"), InterestMethod.REDUCING_BALANCE), "staff-1"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("only on a credit-life scheme");
    }

    // ---- V14: the loan columns, and the constraints that keep them honest ----
    //
    // Written against JDBC rather than the API because nothing can populate these columns
    // until task 6 wires LoanTerms through MemberInput. A migration that merely applies
    // proves nothing about whether its constraints actually refuse anything, and these
    // three are the difference between a schedule the platform can trust and one it
    // cannot.

    /** Insert a bare freeform member row and return its id, for loading with loan columns. */
    private UUID insertFreeformMemberRow(UUID tenantId, String policyNumber) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            insert into policy.policy_member
                (policy_member_id, tenant_id, policy_number, member_type, member_name,
                 joined_on, status, underwriting_status)
            values (?, ?, ?, 'FREEFORM', 'Loan Borrower', current_date, 'ACTIVE', 'WITHIN_FCL')
            """, id, tenantId, policyNumber);
        return id;
    }

    @Test
    void aHalfFilledLoanIsRefusedByTheDatabase() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        GroupProduct product = groupProduct("GRP-LOANHALF");
        GroupSchemeView scheme = policyApi.issueGroupScheme(flatScheme(product, person("Half Co"),
            new BigDecimal("1000000.00"), null,
            List.of(new PolicyApi.MemberInput(person("Opening"), null, null, null))), "staff-1");
        UUID memberId = insertFreeformMemberRow(tenantId, scheme.policyNumber());

        // A principal with no term is a schedule the application would have to guess at.
        assertThatThrownBy(() -> jdbcTemplate.update(
            "update policy.policy_member set loan_account_number = ?, loan_principal_amount = ? "
                + "where policy_member_id = ?",
            "LN-HALF-1", new BigDecimal("8500000.00"), memberId))
            .hasMessageContaining("chk_policy_member_loan_complete");
    }

    @Test
    void aMemberReferenceIsNeverIssuedTwice() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        GroupProduct product = groupProduct("GRP-LOANDUP");
        GroupSchemeView scheme = policyApi.issueGroupScheme(flatScheme(product, person("Dup Co"),
            new BigDecimal("1000000.00"), null,
            List.of(new PolicyApi.MemberInput(person("Opening"), null, null, null))), "staff-1");

        loadLoan(insertFreeformMemberRow(tenantId, scheme.policyNumber()), "CL-TEST-000001");

        // The sequence cannot collide, so this guards against a reference minted by some
        // other route -- and it is what makes a reference safe for a lender to quote.
        assertThatThrownBy(() ->
            loadLoan(insertFreeformMemberRow(tenantId, scheme.policyNumber()), "CL-TEST-000001"))
            .hasMessageContaining("ux_policy_member_reference");
    }

    @Test
    void aMemberCarryingALoanMustCarryAReference() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        GroupProduct product = groupProduct("GRP-NOREFLOAN");
        GroupSchemeView scheme = policyApi.issueGroupScheme(flatScheme(product, person("No Ref Co"),
            new BigDecimal("1000000.00"), null,
            List.of(new PolicyApi.MemberInput(person("Opening"), null, null, null))), "staff-1");
        UUID memberId = insertFreeformMemberRow(tenantId, scheme.policyNumber());

        // A borrower the lender can never name again is the whole problem V16 exists for.
        assertThatThrownBy(() -> jdbcTemplate.update("""
            update policy.policy_member
               set loan_principal_amount = 8500000.00, loan_annual_rate_percent = 0,
                   loan_term_months = 48, loan_repayment_frequency = 'MONTHLY',
                   loan_disbursement_date = current_date,
                   loan_first_repayment_date = current_date + 30
             where policy_member_id = ?
            """, memberId))
            .hasMessageContaining("chk_policy_member_loan_has_reference");
    }

    private void loadLoan(UUID memberId, String memberReference) {
        jdbcTemplate.update("""
            update policy.policy_member
               set member_reference = ?, loan_principal_amount = ?,
                   loan_annual_rate_percent = ?, loan_term_months = ?,
                   loan_repayment_frequency = 'MONTHLY',
                   loan_disbursement_date = current_date,
                   loan_first_repayment_date = current_date + 30
             where policy_member_id = ?
            """, memberReference, new BigDecimal("8500000.00"), new BigDecimal("18.500"), 48, memberId);
    }

    @Test
    void aCreditLifeSchemeCarriesNoSchemeLevelAmountAndAnInterestMethod() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        GroupProduct product = groupProduct("GRP-LOANBASIS");
        GroupSchemeView flat = policyApi.issueGroupScheme(flatScheme(product, person("Basis Co"),
            new BigDecimal("1000000.00"), null,
            List.of(new PolicyApi.MemberInput(person("Opening"), null, null, null))), "staff-1");

        // The widened group_scheme_basis_parameter_present must still admit the new basis
        // with no scheme-level amount -- and the interest_method check must admit a real
        // value and refuse a fictional one.
        jdbcTemplate.update("update policy.group_scheme set interest_method = 'FLAT_RATE' "
            + "where policy_number = ?", flat.policyNumber());
        assertThat(jdbcTemplate.queryForObject(
            "select interest_method from policy.group_scheme where policy_number = ?",
            String.class, flat.policyNumber())).isEqualTo("FLAT_RATE");

        assertThatThrownBy(() -> jdbcTemplate.update(
            "update policy.group_scheme set interest_method = 'SIMPLE' where policy_number = ?",
            flat.policyNumber()))
            .hasMessageContaining("interest_method");
    }

    @Test
    void nameSearchFindsFreeformMembersAsWellAsRegisteredOnes() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = groupProduct("GRP-SEARCH");
        UUID employer = person("Search Co");

        GroupSchemeView scheme = policyApi.issueGroupScheme(flatScheme(product, employer,
            new BigDecimal("1000000.00"), null,
            List.of(new PolicyApi.MemberInput(person("Amina Registered"), null, null, null))), "staff-1");
        policyApi.addMember(scheme.policyNumber(), freeform("Amina Freeform"), "staff-1");
        policyApi.addMember(scheme.policyNumber(), freeform("Someone Else"), "staff-1");

        // Without the freeform half of the query a credit-life roll -- where EVERY member
        // is freeform -- would be unsearchable: 400 borrowers and no way to find one.
        assertThat(policyApi.listMembers(scheme.policyNumber(), null, "Amina",
            PageRequest.of(0, 20)).getTotalElements()).isEqualTo(2);
        assertThat(policyApi.listMembers(scheme.policyNumber(), null, "Freeform",
            PageRequest.of(0, 20)).getTotalElements()).isEqualTo(1);
        assertThat(policyApi.listMembers(scheme.policyNumber(), null, "Nobody",
            PageRequest.of(0, 20)).getTotalElements()).isZero();
        // No search still means the whole schedule.
        assertThat(policyApi.listMembers(scheme.policyNumber(), null, null,
            PageRequest.of(0, 20)).getTotalElements()).isEqualTo(3);
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
            new BigDecimal("100000.00"), "TZS", "ANNUALLY", LocalDate.now().minusYears(2), null, null, IssuanceBasis.MIGRATION), "staff-1");

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
            new BigDecimal("100000.00"), "TZS", "ANNUALLY", LocalDate.now(), null, null, IssuanceBasis.MIGRATION), "staff-1");

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
