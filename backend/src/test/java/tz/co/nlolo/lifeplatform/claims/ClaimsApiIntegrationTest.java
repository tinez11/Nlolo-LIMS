package tz.co.nlolo.lifeplatform.claims;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.claims.api.ClaimAssessmentView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimNotFoundException;
import tz.co.nlolo.lifeplatform.claims.api.ClaimStatus;
import tz.co.nlolo.lifeplatform.claims.api.ClaimType;
import tz.co.nlolo.lifeplatform.claims.api.ClaimValidationException;
import tz.co.nlolo.lifeplatform.claims.api.ClaimView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.claims.api.DeathClaimDetails;
import tz.co.nlolo.lifeplatform.claims.api.DisabilityClaimDetails;
import tz.co.nlolo.lifeplatform.claims.api.InvalidClaimStateException;
import tz.co.nlolo.lifeplatform.claims.api.MaturityClaimDetails;
import tz.co.nlolo.lifeplatform.claims.domain.Claim;
import tz.co.nlolo.lifeplatform.claims.infrastructure.ClaimRepository;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyNotFoundException;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.product.api.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;
import tz.co.nlolo.lifeplatform.claims.api.CriticalIllnessClaimDetails;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 4: registration's full validation chain, run under REAL {@code app_role}
 * (NOSUPERUSER NOBYPASSRLS) rather than the Testcontainers superuser every other module's
 * integration test uses -- copies {@code MobileMoneyCallbackIntegrationTest}'s
 * {@code @DynamicPropertySource} + {@code ALTER ROLE} setup, the freshest correct example in
 * this codebase, so RLS on {@code claims.claim} is genuinely exercised, not merely declared.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class ClaimsApiIntegrationTest {

    private static final String APP_ROLE_PASSWORD = "claims_it_password";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "app_role");
        registry.add("spring.datasource.password", () -> APP_ROLE_PASSWORD);
    }

    @BeforeAll
    static void applyMigrationsAndBootstrapAppRole() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            "db-migrations/refdata/V8__ifrs17_branches_and_channels.sql",
            "db-migrations/refdata/V9__journal_reason_codes.sql",
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
            "db-migrations/product/V15__exclusion_periods.sql",
            "db-migrations/product/V16__base_rate_term_bands.sql",
            "db-migrations/product/V17__cash_value.sql",
            "db-migrations/product/V18__payout_schedule.sql",
            "db-migrations/product/V19__accumulation_terms.sql",
            "db-migrations/product/V20__deposit_rate_grid.sql",
            "db-migrations/product/V21__bonus_terms.sql",
            "db-migrations/product/V27__ifrs17_classification.sql",
            "db-migrations/product/V28__survival_investment_component.sql",
            "db-migrations/product/V29__funeral_group_rate.sql",
            "db-migrations/product/V30__funeral_group_rate_period.sql",
            "db-migrations/benefitpayout/V1__create_benefitpayout_schema.sql",
            "db-migrations/benefitpayout/V2__annuity_streams.sql",
            "db-migrations/benefitpayout/V3__withholding.sql",
            "db-migrations/benefitpayout/V4__withholding_rule_end.sql",
            "db-migrations/accumulation/V1__create_accumulation_schema.sql",
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
            "db-migrations/underwriting/V18__sale_channel_and_branch.sql",
            "db-migrations/underwriting/V19__group_funeral_proposal.sql",
            "db-migrations/underwriting/V20__sale_lock_backfill.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
            "db-migrations/policy/V3__premium_fields.sql",
            "db-migrations/policy/V4__underwriting_case_id.sql",
            "db-migrations/policy/V5__beneficiary_party_index.sql",
            "db-migrations/policy/V6__policy_term.sql",
            "db-migrations/policy/V7__life_assured.sql",
            "db-migrations/policy/V10__one_policy_per_underwriting_case.sql",
            "db-migrations/policy/V11__not_taken_up_status.sql",
            "db-migrations/policy/V24__issuance_record.sql",
            "db-migrations/policy/V27__expired_status.sql",
            "db-migrations/policy/V28__policies_due_to_expire.sql",
            "db-migrations/policy/V29__paid_up.sql",
            "db-migrations/policy/V30__surrender.sql",
            "db-migrations/policy/V31__free_look_status.sql",
            "db-migrations/policy/V37__sale_classification.sql",
            "db-migrations/policy/V38__group_funeral_scheme.sql",
            "db-migrations/policy/V40__commencement_never_null.sql",
            "db-migrations/claims/V1__create_claims_schema.sql",
            "db-migrations/claims/V2__grants_rls_money_checks_evidence_and_settlement_columns.sql",
            "db-migrations/claims/V3__registration_idempotency_key.sql",
            "db-migrations/claims/V5__claim_policy_member.sql",
            "db-migrations/claims/V6__exclusion_decline.sql",
            "db-migrations/claims/V7__claim_assessment_assessor_name.sql",
            "db-migrations/claims/V8__claim_evidence_uploaded_by_name.sql",
            "db-migrations/claims/V11__claim_document_request.sql");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"
                + APP_ROLE_PASSWORD + "'");
        }
    }

    @Autowired private ClaimsApi claimsApi;
    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private ClaimRepository claimRepository;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private record Fixture(UUID applicantId, UUID productId, UUID productVersionId) {}

    private Fixture buildFixture(UUID tenantId, String productCode) {
        TenantContext.set(tenantId);
        PartyView applicant = partyApi.registerIndividual("Claims IT Applicant " + productCode, LocalDate.of(1985, 3, 1),
            "+25571500" + String.format("%04d", Math.abs(productCode.hashCode() % 10000)), null, "test-agent");
        ProductSummaryView product = productApi.createProduct(productCode, "Claims IT Product", ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                        // Every benefit this class registers a claim against. All at SUM_ASSURED, so each
            // claim is valued at the policy sum assured exactly as it was before benefits drove
            // coverage -- no existing amount assertion moves. Before this, a MATURITY or
            // DISABILITY claim was valued at the death benefit because claimableCover took no
            // claim type at all.
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED),
                    new ProductApi.BenefitInput(BenefitType.DISABILITY, BenefitCalculationMethod.SUM_ASSURED),
                    new ProductApi.BenefitInput(BenefitType.CRITICAL_ILLNESS, BenefitCalculationMethod.SUM_ASSURED),
                    new ProductApi.BenefitInput(BenefitType.MATURITY, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        return new Fixture(applicant.partyId(), product.productId(), snapshot.productVersionId());
    }

    /** Issues a policy with a NULL underwritingCaseId -- the exact pre-M6 / unbackfillable shape
     * Task 4's fail-closed contestability path exists for. Every scenario below except the
     * dedicated cross-tenant/unknown-party ones uses this, since none of the OTHER assertions
     * this task cares about (in-force check, coverage check, details/type mismatch) depend on
     * which contestability answer comes back. */
    private String issuePolicyWithNullUnderwritingCase(UUID tenantId, Fixture fixture) {
        TenantContext.set(tenantId);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(), fixture.productVersionId(),
            new BigDecimal("2000000"), "TZS", new BigDecimal("40000.00"), "TZS", "MONTHLY", null, List.of(), "Claims IT test", java.time.LocalDate.now().minusYears(1), null, null, null, null);
        String issuedPolicyNumber = policyApi.issuePolicy(null, request, "test-staff").policyNumber();
        // Cover starts with the first premium. This fixture needs a policy on risk.
        policyApi.activateOnFirstPremium(issuedPolicyNumber);
        return issuedPolicyNumber;
    }

    /**
     * A policy that has reached its maturity date today: commenced a year ago on a 12-month term.
     * A maturity claim is only claimable once the term is up, which registration now enforces.
     */
    private String issueMaturedPolicy(UUID tenantId, Fixture fixture) {
        TenantContext.set(tenantId);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(), fixture.productVersionId(),
            new BigDecimal("2000000"), "TZS", new BigDecimal("40000.00"), "TZS", "MONTHLY", null, List.of(), "Claims IT test",
            LocalDate.now().minusMonths(12), 12, null, null, null);
        String issuedPolicyNumber = policyApi.issuePolicy(null, request, "test-staff").policyNumber();
        policyApi.activateOnFirstPremium(issuedPolicyNumber);
        return issuedPolicyNumber;
    }

    private ClaimsApi.RegisterClaimRequest deathRequest(String policyNumber, UUID claimantId, LocalDate dateOfEvent) {
        return new ClaimsApi.RegisterClaimRequest(policyNumber, null, claimantId, ClaimType.DEATH, dateOfEvent,
            new DeathClaimDetails("Natural causes", "Dar es Salaam", dateOfEvent, "Dr. Test"));
    }

    private ClaimsApi.RegisterClaimRequest maturityRequest(String policyNumber, UUID claimantId, LocalDate dateOfEvent) {
        return new ClaimsApi.RegisterClaimRequest(policyNumber, null, claimantId, ClaimType.MATURITY, dateOfEvent,
            new MaturityClaimDetails(dateOfEvent));
    }

    /** Registers and returns a fresh DEATH claim id, tenant already set on {@link TenantContext}. */
    private UUID registerDeathClaim(UUID tenantId, Fixture fixture, String policyNumber, String idempotencyKey) {
        TenantContext.set(tenantId);
        return claimsApi.registerClaim(deathRequest(policyNumber, fixture.applicantId(), LocalDate.now().minusDays(1)),
            idempotencyKey, "claims-staff").claimId();
    }

    /** Drives SETTLEMENT_REQUESTED -> SETTLED directly on the aggregate, bypassing
     * {@link ClaimsApi} -- ClaimsApi has no public method for this transition, since the real
     * caller is Task 6's payment-completion listener, which is out of scope here. This is test
     * setup only, exercising {@code Claim.markSettled()} which Task 3's unit test already covers
     * in isolation; the point of this helper is reaching SETTLED so reopenClaim's second source
     * state can be exercised, not re-testing markSettled itself. */
    private void settleDirectly(UUID claimId, UUID tenantId) {
        TenantContext.set(tenantId);
        Claim claim = claimRepository.findByClaimIdAndTenantId(claimId, tenantId).orElseThrow();
        claim.markSettled();
        claimRepository.save(claim);
    }

    /** Forces UNDER_ASSESSMENT with NO ClaimAssessment row, bypassing submitAssessment (which
     * always inserts one). Exists to isolate decideSettlement's assessment-count guard from
     * Claim.approve()'s own state-machine guard: with a REGISTERED claim, either guard throws
     * InvalidClaimStateException, so a REGISTERED-claim test alone cannot tell which one fired --
     * a review of this task found exactly that ambiguity and confirmed by mutation that removing
     * the count guard entirely still left the suite green. */
    private void beginAssessmentDirectlyWithNoAssessmentRow(UUID claimId, UUID tenantId) {
        TenantContext.set(tenantId);
        Claim claim = claimRepository.findByClaimIdAndTenantId(claimId, tenantId).orElseThrow();
        claim.beginAssessment();
        claimRepository.save(claim);
    }

    /**
     * Registering a critical-illness claim on a death-only product is refused rather than valued
     * at the death benefit.
     *
     * <p>This is the defect the batch exists for: {@code claimableCover} took no claim type and
     * returned the policy's single sum assured for every claim, so a survivable condition paid the
     * whole cover on a rider nobody had costed. Paying a benefit nobody authored is how a product
     * pays for cover it never priced.
     *
     * <p>Uses its own product rather than {@code buildFixture}'s, which now authors all four
     * benefit types because this class claims against all four.
     */
    @Test
    void refusesACriticalIllnessClaimOnAProductThatOnlyCoversDeath() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);

        PartyView applicant = partyApi.registerIndividual("Claims IT CI Applicant",
            LocalDate.of(1985, 3, 1), "+255715009901", null, "test-agent");
        ProductSummaryView product = productApi.createProduct("CLAIMS-IT-CI-01", "Death only",
            ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());

        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId,
            new Fixture(applicant.partyId(), product.productId(), snapshot.productVersionId()));
        LocalDate dateOfEvent = LocalDate.now().minusDays(1);

        ClaimsApi.RegisterClaimRequest request = new ClaimsApi.RegisterClaimRequest(
            policyNumber, null, applicant.partyId(), ClaimType.CRITICAL_ILLNESS, dateOfEvent,
            new CriticalIllnessClaimDetails("Myocardial infarction", dateOfEvent, "I21"));

        assertThatThrownBy(() -> claimsApi.registerClaim(request, "reg-idem-ci-01", "claims-staff"))
            .hasMessageContaining("CRITICAL_ILLNESS");
    }

    @Test
    void registersADeathClaimAndPublishesRegistrationWithFailClosedContestabilityFlagged() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-REG-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);

        TenantContext.set(tenantId);
        LocalDate dateOfEvent = LocalDate.now().minusDays(3);
        ClaimView view = claimsApi.registerClaim(deathRequest(policyNumber, fixture.applicantId(), dateOfEvent),
            "reg-idem-01", "claims-staff");

        assertThat(view.claimId()).isNotNull();
        assertThat(view.policyNumber()).isEqualTo(policyNumber);
        assertThat(view.claimantPartyId()).isEqualTo(fixture.applicantId());
        assertThat(view.claimType()).isEqualTo(ClaimType.DEATH);
        assertThat(view.dateOfEvent()).isEqualTo(dateOfEvent);
        assertThat(view.details()).isInstanceOf(DeathClaimDetails.class);
        // The policy's underwritingCaseId is NULL -- the fail-closed path MUST flag this claim
        // for review, not silently wave it through as if it were safely outside the window.
        assertThat(view.requiresContestabilityReview())
            .as("a NULL underwritingCaseId must fail closed to true, never silently pass as false")
            .isTrue();

        // Round-trips through getClaim too, proving it is genuinely persisted, not just an
        // in-memory view returned by registerClaim.
        ClaimView reloaded = claimsApi.getClaim(view.claimId());
        assertThat(reloaded.claimId()).isEqualTo(view.claimId());
        assertThat(reloaded.requiresContestabilityReview()).isTrue();
    }

    @Test
    void rejectsRegistrationWhenThePolicyIsNotInForce() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-LAPSE-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);

        TenantContext.set(tenantId);
        policyApi.lapsePolicy(policyNumber, "test-staff"); // isPolicyInForce now returns false

        ClaimsApi.RegisterClaimRequest request = deathRequest(policyNumber, fixture.applicantId(), LocalDate.now());
        assertThrows(ClaimValidationException.class, () -> claimsApi.registerClaim(request, "reg-idem-02", "claims-staff"));
    }

    @Test
    void rejectsRegistrationWhenDetailsClaimTypeDisagreesWithTheDeclaredClaimType() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-MISMATCH-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);

        TenantContext.set(tenantId);
        LocalDate dateOfEvent = LocalDate.now().minusDays(1);
        // claimType says DEATH, details says DISABILITY -- Claim's own constructor
        // (Claim.java:107-113) must reject this, and ClaimsApiImpl must let it propagate.
        ClaimsApi.RegisterClaimRequest request = new ClaimsApi.RegisterClaimRequest(policyNumber, null, fixture.applicantId(),
            ClaimType.DEATH, dateOfEvent, new DisabilityClaimDetails("Loss of limb", dateOfEvent, true, new BigDecimal("50")));

        assertThrows(ClaimValidationException.class, () -> claimsApi.registerClaim(request, "reg-idem-03", "claims-staff"));
    }

    @Test
    void rejectsRegistrationForAnUnknownClaimant() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-NOPARTY-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);

        TenantContext.set(tenantId);
        UUID unknownPartyId = UUID.randomUUID();
        ClaimsApi.RegisterClaimRequest request = deathRequest(policyNumber, unknownPartyId, LocalDate.now());

        assertThrows(PartyNotFoundException.class, () -> claimsApi.registerClaim(request, "reg-idem-04", "claims-staff"));
    }

    @Test
    void aNullUnderwritingCaseIdFailsClosedToRequiringContestabilityReview() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-CONTEST-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);

        TenantContext.set(tenantId);
        ClaimView view = claimsApi.registerClaim(deathRequest(policyNumber, fixture.applicantId(), LocalDate.now()),
            "reg-idem-05", "claims-staff");

        // Falsifiable: if the fail-closed guard were missing or inverted, this would come back
        // false (or throw an NPE calling checkContestability with a null case id) instead.
        assertThat(view.requiresContestabilityReview()).isTrue();
    }

    @Test
    void aClaimIsInvisibleToAnyOtherTenantUnderRealRls() {
        UUID ownerTenant = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        Fixture fixture = buildFixture(ownerTenant, "CLAIMS-IT-TENANT-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(ownerTenant, fixture);

        TenantContext.set(ownerTenant);
        ClaimView view = claimsApi.registerClaim(deathRequest(policyNumber, fixture.applicantId(), LocalDate.now()),
            "reg-idem-06", "claims-staff");
        UUID claimId = view.claimId();

        // Same claimId, DIFFERENT tenant context -- must come back not-found, proving
        // ClaimRepository.findByClaimIdAndTenantId is genuinely enforced by Postgres RLS under
        // real app_role (NOSUPERUSER NOBYPASSRLS), not merely by the tenantId argument the
        // application layer happens to pass.
        TenantContext.set(otherTenant);
        assertThrows(ClaimNotFoundException.class, () -> claimsApi.getClaim(claimId));

        // Sanity check: the SAME tenant can still read it, so the not-found above is genuinely
        // about tenant isolation and not some other bug (e.g. a broken claimId).
        TenantContext.set(ownerTenant);
        assertThat(claimsApi.getClaim(claimId).claimId()).isEqualTo(claimId);
    }

    // ---- Task 5: submitAssessment ----------------------------------------------------------

    @Test
    void submitAssessmentMovesRegisteredToUnderAssessment() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-ASSESS-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture, policyNumber, "reg-idem-assess-01");

        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.REGISTERED);

        ClaimAssessmentView assessment = claimsApi.submitAssessment(claimId, "Consistent with cause of death",
            new BigDecimal("2000000"), "TZS", false, "assessor-1", null);

        assertThat(assessment.claimId()).isEqualTo(claimId);
        assertThat(assessment.assessor()).isEqualTo("assessor-1");
        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.UNDER_ASSESSMENT);
    }

    /** Only the APPROVAL used to be bounded by the cover, so an assessor could record a figure no
     * manager could ever approve. The recommendation is now held to the same ceiling -- and a
     * refused one leaves nothing behind: no row, and the claim still REGISTERED. */
    @Test
    void aRecommendationAboveTheCoverIsRefusedAndLeavesTheClaimUntouched() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-ASSESS-CAP");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture, policyNumber, "reg-idem-assess-cap");

        ClaimValidationException refused = assertThrows(ClaimValidationException.class,
            () -> claimsApi.submitAssessment(claimId, "Findings", new BigDecimal("2000000.01"), "TZS",
                false, "assessor-cap", null));
        assertThat(refused.getMessage()).contains("2000000.01").contains("covered for");

        assertThat(claimsApi.listAssessments(claimId)).isEmpty();
        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.REGISTERED);

        // INCLUSIVE, as the approval is: the full cover is the commonest correct recommendation.
        claimsApi.submitAssessment(claimId, "Findings", new BigDecimal("2000000.00"), "TZS", false,
            "assessor-cap", null);
        assertThat(claimsApi.listAssessments(claimId)).hasSize(1);
    }

    // ---- Task 5: decideSettlement -- assessment-count invariant (Cl3) ----------------------

    @Test
    void approvalWithoutAnyAssessmentThrowsForDeath() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-NOASSESS-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture, policyNumber, "reg-idem-noassess-01");

        // DEATH has no assessment rows at all -- claim.approve would even reject the state
        // transition itself (still REGISTERED), but the assessment-count guard must fire first.
        assertThrows(InvalidClaimStateException.class, () -> claimsApi.decideSettlement(claimId, true,
            new BigDecimal("2000000"), "TZS", null, "payee-ref-1", "settle-idem-noassess-01", "manager-1"));
    }

    /** Isolates the assessment-count guard from Claim.approve()'s own state-machine guard: unlike
     * approvalWithoutAnyAssessmentThrowsForDeath (still REGISTERED, so EITHER guard could be the
     * one throwing), this claim is genuinely UNDER_ASSESSMENT with zero ClaimAssessment rows --
     * approve()'s state check alone would allow this transition, so only the count guard can be
     * the cause if this still throws. */
    @Test
    void approvalWithZeroAssessmentRowsThrowsForDeathEvenWhenUnderAssessment() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-NOASSESS-02");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture, policyNumber, "reg-idem-noassess-02");

        beginAssessmentDirectlyWithNoAssessmentRow(claimId, tenantId);
        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.UNDER_ASSESSMENT);

        assertThrows(InvalidClaimStateException.class, () -> claimsApi.decideSettlement(claimId, true,
            new BigDecimal("2000000"), "TZS", null, "payee-ref-1", "settle-idem-noassess-02", "manager-1"));
    }

    @Test
    void approvalWithoutAnyAssessmentSucceedsForMaturity() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-MATURITY-01");
        // A maturity claim is only claimable on a policy that has reached its maturity date, which
        // registration now enforces. This fixture matures today.
        String policyNumber = issueMaturedPolicy(tenantId, fixture);

        TenantContext.set(tenantId);
        UUID claimId = claimsApi.registerClaim(maturityRequest(policyNumber, fixture.applicantId(), LocalDate.now()),
            "reg-idem-maturity-01", "claims-staff").claimId();

        // Falsifiable: MATURITY is the ONLY claim type allowed to auto-approve with zero
        // assessments (docs/03-aggregate-design.md:134, Cl3). No submitAssessment call precedes
        // this, and it still succeeds.
        claimsApi.decideSettlement(claimId, true, new BigDecimal("2000000"), "TZS", null,
            "payee-ref-2", "settle-idem-maturity-01", "manager-1");

        ClaimView view = claimsApi.getClaim(claimId);
        assertThat(view.status()).isEqualTo(ClaimStatus.SETTLEMENT_REQUESTED);
        // isEqualByComparingTo, not isEqualTo -- Postgres NUMERIC(19,2) round-trips as
        // "2000000.00", which is BigDecimal-unequal but numerically equal to "2000000".
        assertThat(view.approvedAmount()).isEqualByComparingTo(new BigDecimal("2000000"));
    }

    // ---- Task 5: decideSettlement -- separation of duties -----------------------------------

    @Test
    void theSameUserAssessingThenDecidingIsRejected() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-SOD-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture, policyNumber, "reg-idem-sod-01");

        claimsApi.submitAssessment(claimId, "Findings", new BigDecimal("2000000"), "TZS", false, "same-person", null);

        // "same-person" both assessed and is now trying to decide -- must be rejected even
        // though nothing here checks their Keycloak role, only the persisted assessor identity.
        assertThrows(ClaimValidationException.class, () -> claimsApi.decideSettlement(claimId, true,
            new BigDecimal("2000000"), "TZS", null, "payee-ref-3", "settle-idem-sod-01", "same-person"));
    }

    @Test
    void aDifferentDeciderThanTheAssessorIsAllowed() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-SOD-02");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture, policyNumber, "reg-idem-sod-02");

        claimsApi.submitAssessment(claimId, "Findings", new BigDecimal("2000000"), "TZS", false, "assessor-2", null);

        claimsApi.decideSettlement(claimId, true, new BigDecimal("2000000"), "TZS", null,
            "payee-ref-4", "settle-idem-sod-02", "manager-2");

        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.SETTLEMENT_REQUESTED);
    }

    // ---- Task 5: decideSettlement -- payeeRef / idempotencyKey required on approval --------

    @Test
    void aBlankPayeeRefOnApprovalIsRejected() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-BLANKPAYEE-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture, policyNumber, "reg-idem-blankpayee-01");
        claimsApi.submitAssessment(claimId, "Findings", new BigDecimal("2000000"), "TZS", false, "assessor-3", null);

        assertThrows(ClaimValidationException.class, () -> claimsApi.decideSettlement(claimId, true,
            new BigDecimal("2000000"), "TZS", null, "   ", "settle-idem-blankpayee-01", "manager-3"));
    }

    @Test
    void aBlankIdempotencyKeyOnApprovalIsRejected() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-BLANKIDEM-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture, policyNumber, "reg-idem-blankidem-01");
        claimsApi.submitAssessment(claimId, "Findings", new BigDecimal("2000000"), "TZS", false, "assessor-4", null);

        // A blank key would otherwise silently reach the payment rail zero times.
        assertThrows(ClaimValidationException.class, () -> claimsApi.decideSettlement(claimId, true,
            new BigDecimal("2000000"), "TZS", null, "payee-ref-5", "   ", "manager-4"));
    }

    // ---- Task 5: fraudIndicator must never itself block approval ---------------------------

    @Test
    void fraudIndicatorTrueDoesNotBlockApproval() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-FRAUD-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture, policyNumber, "reg-idem-fraud-01");

        ClaimAssessmentView assessment = claimsApi.submitAssessment(claimId, "Suspicious circumstances",
            new BigDecimal("2000000"), "TZS", true, "assessor-5", null);
        assertThat(assessment.fraudIndicator()).isTrue();

        // Falsifiable: if fraudIndicator wrongly gated approval, this would throw instead.
        claimsApi.decideSettlement(claimId, true, new BigDecimal("2000000"), "TZS", null,
            "payee-ref-6", "settle-idem-fraud-01", "manager-5");

        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.SETTLEMENT_REQUESTED);
    }

    // ---- Task 5: decideSettlement -- rejection path -----------------------------------------

    @Test
    void rejectionRecordsTheReasonAndReachesRejected() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-REJECT-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture, policyNumber, "reg-idem-reject-01");
        claimsApi.submitAssessment(claimId, "Insufficient evidence", new BigDecimal("2000000"), "TZS", false, "assessor-6", null);

        claimsApi.decideSettlement(claimId, false, null, null, "Cause of death not covered",
            null, null, "manager-6");

        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.REJECTED);
    }

    // ---- Task 5: reopenClaim -----------------------------------------------------------------

    @Test
    void reopenWorksFromRejected() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-REOPEN-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture, policyNumber, "reg-idem-reopen-01");
        claimsApi.submitAssessment(claimId, "Findings", new BigDecimal("2000000"), "TZS", false, "assessor-7", null);
        claimsApi.decideSettlement(claimId, false, null, null, "Not covered", null, null, "manager-7");
        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.REJECTED);

        claimsApi.reopenClaim(claimId, "New evidence submitted", "manager-7");

        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.REOPENED);
    }

    @Test
    void reopenWorksFromSettled() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-REOPEN-02");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture, policyNumber, "reg-idem-reopen-02");
        claimsApi.submitAssessment(claimId, "Findings", new BigDecimal("2000000"), "TZS", false, "assessor-8", null);
        claimsApi.decideSettlement(claimId, true, new BigDecimal("2000000"), "TZS", null,
            "payee-ref-7", "settle-idem-reopen-02", "manager-8");
        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.SETTLEMENT_REQUESTED);

        // decideSettlement only reaches SETTLEMENT_REQUESTED (Task 6 owns the payment
        // completion callback that marks SETTLED) -- drive the remaining hop directly on the
        // aggregate via the repository so this test can reach the SETTLED source state without
        // depending on Task 6's payment plumbing.
        settleDirectly(claimId, tenantId);
        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.SETTLED);

        claimsApi.reopenClaim(claimId, "Dispute raised", "manager-8");

        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.REOPENED);
    }

    // ---- Task 9 review fix (Part B): registration idempotency ------------------------------

    @Test
    void registrationRejectsABlankOrMissingIdempotencyKey() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-REGIDEM-BLANK-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        TenantContext.set(tenantId);

        ClaimsApi.RegisterClaimRequest request = deathRequest(policyNumber, fixture.applicantId(), LocalDate.now());
        assertThrows(ClaimValidationException.class, () -> claimsApi.registerClaim(request, null, "claims-staff"));
        assertThrows(ClaimValidationException.class, () -> claimsApi.registerClaim(request, "   ", "claims-staff"));
    }

    /** The exact double-payout path Task 9's review surfaced: two independently-created Claim
     * rows for the same real-world event could each be independently assessed and settled.
     * Repeating the SAME registration idempotency key must return the SAME claim -- not error,
     * not create a second row -- matching this platform's idempotency semantics everywhere else. */
    @Test
    void repeatingTheSameRegistrationIdempotencyKeyReturnsTheSameClaimAndCreatesOnlyOneRow() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-REGIDEM-SAME-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        TenantContext.set(tenantId);

        ClaimsApi.RegisterClaimRequest request = deathRequest(policyNumber, fixture.applicantId(), LocalDate.now().minusDays(1));
        ClaimView first = claimsApi.registerClaim(request, "regidem-same-key-01", "claims-staff");
        ClaimView second = claimsApi.registerClaim(request, "regidem-same-key-01", "claims-staff");

        assertThat(second.claimId()).isEqualTo(first.claimId());
        // Falsifiable: without the dedup fix in ClaimsApiImpl.registerClaim, this repeated call
        // would throw claims/V3's unique-constraint violation as an uncaught 500, or (before V3
        // existed at all) would silently insert a SECOND row -- this asserts there is exactly one.
        assertThat(claimRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber)).hasSize(1);
    }

    /** ONE DEATH CLAIM PER LIFE. This test used to assert the opposite -- two keys, two death
     * claims on one life -- as a deliberate choice. It was the path to a real overpayment: in
     * dev one credit-life borrower carried three approved death claims, TZS 1,640,000 against
     * 800,000 of cover. A different key is still a new attempt; it is just not a second death. */
    @Test
    void aSecondDeathClaimOnTheSameLifeIsRefusedUntilTheFirstIsRejected() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-REGIDEM-DIFF-01");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        TenantContext.set(tenantId);

        ClaimsApi.RegisterClaimRequest request = deathRequest(policyNumber, fixture.applicantId(), LocalDate.now().minusDays(1));
        ClaimView first = claimsApi.registerClaim(request, "regidem-diff-key-A", "claims-staff");

        assertThatThrownBy(() -> claimsApi.registerClaim(request, "regidem-diff-key-B", "claims-staff"))
            .isInstanceOf(ClaimValidationException.class)
            .hasMessageContaining(first.claimId().toString());
        assertThat(claimRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber)).hasSize(1);

        // Once the first is REJECTED it no longer counts: a fresh claim is the other legitimate
        // path after a refusal, beside reopening.
        claimsApi.submitAssessment(first.claimId(), "Findings", new BigDecimal("2000000"), "TZS", false,
            "assessor-dup", null);
        claimsApi.decideSettlement(first.claimId(), false, null, null, "Insufficient evidence",
            null, null, "manager-dup");
        ClaimView second = claimsApi.registerClaim(request, "regidem-diff-key-C", "claims-staff");
        assertThat(second.claimId()).isNotEqualTo(first.claimId());
    }

    /** The backstop at the MONEY step, for pairs registered before the rule above existed --
     * which the dev database really holds. Inserted directly, since registration now refuses. */
    @Test
    void approvingOneOfTwoLiveDeathClaimsOnOneLifeIsRefused() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-IT-DUP-APPROVE");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID first = registerDeathClaim(tenantId, fixture, policyNumber, "reg-idem-dup-approve-1");
        TenantContext.set(tenantId);
        LocalDate dateOfEvent = LocalDate.now().minusDays(1);
        Claim legacyDuplicate = claimRepository.saveAndFlush(new Claim(tenantId, policyNumber, null,
            fixture.applicantId(), ClaimType.DEATH, dateOfEvent,
            new DeathClaimDetails("Natural causes", "Dar es Salaam", dateOfEvent, "Dr. Test"),
            "claims-staff", "reg-idem-dup-approve-2"));

        claimsApi.submitAssessment(legacyDuplicate.getClaimId(), "Findings", new BigDecimal("2000000"), "TZS",
            false, "assessor-dup-2", null);
        assertThatThrownBy(() -> claimsApi.decideSettlement(legacyDuplicate.getClaimId(), true,
            new BigDecimal("2000000"), "TZS", null, null, "payee-ref-dup", "settle-idem-dup", "manager-dup-2"))
            .isInstanceOf(InvalidClaimStateException.class)
            .hasMessageContaining(first.toString());
        assertThat(claimsApi.getClaim(legacyDuplicate.getClaimId()).status()).isEqualTo(ClaimStatus.UNDER_ASSESSMENT);
    }
}
