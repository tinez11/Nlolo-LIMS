package tz.co.nlolo.lifeplatform.claims;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.claims.api.*;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.payment.application.PaymentApiImpl;
import tz.co.nlolo.lifeplatform.payment.domain.DisbursementInstruction;
import tz.co.nlolo.lifeplatform.payment.infrastructure.DisbursementInstructionRepository;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.MetricReaderRegistry;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.PolicyMovementRepository;
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
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
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
            "db-migrations/product/V14__credit_life_category.sql",
            "db-migrations/product/V15__exclusion_periods.sql",
            "db-migrations/product/V16__base_rate_term_bands.sql",
            "db-migrations/product/V17__cash_value.sql",
            "db-migrations/product/V18__payout_schedule.sql",
            "db-migrations/benefitpayout/V1__create_benefitpayout_schema.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/underwriting/V2__agent_of_record.sql",
            "db-migrations/underwriting/V3__medical_disclosure_recorded_by.sql",
            "db-migrations/underwriting/V4__proposal_identity.sql",
            "db-migrations/underwriting/V5__explicit_decision.sql",
            "db-migrations/underwriting/V6__proposal_terms_and_beneficiaries.sql",
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
            "db-migrations/policy/V27__expired_status.sql",
            "db-migrations/policy/V28__policies_due_to_expire.sql",
            "db-migrations/policy/V29__paid_up.sql",
            "db-migrations/policy/V30__surrender.sql",
            "db-migrations/policy/V31__free_look_status.sql",
            "db-migrations/claims/V1__create_claims_schema.sql",
            "db-migrations/claims/V2__grants_rls_money_checks_evidence_and_settlement_columns.sql",
            "db-migrations/claims/V3__registration_idempotency_key.sql",
            "db-migrations/claims/V5__claim_policy_member.sql",
            "db-migrations/claims/V6__exclusion_decline.sql",
            "db-migrations/claims/V7__claim_assessment_assessor_name.sql",
            "db-migrations/claims/V8__claim_evidence_uploaded_by_name.sql",
            // The settlement rail. A credit-life payout takes the EFT rail, which calls no
            // gateway at all -- so proving the whole chain here needs the payment schema and
            // nothing else: no WireMock, no aggregator, no stub. That is the rail being what
            // it claims to be rather than a convenience of the test.
            "db-migrations/payment/V1__create_payment_schema.sql",
            "db-migrations/payment/V2__grants_rls_money_checks_version_and_tenant_scoped_registries.sql",
            "db-migrations/payment/V3__inbound_callback_tenant_resolver.sql",
            "db-migrations/payment/V4__in_doubt_status_and_id_based_callback_resolvers.sql",
            "db-migrations/payment/V6__disbursement_method.sql",
            "db-migrations/payment/V7__q4_2026_partitions.sql",
            // regreporting, so the credit-life chain can be asserted all the way into the
            // figure a return is computed from. Its module dependencies are { refdata::api }
            // only, and refdata is already applied above.
            "db-migrations/regreporting/V1__create_regreporting_schema.sql",
            "db-migrations/regreporting/V2__grants_rls_dimensions_movements_and_return_lines.sql",
            "db-migrations/regreporting/V3__optimistic_locking_on_movement_tables.sql",
            "db-migrations/regreporting/V5__member_movement_columns.sql");
    }

    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private ClaimsApi claimsApi;
    @Autowired private PaymentApiImpl paymentApiImpl;
    @Autowired private DisbursementInstructionRepository disbursementRepository;
    @Autowired private PolicyMovementRepository policyMovementRepository;

    private static final AtomicInteger SEQ = new AtomicInteger(4000);

    /** 2,400,000 over 18 months, disbursed 2026-08-03. Cover declines straight-line. */
    private static final BigDecimal PRINCIPAL = new BigDecimal("2400000.00");
    private static final int TERM_MONTHS = 18;
    private static final LocalDate DISBURSED = LocalDate.of(2026, 8, 3);
    /** The scheme's free cover limit, and a loan that blows straight through it. Nobody
     * underwrites the excess, so the borrower is covered for the limit and the lender carries
     * the remaining 200,000,000 as ordinary credit risk. */
    private static final BigDecimal FCL = new BigDecimal("600000000.00");
    private static final BigDecimal ABOVE_FCL = new BigDecimal("800000000.00");

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
            ClaimDeclineReason.SUICIDE_WITHIN_EXCLUSION, null, idem(), "assessor.two"))
            .isInstanceOf(ClaimValidationException.class)
            .hasMessageContaining("APPROVED");
    }

    // ---- and the figure a regulatory return is computed from --------------

    /**
     * THE SPEC'S OWN CASE (§2.14): <i>"members are added every month and exited on every
     * settlement, so it is continuous — and it lands in a TIRA return."</i>
     *
     * <p>Hand-published events proved the arithmetic in {@code MemberMovementProjectionTest}.
     * This proves the REAL chain reaches it: a scheme issued through {@code issueGroupScheme}, a
     * borrower enrolled through {@code addMember}, and a death settled through the whole claim →
     * EFT → {@code DisbursementCompleted} → {@code dischargeForSettledClaim} path.
     *
     * <p>Lives here rather than in {@code ProjectionEndToEndTest}, which the plan named: that
     * class would need eight more product and policy migrations before it could issue a
     * credit-life scheme at all, while this one already runs the entire chain and needed only
     * regreporting's four. The assertion is about the credit-life chain reaching the projection,
     * so this is also its more honest home.
     */
    @Test
    void aBorrowerJoiningAndThenDyingMovesTheInForceFigureInBothDirections() {
        // The scheme opens with one 2,400,000 borrower, already covered by seedScheme().
        assertThat(inForceSumAssured()).isEqualByComparingTo(PRINCIPAL);

        // A second borrower enrolled the way a lender's monthly file enrols one.
        policyApi.addMember(scheme, PolicyApi.MemberInput.borrower("Juma Rajabu Kimaro",
            LocalDate.of(1990, 7, 2), null,
            new LoanTerms(new BigDecimal("1200000.00"), BigDecimal.ZERO, TERM_MONTHS,
                RepaymentFrequency.MONTHLY, DISBURSED, DISBURSED.plusMonths(1))), "staff-1");

        assertThat(inForceSumAssured())
            .as("the joiner's cover is in force -- before Plan 5 this stayed at the opening total")
            .isEqualByComparingTo("3600000.00");

        // And the first borrower dies at month six.
        //
        // TWO DIFFERENT NUMBERS HERE, AND BOTH ARE RIGHT -- do not "fix" one into the other.
        // The claim PAYS 1,600,000: what the borrower still owed on the day they died, which is
        // what the lender actually lost. The cover REMOVED from the in-force total is the full
        // 2,400,000, because restateSchemeTotal sums the stored covered_amount of members who are
        // still active, and a dead borrower is not covered for a reduced amount -- they are not
        // covered at all. So the scheme falls to the survivor's 1,200,000, not to 2,000,000.
        UUID claimId = approvedClaimAt(DISBURSED.plusMonths(6), new BigDecimal("1600000.00"));
        DisbursementInstruction eft = disbursementRepository
            .findByIdempotencyKeyAndTenantId(settlementKey, TenantContext.get())
            .orElseThrow(() -> new AssertionError("No disbursement was recorded for the settlement"));
        paymentApiImpl.markEftExecuted(eft.getDisbursementId(), "FT26092300993", "finance-officer-asha");
        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.SETTLED);

        assertThat(inForceSumAssured())
            .as("the exited member's whole covered amount left the scheme; the survivor's stayed")
            .isEqualByComparingTo("1200000.00");
    }

    /** SUM_ASSURED_IN_FORCE as the return generator computes it: issued plus member-added, less
     * terminated and member-exited, over every period up to the one asked for. */
    private BigDecimal inForceSumAssured() {
        UUID tenantId = TenantContext.get();
        String asOf = quarterOf(DISBURSED.plusMonths(6));
        return MetricReaderRegistry.cumulativeSumAssured(
            policyMovementRepository.findByTenantIdAndPeriodLessThanEqual(tenantId, asOf), asOf);
    }

    private static String quarterOf(LocalDate date) {
        return date.getYear() + "-Q" + ((date.getMonthValue() - 1) / 3 + 1);
    }

    // ---- fixtures -----------------------------------------------------------

    /** A claim on the borrower, dated {@code dateOfEvent}, already under assessment. */

    // ---- what the claim is worth ---------------------------------------------

    @Test
    void anOrdinaryDeathOnDayOneIsPaidInFull() {
        // THE TEST THAT PROVES THERE IS NO GENERAL WAITING PERIOD (client answer 3.4). Both
        // exclusion windows are open on day one, and an ordinary death is still paid: an open
        // window is permission for an assessor to cite a reason, never a bar on settlement.
        //
        // Getting this wrong is the expensive direction. A platform that quietly treated an open
        // window as "not yet covered" would refuse every early death on a book where nobody is
        // underwritten -- and the lender would be told their borrower was insured.
        UUID claimId = approvedClaimAt(DISBURSED, PRINCIPAL);

        ClaimView claim = claimsApi.getClaim(claimId);
        assertThat(claim.status()).isEqualTo(ClaimStatus.SETTLEMENT_REQUESTED);
        assertThat(claim.approvedAmount()).isEqualByComparingTo(PRINCIPAL);
    }

    @Test
    void aDeathClaimPaysWhatTheBorrowerStillOwedOnTheDayTheyDied() {
        // 2,400,000 over 18 months, disbursed 2026-08-03. Death at month 6 leaves twelve of
        // eighteen months outstanding: 1,600,000 -- not the 2,400,000 they borrowed.
        UUID claimId = approvedClaimAt(DISBURSED.plusMonths(6), new BigDecimal("1600000.00"));

        assertThat(claimsApi.getClaim(claimId).approvedAmount()).isEqualByComparingTo("1600000.00");
    }

    @Test
    void aClaimCannotBeApprovedForMoreThanTheBorrowerStillOwed() {
        // The ceiling is the claim's own stored facts, never the caller's figure. Without it a
        // manager could settle a month-six death for the original principal and hand the lender
        // 800,000 shillings of cover that had already run off.
        UUID claimId = assessedClaimAt(DISBURSED.plusMonths(6));

        assertThatThrownBy(() -> claimsApi.decideSettlement(claimId, true, PRINCIPAL, "TZS",
            null, null, idem(), "claims.manager"))
            // The CEILING refused it -- pinned, so a payee rule firing first cannot pass this.
            .isInstanceOf(ClaimValidationException.class)
            .hasMessageContaining("covered for");

        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.UNDER_ASSESSMENT);
    }

    /** The insurer deals only with the lender (client answer 3.6), who is the claimant and the
     * policyholder (spec 2.9). A payee typed into a credit-life approval was never a real choice
     * -- in dev they read "mobile" and "i approve" -- and could have named any account at all. */
    @Test
    void aCreditLifeApprovalRefusesATypedPayee() {
        UUID claimId = assessedClaimAt(DISBURSED.plusMonths(6));

        assertThatThrownBy(() -> claimsApi.decideSettlement(claimId, true, new BigDecimal("1600000.00"),
            "TZS", null, "0712345678", idem(), "claims.manager"))
            .isInstanceOf(ClaimValidationException.class)
            .hasMessageContaining("pays the lender");

        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.UNDER_ASSESSMENT);
    }

    @Test
    void aCappedBorrowerIsPaidTheCapAndTheResidualIsTheLendersCreditRisk() {
        // Spec 2.7: cover above the free cover limit is capped. The claim pays the cap, closes,
        // and the member exits -- the shortfall is the lender's, which is the entire purpose of
        // a free cover limit. The platform must not silently pay the full debt.
        //
        // 800,000,000 borrowed against a 600,000,000 limit, and nobody underwrote the excess. The
        // borrower is covered for 600,000,000 from day one -- not zero, and not the whole loan.
        String bigScheme = issueCreditLifeScheme(bankPartyId, ABOVE_FCL);
        UUID member = policyApi.listMembers(bigScheme, null, null, PageRequest.of(0, 10))
            .getContent().get(0).policyMemberId();

        ClaimView claim = claimsApi.registerClaim(new ClaimsApi.RegisterClaimRequest(
            bigScheme, member, bankPartyId, ClaimType.DEATH, DISBURSED,
            new DeathClaimDetails("Natural causes", "Dar es Salaam", DISBURSED, "Dr Mwakalinga")),
            idem(), "claims.clerk");
        claimsApi.submitAssessment(claim.claimId(), "verified", null, null, false, "assessor.one", null);

        // The 200,000,000 above the limit is uninsured, which is the point -- proved by the
        // platform refusing to settle for the whole debt. On the SAME claim, first: this used to
        // register a second death claim on the same borrower to show it, and one death claim per
        // life now refuses that registration outright.
        assertThatThrownBy(() -> claimsApi.decideSettlement(claim.claimId(), true, ABOVE_FCL, "TZS",
            null, null, idem(), "claims.manager"))
            .isInstanceOf(ClaimValidationException.class)
            .hasMessageContaining("covered for");

        claimsApi.decideSettlement(claim.claimId(), true, FCL, "TZS", null,
            null, idem(), "claims.manager");
        assertThat(claimsApi.getClaim(claim.claimId()).approvedAmount()).isEqualByComparingTo(FCL);
    }

    /** Rejecting the death claim clears it from the member; reopening it records it again. */
    @Test
    void theOpenDeathClaimFollowsTheClaimThroughRejectionAndReopening() {
        UUID claimId = assessedClaimAt(DISBURSED.plusMonths(6));
        assertThat(borrower().openDeathClaimId()).isEqualTo(claimId);

        claimsApi.decideSettlement(claimId, false, null, null, "documents were forged",
            null, null, idem(), "claims.manager");
        assertThat(borrower().openDeathClaimId())
            .as("a rejected claim is not in progress; the borrower reads as a live loan again")
            .isNull();

        claimsApi.reopenClaim(claimId, "new evidence", "claims.manager");
        assertThat(borrower().openDeathClaimId()).isEqualTo(claimId);
    }

    private PolicyMemberView borrower() {
        return policyApi.listMembers(scheme, null, null, PageRequest.of(0, 10)).getContent().get(0);
    }

    /** One death claim per life, on the product where it was found: three approved claims on one
     * borrower in dev, 1,640,000 against 800,000 of cover. */
    @Test
    void aSecondDeathClaimOnTheSameBorrowerIsRefused() {
        UUID first = assessedClaimAt(DISBURSED.plusMonths(6));

        assertThatThrownBy(() -> assessedClaimAt(DISBURSED.plusMonths(6)))
            .isInstanceOf(ClaimValidationException.class)
            .hasMessageContaining(first.toString());
    }

    // ---- and then the loan comes off cover -----------------------------------

    @Test
    void settlingAClaimTakesTheLoanOffCoverAndRefundsNoPremium() {
        // The whole chain, with nothing hand-published: approve -> claims.ClaimSettlementRequested
        // -> payment records an EFT AWAITING_EXECUTION -> finance confirms it ->
        // payment.DisbursementCompleted -> the claim settles -> the borrower comes off cover.
        UUID claimId = approvedClaimAt(DISBURSED.plusMonths(6), new BigDecimal("1600000.00"));

        // Nothing is settled yet, and that is the EFT rail working: the money has not moved, so
        // the claim has not. A mobile-money claim would already be SETTLED by this line.
        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.SETTLEMENT_REQUESTED);
        PolicyMemberView awaitingPayment = policyApi.listMembers(scheme, null, null, PageRequest.of(0, 10))
            .getContent().get(0);
        assertThat(awaitingPayment.status()).isEqualTo(MemberStatus.ACTIVE);
        // Still on cover -- but the roll now says why: a death claim is open on this life. Before
        // V23 this row was indistinguishable from a live loan.
        assertThat(awaitingPayment.openDeathClaimId()).isEqualTo(claimId);

        DisbursementInstruction eft = disbursementRepository
            .findByIdempotencyKeyAndTenantId(settlementKey, TenantContext.get())
            .orElseThrow(() -> new AssertionError("No disbursement was recorded for the settlement"));
        assertThat(eft.getStatus()).isEqualTo("AWAITING_EXECUTION");
        assertThat(eft.getMethod()).isEqualTo("EFT");
        // The lender, named by the platform -- nobody typed a destination. Finance reads who to
        // pay and on which scheme; the account itself they hold, out of band (spec 2.9).
        assertThat(eft.getPayeeRef()).isEqualTo("Lender Co — policyholder of " + scheme);

        paymentApiImpl.markEftExecuted(eft.getDisbursementId(), "FT26092300881", "finance-officer-asha");

        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.SETTLED);

        PolicyMemberView exited = policyApi.listMembers(scheme, null, null, PageRequest.of(0, 10))
            .getContent().get(0);
        assertThat(exited.status()).isEqualTo(MemberStatus.EXITED);
        assertThat(exited.leftOn()).isEqualTo(DISBURSED.plusMonths(6));
        assertThat(exited.exitReason()).isEqualTo(ExitReason.CLAIM_SETTLED);
        // The settlement's own exit closed the open claim in the same write.
        assertThat(exited.openDeathClaimId()).isNull();
        // No refund: the premium was fully earned the moment the insurer paid. Refunding it would
        // pay the claim and give back the money that funded it. PolicyApiImpl.addRefundDetail
        // returns before computing anything for a CLAIM_SETTLED exit; MemberExitIntegrationTest
        // owns the credit-side assertion, which needs billing's schema.
        assertThat(exited.exitReason()).isEqualTo(ExitReason.CLAIM_SETTLED);
    }

    private UUID assessedClaimAt(LocalDate dateOfEvent) {
        ClaimView claim = claimsApi.registerClaim(new ClaimsApi.RegisterClaimRequest(
            scheme, borrowerMemberId, bankPartyId, ClaimType.DEATH, dateOfEvent,
            new DeathClaimDetails("Under investigation", "Dar es Salaam", dateOfEvent, "Dr Mwakalinga")),
            idem(), "claims.clerk");
        claimsApi.submitAssessment(claim.claimId(), "investigating", null, null, false, "assessor.one", null);
        return claim.claimId();
    }

    /** The idempotency key the last approval used, so the test can find the payout it produced.
     * payment keys the disbursement on it -- that is the handle, not a lookup by claim. */
    private String settlementKey;

    /** Registers, assesses and approves one death claim, and returns its id. {@code expected} is
     * what the platform should value it at: passing the figure in rather than reading it back is
     * what makes the approval itself an assertion -- an approval above cover is REFUSED. */
    private UUID approvedClaimAt(LocalDate dateOfEvent, BigDecimal expected) {
        UUID claimId = assessedClaimAt(dateOfEvent);
        settlementKey = idem();
        claimsApi.decideSettlement(claimId, true, expected, "TZS", null,
            null, settlementKey, "claims.manager");
        return claimId;
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
        return issueCreditLifeScheme(lender, PRINCIPAL);
    }

    private String issueCreditLifeScheme(UUID lender, BigDecimal principal) {
        GroupProduct product = publish(ProductCategory.CREDIT_LIFE, "CL-CLAIM-" + SEQ.incrementAndGet());
        return policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            lender, product.productId(), product.productVersionId(), null,
            BenefitBasis.AMORTISING_LOAN, null, null, FCL, "TZS",
            null, List.of(PolicyApi.MemberInput.borrower("Amina Hassan Mwinyi",
                LocalDate.of(1988, 3, 14), null,
                new LoanTerms(principal, BigDecimal.ZERO, TERM_MONTHS, RepaymentFrequency.MONTHLY,
                    DISBURSED, DISBURSED.plusMonths(1)))),
            new BigDecimal("52000.00"), "TZS", "SINGLE",
            LocalDate.of(2026, 6, 1), null, "credit life onboarding", IssuanceBasis.MIGRATION,
            InterestMethod.FLAT_RATE, RepaymentFrequency.MONTHLY, new BigDecimal("0.5000"), CreditLifePremiumBasis.PER_ANNUM_ON_PRINCIPAL),
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
