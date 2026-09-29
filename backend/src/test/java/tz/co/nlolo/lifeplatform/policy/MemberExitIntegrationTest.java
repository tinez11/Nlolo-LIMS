package tz.co.nlolo.lifeplatform.policy;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.policy.api.*;
import tz.co.nlolo.lifeplatform.product.api.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
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
 * A loan ends before its term, and the cover on it ends with it.
 *
 * <p>Until now the only way off a scheme was {@code dischargeForSettledClaim}: the insurer
 * paid, the life left. But most loans that end early end because they were settled,
 * refinanced, cancelled or written off, and without a way to record that, a repaid borrower
 * stays insured for a debt that no longer exists — and no refund or clawback can ever fire,
 * because nothing tells the rest of the platform anything happened.
 *
 * <p>The mechanics are deliberately the SAME ones the claim path uses. Exiting is one
 * operation with several reasons, not several operations.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class MemberExitIntegrationTest {

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
            "db-migrations/product/V14__credit_life_category.sql",
            "db-migrations/product/V15__exclusion_periods.sql",
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
            "db-migrations/policy/V17__enrolment_row_member_reference.sql",
            "db-migrations/policy/V18__scheme_premium_rate.sql",
            "db-migrations/policy/V19__enrolment_premium.sql",
            "db-migrations/policy/V20__member_exit_reason.sql",
            "db-migrations/policy/V22__member_promoted_party.sql",
            "db-migrations/policy/V23__member_open_death_claim.sql",
            "db-migrations/policy/V24__issuance_record.sql",
            "db-migrations/policy/V25__credit_life_premium_basis.sql",
            "db-migrations/policy/V26__enrolment_stated_premium.sql",
            "db-migrations/audit/V1__create_audit_schema.sql");
    }

    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;

    private static final AtomicInteger SEQ = new AtomicInteger(9000);

    /** 2,400,000 over 18 months, disbursed 2026-08-03. Cover declines straight-line. */
    private static final BigDecimal PRINCIPAL = new BigDecimal("2400000.00");
    private static final int TERM_MONTHS = 18;
    private static final LocalDate DISBURSED = LocalDate.of(2026, 8, 3);

    private String policyNumber;

    @BeforeEach
    void setTenant() {
        TenantContext.set(UUID.randomUUID());
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    // ---- a loan leaves ------------------------------------------------------

    @Test
    void aSettledLoanLeavesTheSchemeAndStopsBeingCovered() {
        PolicyMemberView member = schemeWithTwoBorrowers();

        policyApi.exitMember(policyNumber, member.policyMemberId(), LocalDate.of(2026, 11, 3),
            ExitReason.SETTLED_EARLY, BigDecimal.ZERO, "staff.one");

        // REFUSED, not zero. A zero would read as "insured for nothing", which is a different
        // and much worse statement than "not insured": it invites a claim to be registered and
        // valued at nil rather than turned away with a reason. The message names the window.
        assertThatThrownBy(() -> policyApi.claimableCover(policyNumber, member.policyMemberId(),
            LocalDate.of(2026, 11, 4), BenefitType.DEATH.name()))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("was not covered on 2026-11-04");
    }

    @Test
    void aClaimDatedBeforeTheExitIsStillCovered() {
        // The reason an exited member is never deleted. A death in September reported in
        // December, on a loan settled in November, is a covered claim -- and an implementation
        // that asks "is this member active" rather than "were they covered on the date of
        // event" refuses it.
        PolicyMemberView member = schemeWithTwoBorrowers();

        policyApi.exitMember(policyNumber, member.policyMemberId(), LocalDate.of(2026, 11, 3),
            ExitReason.SETTLED_EARLY, BigDecimal.ZERO, "staff.one");

        assertThat(policyApi.claimableCover(policyNumber, member.policyMemberId(),
            LocalDate.of(2026, 9, 3), BenefitType.DEATH.name()).amount()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void theExitedMemberKeepsItsReferenceAndRecordsWhyItLeft() {
        // The reference is how the lender named this loan and how they will ask about it
        // later; the reason is what tasks 5 and 6 branch on to decide a refund and a clawback.
        PolicyMemberView member = schemeWithTwoBorrowers();
        String reference = member.memberReference();

        PolicyMemberView exited = policyApi.exitMember(policyNumber, member.policyMemberId(),
            LocalDate.of(2026, 11, 3), ExitReason.REFINANCED, new BigDecimal("1200000.00"),
            "staff.one");

        assertThat(exited.status()).isEqualTo(MemberStatus.EXITED);
        assertThat(exited.memberReference()).isEqualTo(reference);
        assertThat(exited.exitReason()).isEqualTo(ExitReason.REFINANCED);
        assertThat(exited.leftOn()).isEqualTo(LocalDate.of(2026, 11, 3));
        // Recorded, not trusted: it is the lender's figure, and it reconciles against ours.
        assertThat(exited.outstandingBalanceAtExit()).isEqualByComparingTo("1200000.00");
    }

    @Test
    void theSchemeTotalFallsByExactlyTheDepartingMembersStoredCover() {
        // TWO different amounts are in play here, and conflating them is the easy mistake.
        //
        //   - the member's STORED covered amount: the original principal, capped at the free
        //     cover limit where that bit. This is what the scheme's sum assured is the total
        //     of, and what restating it adds up.
        //   - their CLAIMABLE cover on a date: the stored amount MIN the straight-line
        //     declining balance on that day. Always the smaller of the two after month one.
        //
        // The scheme total falls by the first, not the second. Worth stating plainly because
        // it means a credit-life scheme's stored total does NOT decline as its loans amortise
        // -- it is the sum of original principals, and it overstates live exposure by design.
        // See the note flagged alongside this task.
        PolicyMemberView leaver = schemeWithTwoBorrowers();
        BigDecimal before = policyApi.getGroupScheme(policyNumber).totalCoveredAmount();
        BigDecimal leaversStoredCover = leaver.coveredAmount();

        policyApi.exitMember(policyNumber, leaver.policyMemberId(), LocalDate.now(),
            ExitReason.SETTLED_EARLY, BigDecimal.ZERO, "staff.one");

        assertThat(policyApi.getGroupScheme(policyNumber).totalCoveredAmount())
            .isEqualByComparingTo(before.subtract(leaversStoredCover));
    }

    @Test
    void claimableCoverIsLessThanTheStoredCoverOnceTheLoanHasBeenRepaying() {
        // The distinction the test above turns on, asserted directly so it cannot be mistaken
        // for an accident of the fixture.
        PolicyMemberView member = schemeWithTwoBorrowers();

        BigDecimal claimable = policyApi.claimableCover(policyNumber, member.policyMemberId(),
            DISBURSED.plusMonths(6), BenefitType.DEATH.name()).amount();

        assertThat(claimable).isLessThan(member.coveredAmount());
        // Six of eighteen months elapsed leaves two thirds of 2,400,000 outstanding.
        assertThat(claimable).isEqualByComparingTo("1600000.00");
    }

    @Test
    void exitingAnAlreadyExitedMemberChangesNothing() {
        // The monthly exits file can legitimately repeat a row -- a lender resending a
        // corrected file, or an operator reprocessing one. A second exit that subtracted the
        // cover again would understate the scheme by a whole borrower.
        PolicyMemberView member = schemeWithTwoBorrowers();
        policyApi.exitMember(policyNumber, member.policyMemberId(), LocalDate.of(2026, 11, 3),
            ExitReason.SETTLED_EARLY, BigDecimal.ZERO, "staff.one");
        BigDecimal totalAfterFirst = policyApi.getGroupScheme(policyNumber).totalCoveredAmount();

        assertThatCode(() -> policyApi.exitMember(policyNumber, member.policyMemberId(),
            LocalDate.of(2026, 11, 3), ExitReason.SETTLED_EARLY, BigDecimal.ZERO, "staff.two"))
            .doesNotThrowAnyException();

        assertThat(policyApi.getGroupScheme(policyNumber).totalCoveredAmount())
            .isEqualByComparingTo(totalAfterFirst);
        // And the FIRST exit stands: a repeat must not overwrite why or when they left.
        assertThat(memberOf(policyNumber, member.policyMemberId()).leftOn())
            .isEqualTo(LocalDate.of(2026, 11, 3));
    }

    @Test
    void theLastLoanLeavingClosesTheScheme() {
        // Already true down the claim path; this asserts the generalised method kept it.
        // totalCovered sums only ACTIVE members and returns nothing with none, so a scheme
        // insuring nobody is one to close rather than to carry at nil.
        PolicyMemberView only = schemeWithOneBorrower();

        policyApi.exitMember(policyNumber, only.policyMemberId(), LocalDate.of(2026, 11, 3),
            ExitReason.SETTLED_EARLY, BigDecimal.ZERO, "staff.one");

        assertThat(policyApi.getPolicy(policyNumber).status()).isEqualTo(PolicyStatus.SURRENDERED);
    }

    // ---- what is refused ----------------------------------------------------

    @Test
    void anExitDatedBeforeTheLoanWasDisbursedIsRefused() {
        PolicyMemberView member = schemeWithTwoBorrowers();

        assertThatThrownBy(() -> policyApi.exitMember(policyNumber, member.policyMemberId(),
            DISBURSED.minusDays(1), ExitReason.SETTLED_EARLY, BigDecimal.ZERO, "staff.one"))
            .isInstanceOf(InvalidPolicyStateException.class);
    }

    @Test
    void anExitMustSayWhyTheLoanEnded() {
        // A refund and a clawback both turn on the reason, and "the loan was written off" and
        // "the borrower died" must not be treated the same way. An exit with no reason is one
        // nobody downstream can act on.
        PolicyMemberView member = schemeWithTwoBorrowers();

        assertThatThrownBy(() -> policyApi.exitMember(policyNumber, member.policyMemberId(),
            LocalDate.of(2026, 11, 3), null, BigDecimal.ZERO, "staff.one"))
            .isInstanceOf(InvalidPolicyStateException.class);
    }

    @Test
    void aMemberOfAnotherSchemeCannotBeExitedThroughThisOne() {
        // Two lenders, one tenant. The cross-scheme leak test.
        PolicyMemberView mine = schemeWithTwoBorrowers();
        String otherScheme = policyNumber;
        schemeWithTwoBorrowers();

        assertThatThrownBy(() -> policyApi.exitMember(policyNumber, mine.policyMemberId(),
            LocalDate.of(2026, 11, 3), ExitReason.SETTLED_EARLY, BigDecimal.ZERO, "staff.one"))
            .isInstanceOf(InvalidPolicyStateException.class);

        // And the member is untouched on their own scheme.
        assertThat(memberOf(otherScheme, mine.policyMemberId()).status())
            .isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    void anEmployerSchemesMemberCanStillLeaveForAnOrdinaryReason() {
        // exitMember is not credit-life-only. An employee leaving the employer is the same
        // operation, and refusing it here would be a gate nobody asked for.
        String employerScheme = issueEmployerScheme();
        List<PolicyMemberView> members = policyApi.listMembers(employerScheme, null, null,
            PageRequest.of(0, 10)).getContent();

        assertThatCode(() -> policyApi.exitMember(employerScheme,
            members.get(0).policyMemberId(), LocalDate.now(), ExitReason.CANCELLED, null,
            "staff.one")).doesNotThrowAnyException();
    }

    // ---- fixtures -----------------------------------------------------------

    private record GroupProduct(UUID productId, UUID productVersionId) {}

    /** Returns the FIRST borrower; a second exists so the scheme survives the first leaving. */
    private PolicyMemberView schemeWithTwoBorrowers() {
        policyNumber = issueCreditLifeScheme(List.of(
            borrower("Amina Hassan Mwinyi", LocalDate.of(1988, 3, 14)),
            borrower("Joseph Mkenda", LocalDate.of(1975, 11, 2))));
        return firstMemberOf(policyNumber);
    }

    private PolicyMemberView schemeWithOneBorrower() {
        policyNumber = issueCreditLifeScheme(List.of(
            borrower("Amina Hassan Mwinyi", LocalDate.of(1988, 3, 14))));
        return firstMemberOf(policyNumber);
    }

    private PolicyMemberView firstMemberOf(String scheme) {
        return policyApi.listMembers(scheme, null, null, PageRequest.of(0, 10))
            .getContent().get(0);
    }

    private PolicyMemberView memberOf(String scheme, UUID policyMemberId) {
        return policyApi.listMembers(scheme, null, null, PageRequest.of(0, 50)).getContent()
            .stream().filter(m -> m.policyMemberId().equals(policyMemberId)).findFirst()
            .orElseThrow();
    }

    private PolicyApi.MemberInput borrower(String name, LocalDate dateOfBirth) {
        return PolicyApi.MemberInput.borrower(name, dateOfBirth, null,
            new LoanTerms(PRINCIPAL, BigDecimal.ZERO, TERM_MONTHS, RepaymentFrequency.MONTHLY,
                DISBURSED, DISBURSED.plusMonths(1)));
    }

    private String issueCreditLifeScheme(List<PolicyApi.MemberInput> borrowers) {
        GroupProduct product = publish(ProductCategory.CREDIT_LIFE, "CL-EXIT-" + SEQ.incrementAndGet());
        return policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            person("Lender Co"), product.productId(), product.productVersionId(), null,
            BenefitBasis.AMORTISING_LOAN, null, null, new BigDecimal("600000000.00"), "TZS",
            null, borrowers, new BigDecimal("52000.00"), "TZS", "SINGLE",
            LocalDate.of(2026, 6, 1), null, "credit life onboarding", IssuanceBasis.MIGRATION,
            InterestMethod.FLAT_RATE, RepaymentFrequency.MONTHLY, new BigDecimal("0.5000"), CreditLifePremiumBasis.PER_ANNUM_ON_PRINCIPAL),
            "staff-1").policyNumber();
    }

    private String issueEmployerScheme() {
        GroupProduct product = publish(ProductCategory.GROUP_LIFE, "GRP-EXIT-" + SEQ.incrementAndGet());
        return policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            person("Employer Co"), product.productId(), product.productVersionId(), null,
            BenefitBasis.FLAT, new BigDecimal("1000000.00"), null, null, "TZS", null,
            List.of(new PolicyApi.MemberInput(person("Employee A"), null, null, null),
                    new PolicyApi.MemberInput(person("Employee B"), null, null, null)),
            new BigDecimal("1200000.00"), "TZS", "ANNUALLY", LocalDate.now(), null,
            "group onboarding", IssuanceBasis.MIGRATION), "staff-1").policyNumber();
    }

    private GroupProduct publish(ProductCategory category, String code) {
        ProductSummaryView product = productApi.createProduct(code, category + " " + code,
            category, "TZS", "actuary");
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
            "+2557" + String.format("%08d", SEQ.incrementAndGet()), null, "test-agent").partyId();
    }
}
