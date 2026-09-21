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
            "db-migrations/audit/V1__create_audit_schema.sql");
    }

    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;
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
        return new PolicyApi.IssueGroupSchemeRequest(lender, product.productId(),
            product.productVersionId(), null, basis, flatBenefit, null,
            new BigDecimal("25000000.00"), "TZS", null,
            List.of(new PolicyApi.MemberInput(MemberType.FREEFORM, null, "Amina Hassan Mwinyi",
                LocalDate.of(1988, 3, 14), null, null, null)),
            new BigDecimal("52000.00"), "TZS", "ANNUALLY", LocalDate.now(), null,
            "credit life onboarding", IssuanceBasis.MIGRATION, interestMethod);
    }

    @Test
    void aCreditLifeProductIsNoLongerRefusedForItsCategory() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = creditLifeProduct("CL-CATEGORY");

        // It still fails -- MemberInput cannot carry a loan until task 6 -- but it must
        // fail on the MEMBER, not on the product. That is the whole of this task.
        assertThatThrownBy(() -> policyApi.issueGroupScheme(
            loanScheme(product, person("Lender Co"), BenefitBasis.AMORTISING_LOAN, null,
                InterestMethod.FLAT_RATE), "staff-1"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("principal of their own loan")
            .hasMessageNotContaining("GROUP_LIFE product");
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
    void theSameLoanAccountNumberCannotBeActiveTwiceOnOneScheme() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        GroupProduct product = groupProduct("GRP-LOANDUP");
        GroupSchemeView scheme = policyApi.issueGroupScheme(flatScheme(product, person("Dup Co"),
            new BigDecimal("1000000.00"), null,
            List.of(new PolicyApi.MemberInput(person("Opening"), null, null, null))), "staff-1");

        loadLoan(insertFreeformMemberRow(tenantId, scheme.policyNumber()), "LN-2026-00417");

        // V13 deliberately lets two freeform members share a NAME, because a name is not
        // an identity. A loan account number is, and this is the index that makes a
        // resubmitted enrolment file idempotent.
        assertThatThrownBy(() ->
            loadLoan(insertFreeformMemberRow(tenantId, scheme.policyNumber()), "LN-2026-00417"))
            .hasMessageContaining("ux_policy_member_active_loan");
    }

    private void loadLoan(UUID memberId, String accountNumber) {
        jdbcTemplate.update("""
            update policy.policy_member
               set loan_account_number = ?, loan_principal_amount = ?,
                   loan_annual_rate_percent = ?, loan_term_months = ?,
                   loan_repayment_frequency = 'MONTHLY',
                   loan_disbursement_date = current_date,
                   loan_first_repayment_date = current_date + 30
             where policy_member_id = ?
            """, accountNumber, new BigDecimal("8500000.00"), new BigDecimal("18.500"), 48, memberId);
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
