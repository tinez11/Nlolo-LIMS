package tz.co.nlolo.lifeplatform.claims;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.claims.api.*;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;

/**
 * A borrower dies and the insurer pays the lender what the borrower still owed.
 *
 * <p>The thing that makes a credit-life claim different from every other claim on this
 * platform: <b>the claimant is the bank.</b> The payout extinguishes a debt, so the money is
 * owed to whoever holds it — which is also the policyholder. Claimant and payee are the same
 * entity, which is exactly why this product needs no payee-redirection concept (spec §2.9).
 *
 * <p>The failure that makes it worth asserting: paying a borrower's family for a debt the
 * family does not hold, while the lender's loan stays unpaid and the insurer's books say the
 * claim is settled.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class CreditLifeClaimEndToEndTest {

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
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
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
            "db-migrations/product/V14__credit_life_category.sql",
            "db-migrations/product/V15__exclusion_periods.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/underwriting/V2__agent_of_record.sql",
            "db-migrations/underwriting/V3__medical_disclosure_recorded_by.sql",
            "db-migrations/underwriting/V4__proposal_identity.sql",
            "db-migrations/underwriting/V5__explicit_decision.sql",
            "db-migrations/underwriting/V6__proposal_terms_and_beneficiaries.sql",
            "db-migrations/underwriting/V8__rating_multiplier.sql",
            "db-migrations/underwriting/V9__group_proposal.sql",
            "db-migrations/underwriting/V10__issuance_failure.sql",
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
            "db-migrations/claims/V1__create_claims_schema.sql",
            "db-migrations/claims/V2__grants_rls_money_checks_evidence_and_settlement_columns.sql",
            "db-migrations/claims/V3__registration_idempotency_key.sql",
            "db-migrations/claims/V5__claim_policy_member.sql",
            "db-migrations/claims/V6__exclusion_decline.sql");
    }

    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private ClaimsApi claimsApi;

    private static final AtomicInteger SEQ = new AtomicInteger(4000);

    /** 2,400,000 over 18 months, disbursed 2026-08-03. Cover declines straight-line. */
    private static final BigDecimal PRINCIPAL = new BigDecimal("2400000.00");
    private static final int TERM_MONTHS = 18;
    private static final LocalDate DISBURSED = LocalDate.of(2026, 8, 3);

    private UUID bankPartyId;
    private String scheme;
    private UUID borrowerMemberId;

    @BeforeEach
    void seedScheme() {
        TenantContext.set(UUID.randomUUID());
        bankPartyId = person("Lender Co");
        scheme = issueCreditLifeScheme(bankPartyId);
        borrowerMemberId = policyApi.listMembers(scheme, null, null, PageRequest.of(0, 10))
            .getContent().get(0).policyMemberId();
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    // ---- the claimant is the bank -------------------------------------------

    @Test
    void aCreditLifeClaimIsRegisteredWithTheBankAsClaimant() {
        ClaimView claim = claimsApi.registerClaim(
            deathRequest(borrowerMemberId, bankPartyId), idem(), "claims.clerk");

        assertThat(claim.claimantPartyId()).isEqualTo(bankPartyId);
    }

    @Test
    void aCreditLifeClaimNamingAnyoneButTheLenderIsRefused() {
        // The money extinguishes a debt the family does not hold. Paying them would leave the
        // loan outstanding while the insurer's books say the claim is settled -- and nothing
        // downstream would ever notice.
        UUID theFamily = person("Next Of Kin");

        assertThatThrownBy(() -> claimsApi.registerClaim(
            deathRequest(borrowerMemberId, theFamily), idem(), "claims.clerk"))
            .isInstanceOf(ClaimValidationException.class)
            .hasMessageContaining("policyholder");
    }

    @Test
    void anEmployerSchemeClaimStillNamesWhoeverTheSchemeSays() {
        // The rule is CREDIT_LIFE only. A group-life death benefit is owed to the member's
        // own beneficiary, not to the employer, and narrowing that would be a serious
        // regression on a product that already works.
        String employerScheme = issueEmployerScheme();
        UUID employeeMemberId = policyApi.listMembers(employerScheme, null, null,
            PageRequest.of(0, 10)).getContent().get(0).policyMemberId();
        UUID beneficiary = person("Employee Next Of Kin");

        ClaimView claim = claimsApi.registerClaim(new ClaimsApi.RegisterClaimRequest(
            employerScheme, employeeMemberId, beneficiary, ClaimType.DEATH,
            LocalDate.now().minusDays(1), deathDetails()), idem(), "claims.clerk");

        assertThat(claim.claimantPartyId()).isEqualTo(beneficiary);
    }

    // ---- the exclusion windows ----------------------------------------------

    @Test
    void anExclusionDeclineIsRefusedOnceTheWindowHasClosed() {
        // THE ERROR THIS PREVENTS: declining a fourteen-month-old claim for suicide on a
        // twelve-month exclusion. It is simply wrong, it costs the lender the whole loan, and
        // it produces a plausible-looking declined claim that nothing downstream would
        // question. The dates do not care how expert the assessor is.
        UUID claimId = assessedClaimAt(DISBURSED.plusMonths(14));

        assertThatThrownBy(() -> claimsApi.decideSettlement(claimId, false, null, null,
            "assessor believes suicide", ClaimDeclineReason.SUICIDE_WITHIN_EXCLUSION,
            null, null, "assessor.two"))
            .isInstanceOf(ClaimValidationException.class)
            .hasMessageContaining("was not open on");
    }

    @Test
    void anExclusionDeclineInsideTheWindowIsAllowedAndRecordsWhichWindow() {
        // Six months into a twelve-month window. The assessor made the finding; the platform
        // records which window they invoked and the dates it was measured from, so a dispute
        // years later is settled from the row rather than from memory.
        UUID claimId = assessedClaimAt(DISBURSED.plusMonths(6));

        claimsApi.decideSettlement(claimId, false, null, null, "assessor believes suicide",
            ClaimDeclineReason.SUICIDE_WITHIN_EXCLUSION, null, null, "assessor.two");

        ClaimView declined = claimsApi.getClaim(claimId);
        assertThat(declined.status()).isEqualTo(ClaimStatus.REJECTED);
    }

    @Test
    void theWindowIsMeasuredFromDISBURSEMENTAndNotFromWhenTheFileArrived() {
        // Cover started at disbursement (2026-08-03); the scheme itself commenced 2026-06-01
        // and the enrolment file could have arrived any time after. Measuring from anything
        // but the borrower's own start date would move every boundary.
        //
        // An event 13 months after DISBURSEMENT is outside a 12-month window. If the window
        // were measured from scheme commencement (two months earlier) it would be outside by
        // even more; if from a later file date, it might still be inside. Asserting the refusal
        // here pins that the earlier, correct date is the one in use.
        UUID claimId = assessedClaimAt(DISBURSED.plusMonths(13));

        assertThatThrownBy(() -> claimsApi.decideSettlement(claimId, false, null, null,
            "assessor believes suicide", ClaimDeclineReason.SUICIDE_WITHIN_EXCLUSION,
            null, null, "assessor.two"))
            .isInstanceOf(ClaimValidationException.class)
            .hasMessageContaining(DISBURSED.toString());
    }

    @Test
    void anOrdinaryDeclineNeedsNoExclusionAndIsUnaffected() {
        // Fraud, non-disclosure, an event outside cover -- every existing decline path keeps
        // working with no exclusion reason at all.
        UUID claimId = assessedClaimAt(DISBURSED.plusMonths(6));

        claimsApi.decideSettlement(claimId, false, null, null, "documents were forged",
            null, null, null, "assessor.two");

        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.REJECTED);
    }

    @Test
    void anExclusionCannotBeRecordedOnAnApprovedClaim() {
        // A contradiction the settlement record could not render, and
        // chk_claim_decline_reason_only_when_rejected refuses the row anyway.
        UUID claimId = assessedClaimAt(DISBURSED.plusMonths(6));

        assertThatThrownBy(() -> claimsApi.decideSettlement(claimId, true,
            new BigDecimal("1000000.00"), "TZS", null,
            ClaimDeclineReason.SUICIDE_WITHIN_EXCLUSION, "bank-account-1", idem(), "assessor.two"))
            .isInstanceOf(ClaimValidationException.class)
            .hasMessageContaining("APPROVED");
    }

    // ---- fixtures -----------------------------------------------------------

    /** A claim on the borrower, dated {@code dateOfEvent}, already under assessment. */
    private UUID assessedClaimAt(LocalDate dateOfEvent) {
        ClaimView claim = claimsApi.registerClaim(new ClaimsApi.RegisterClaimRequest(
            scheme, borrowerMemberId, bankPartyId, ClaimType.DEATH, dateOfEvent,
            new DeathClaimDetails("Under investigation", "Dar es Salaam", dateOfEvent, "Dr Mwakalinga")),
            idem(), "claims.clerk");
        claimsApi.submitAssessment(claim.claimId(), "investigating", null, null, false, "assessor.one");
        return claim.claimId();
    }

    private String idem() { return "idem-" + UUID.randomUUID(); }

    private ClaimsApi.RegisterClaimRequest deathRequest(UUID policyMemberId, UUID claimantPartyId) {
        return new ClaimsApi.RegisterClaimRequest(scheme, policyMemberId, claimantPartyId,
            ClaimType.DEATH, DISBURSED.plusMonths(6), deathDetails());
    }

    private ClaimDetails deathDetails() {
        return new DeathClaimDetails("Natural causes", "Dar es Salaam",
            DISBURSED.plusMonths(6), "Dr Mwakalinga");
    }

    private record GroupProduct(UUID productId, UUID productVersionId) {}

    private String issueCreditLifeScheme(UUID lender) {
        GroupProduct product = publish(ProductCategory.CREDIT_LIFE, "CL-CLAIM-" + SEQ.incrementAndGet());
        return policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            lender, product.productId(), product.productVersionId(), null,
            BenefitBasis.AMORTISING_LOAN, null, null, new BigDecimal("600000000.00"), "TZS",
            null, List.of(PolicyApi.MemberInput.borrower("Amina Hassan Mwinyi",
                LocalDate.of(1988, 3, 14), null,
                new LoanTerms(PRINCIPAL, BigDecimal.ZERO, TERM_MONTHS, RepaymentFrequency.MONTHLY,
                    DISBURSED, DISBURSED.plusMonths(1)))),
            new BigDecimal("52000.00"), "TZS", "SINGLE",
            LocalDate.of(2026, 6, 1), null, "credit life onboarding", IssuanceBasis.MIGRATION,
            InterestMethod.FLAT_RATE, RepaymentFrequency.MONTHLY, new BigDecimal("0.5000")),
            "staff-1").policyNumber();
    }

    private String issueEmployerScheme() {
        GroupProduct product = publish(ProductCategory.GROUP_LIFE, "GRP-CLAIM-" + SEQ.incrementAndGet());
        return policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            person("Employer Co"), product.productId(), product.productVersionId(), null,
            BenefitBasis.FLAT, new BigDecimal("5000000.00"), null, null, "TZS", null,
            List.of(new PolicyApi.MemberInput(person("Employee A"), null, null, null)),
            new BigDecimal("1200000.00"), "TZS", "ANNUALLY", LocalDate.now().minusMonths(2), null,
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
        if (category == ProductCategory.CREDIT_LIFE) {
            // Client answer 3.4: twelve months each, and NO general waiting period. These two
            // windows are the entire anti-selection control the product has, because nobody
            // below the free cover limit is underwritten.
            productApi.setExclusionPeriods(snapshot.productVersionId(), 12, 12, "actuary");
        }
        return new GroupProduct(product.productId(), snapshot.productVersionId());
    }

    private UUID person(String name) {
        return partyApi.registerIndividual(name, LocalDate.of(1985, 6, 15),
            "+2557" + String.format("%08d", SEQ.incrementAndGet()), null, "test-agent").partyId();
    }
}
