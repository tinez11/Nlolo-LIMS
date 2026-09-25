package tz.co.nlolo.lifeplatform.regreporting;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimType;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.claims.api.DeathClaimDetails;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.product.api.BenefitType;
import tz.co.nlolo.lifeplatform.product.api.FactorType;
import tz.co.nlolo.lifeplatform.product.api.IfrsMeasurementModel;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;
import tz.co.nlolo.lifeplatform.product.api.ProductSnapshotView;
import tz.co.nlolo.lifeplatform.product.api.ProductSummaryView;
import tz.co.nlolo.lifeplatform.regreporting.api.RegreportingApi;
import tz.co.nlolo.lifeplatform.regreporting.api.RegulatoryReturnView;
import tz.co.nlolo.lifeplatform.regreporting.api.ReturnLineView;
import tz.co.nlolo.lifeplatform.regreporting.domain.ClaimDimension;
import tz.co.nlolo.lifeplatform.regreporting.domain.ClaimsMovement;
import tz.co.nlolo.lifeplatform.regreporting.domain.PolicyDimension;
import tz.co.nlolo.lifeplatform.regreporting.domain.PolicyMovement;
import tz.co.nlolo.lifeplatform.regreporting.domain.PremiumMovement;
import tz.co.nlolo.lifeplatform.regreporting.domain.ReinsuranceMovement;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.ClaimDimensionRepository;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.ClaimsMovementRepository;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.PolicyDimensionRepository;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.PolicyMovementRepository;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.PremiumMovementRepository;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.ReinsuranceMovementRepository;
import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceApi;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyType;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;
import tz.co.nlolo.lifeplatform.product.api.BenefitCalculationMethod;

/**
 * Task 6, Step 4 -- the real end-to-end proof that Task 6's four listeners actually wire real
 * business events into the star schema Tasks 3-5 built, driving REAL APIs rather than hand-published
 * envelopes: {@code PolicyApi.issuePolicy} (real) -> {@code policy.PolicyIssued} (real event) ->
 * {@code PolicyEventListener} writes {@code policy_dimension}/{@code policy_movement}, AND (real,
 * automatic, via {@code reinsurance}'s OWN {@code PolicyEventListener} -- never hand-invoked here)
 * a cession under a real 50% QUOTA_SHARE treaty -> {@code reinsurance.CessionRecorded} ->
 * {@code ReinsuranceEventListener} writes {@code reinsurance_movement}. {@code
 * BillingApi.applyConfirmedPayment} (real) -> {@code billing.PremiumCollected} -> {@code
 * BillingEventListener} writes {@code premium_movement}. {@code ClaimsApi.registerClaim} /
 * {@code submitAssessment} / {@code decideSettlement} (real) drive a DEATH claim through the real
 * payment request/confirm loop (in-process WireMock, copying {@code
 * ClaimSettlementEndToEndTest}'s exact harness) all the way to {@code claims.ClaimSettled} -> {@code
 * ClaimsEventListener} writes {@code claims_movement}, and {@code policy.PolicySurrendered} (the
 * settled-claim policy closure) -> {@code PolicyEventListener} applies {@code
 * applyClaimTerminated} to the SAME {@code policy_movement} row the issuance wrote.
 *
 * <p>A second, untouched policy is issued purely so POLICIES_IN_FORCE has something left to report
 * non-zero: the SAME policy that is issued and then claim-terminated within this one test nets to
 * ZERO in-force on its own (1 issued - 1 claim-terminated), which would make the brief's own
 * "non-zero POLICIES_IN_FORCE" assertion pass vacuously if this were the only policy in play.
 *
 * <p>Runs against real Postgres as {@code app_role} (NOSUPERUSER NOBYPASSRLS). The migration list is
 * the union every module driven here needs, copied field-for-field from the Task 6 brief. Uses the
 * well-known SEEDED_TENANT ({@code 11111111-1111-1111-1111-111111111111}) because {@code
 * QUARTERLY_PRUDENTIAL} is seeded (regreporting/V2 section 9) for exactly that tenant, and no other
 * test in this suite shares this class's own, freshly-provisioned container.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class ProjectionEndToEndTest {

    private static final String APP_ROLE_PASSWORD = "regreporting_projection_e2e_password";
    private static final String CURRENCY = "TZS";
    private static final UUID SEEDED_TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static WireMockServer wireMock;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "app_role");
        registry.add("spring.datasource.password", () -> APP_ROLE_PASSWORD);
    }

    @DynamicPropertySource
    static void mobileMoneyProperties(DynamicPropertyRegistry registry) {
        registry.add("mobile-money.base-url", () -> wireMock.baseUrl());
    }

    @BeforeAll
    static void startGatewayAndApplyMigrations() throws Exception {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        // Union of every module this end-to-end test drives -- copied field-for-field from the
        // Task 6 brief's own migration list.
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            "db-migrations/refdata/V4__seed_distribution_parameters.sql",
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
            "db-migrations/billing/V1__create_billing_schema.sql",
            "db-migrations/billing/V2__grants_rls_money_checks_and_notification_columns.sql",
            "db-migrations/billing/V3__amount_paid.sql",
            "db-migrations/billing/V5__single_premium_invoice.sql",
            "db-migrations/billing/V6__premium_credit.sql",
            "db-migrations/claims/V1__create_claims_schema.sql",
            "db-migrations/claims/V2__grants_rls_money_checks_evidence_and_settlement_columns.sql",
            "db-migrations/claims/V3__registration_idempotency_key.sql",
            "db-migrations/claims/V5__claim_policy_member.sql",
            "db-migrations/claims/V6__exclusion_decline.sql",
            "db-migrations/claims/V7__claim_assessment_assessor_name.sql",
            "db-migrations/claims/V8__claim_evidence_uploaded_by_name.sql",
            "db-migrations/payment/V1__create_payment_schema.sql",
            "db-migrations/payment/V2__grants_rls_money_checks_version_and_tenant_scoped_registries.sql",
            "db-migrations/payment/V3__inbound_callback_tenant_resolver.sql",
            "db-migrations/payment/V4__in_doubt_status_and_id_based_callback_resolvers.sql",
            "db-migrations/payment/V6__disbursement_method.sql",
            "db-migrations/reinsurance/V1__create_reinsurance_schema.sql",
            "db-migrations/reinsurance/V2__grants_rls_money_checks_reinsurer_and_projection.sql",
            "db-migrations/reinsurance/V4__projection_product_category.sql",
            "db-migrations/regreporting/V1__create_regreporting_schema.sql",
            "db-migrations/regreporting/V2__grants_rls_dimensions_movements_and_return_lines.sql",
            "db-migrations/regreporting/V3__optimistic_locking_on_movement_tables.sql",
            "db-migrations/regreporting/V5__member_movement_columns.sql");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"
                + APP_ROLE_PASSWORD + "'");
        }
    }

    @AfterAll
    static void stopGateway() { wireMock.stop(); }

    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private BillingApi billingApi;
    @Autowired private ClaimsApi claimsApi;
    @Autowired private ReinsuranceApi reinsuranceApi;
    @Autowired private RegreportingApi regreportingApi;
    @Autowired private PolicyDimensionRepository policyDimensionRepository;
    @Autowired private PolicyMovementRepository policyMovementRepository;
    @Autowired private ClaimDimensionRepository claimDimensionRepository;
    @Autowired private ClaimsMovementRepository claimsMovementRepository;
    @Autowired private PremiumMovementRepository premiumMovementRepository;
    @Autowired private ReinsuranceMovementRepository reinsuranceMovementRepository;

    @AfterEach
    void resetAfterEach() {
        TenantContext.clear();
        wireMock.resetAll();
    }

    private record Fixture(UUID applicantId, UUID productId, UUID productVersionId) {}

    /** Mirrors ClaimSettlementEndToEndTest/ReinsuranceAndLoanPostingEndToEndTest.buildFixture. */
    private Fixture buildFixture(String productCode) {
        TenantContext.set(SEEDED_TENANT);
        PartyView applicant = partyApi.registerIndividual("Projection E2E Applicant " + productCode,
            LocalDate.of(1985, 3, 1), "+25571900" + String.format("%04d", Math.abs(productCode.hashCode() % 10000)),
            null, "test-agent");
        ProductSummaryView product = productApi.createProduct(productCode, "Projection E2E Product " + productCode,
            ProductCategory.TERM_LIFE, CURRENCY, "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        return new Fixture(applicant.partyId(), product.productId(), snapshot.productVersionId());
    }

    private void createQuotaShareTreaty(BigDecimal cessionPercent) {
        TenantContext.set(SEEDED_TENANT);
        reinsuranceApi.createTreaty(new ReinsuranceApi.CreateTreatyRequest(
            "Africa Re", TreatyType.QUOTA_SHARE, new BigDecimal("0.00"), CURRENCY, cessionPercent,
            LocalDate.now().minusMonths(1), null), "finance-officer");
    }

    /** ANNUALLY, deliberately -- billing's 12-month look-ahead schedule produces exactly one
     * invoice for this frequency (PremiumPostingEndToEndTest's own class javadoc). */
    private String issueAnnualPolicy(Fixture fixture, BigDecimal sumAssured, BigDecimal premium) {
        String policyNumber = issueAnnualOffer(fixture, sumAssured, premium);
        // New business is counted when the platform goes on risk, which is when the first
        // premium clears -- not when the offer was made.
        TenantContext.set(SEEDED_TENANT);
        policyApi.activateOnFirstPremium(policyNumber);
        return policyNumber;
    }

    /**
     * An offer: issued, but unpaid, so nothing to report to the regulator yet.
     *
     * <p>Split out of {@link #issueAnnualPolicy} when the new-business count moved from
     * {@code PolicyIssued} to {@code PolicyActivated}.
     */
    private String issueAnnualOffer(Fixture fixture, BigDecimal sumAssured, BigDecimal premium) {
        TenantContext.set(SEEDED_TENANT);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(),
            fixture.productVersionId(), sumAssured, CURRENCY, premium, CURRENCY, "ANNUALLY", null, List.of(),
            "Projection E2E test");
        return policyApi.issuePolicy(null, request, "test-staff").policyNumber();
    }

    private UUID registerAndAssessDeathClaim(Fixture fixture, String policyNumber, String regKey, String assessor) {
        TenantContext.set(SEEDED_TENANT);
        ClaimsApi.RegisterClaimRequest request = new ClaimsApi.RegisterClaimRequest(policyNumber, null, fixture.applicantId(),
            ClaimType.DEATH, LocalDate.now().minusDays(1),
            new DeathClaimDetails("Natural causes", "Dar es Salaam", LocalDate.now().minusDays(1), "Dr. Test"));
        UUID claimId = claimsApi.registerClaim(request, regKey, "claims-staff").claimId();
        claimsApi.submitAssessment(claimId, "Consistent with cause of death", new BigDecimal("2000000"), CURRENCY, false, assessor, null);
        return claimId;
    }

    /** Same UTC quarter arithmetic as {@code ProjectionSupport}, duplicated here for the same
     * reason {@code MissingDimensionTest} duplicates it: this test exercises the listeners' real
     * period derivation rather than reaching into their package-private helper. */
    private static String currentQuarterUtc() {
        LocalDate date = Instant.now().atZone(ZoneOffset.UTC).toLocalDate();
        int quarter = (date.getMonthValue() - 1) / 3 + 1;
        return date.getYear() + "-Q" + quarter;
    }

    /**
     * The other half of the new-business count: an unpaid offer is not new business.
     *
     * <p>Around 15% of accepted proposals are never taken up, so counting them at issuance would
     * report contracts to the regulator that the platform is not on risk for, then need a
     * correction when the offer expires. Neither the dimension nor the movement should exist yet.
     */
    @Test
    void anOfferNobodyHasPaidForIsNotCountedAsNewBusiness() {
        Fixture fixture = buildFixture("PROJECTION-E2E-OFFER");

        String policyNumber = issueAnnualOffer(fixture, new BigDecimal("500000"), new BigDecimal("30000.00"));

        TenantContext.set(SEEDED_TENANT);
        assertThat(policyDimensionRepository.findByTenantIdAndPolicyNumber(SEEDED_TENANT, policyNumber))
            .as("a policy that is only an offer has no place in the regulatory dimension yet")
            .isEmpty();
    }

    @Test
    void realApiChainsPopulateAllSixFactAndDimensionTablesAndAReturnReportsNonZeroLines() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-PROJECTION-E2E\"}")));

        String period = currentQuarterUtc();
        BigDecimal sumAssured = new BigDecimal("2000000");
        BigDecimal premium = new BigDecimal("120000.00");

        // ---- A second, untouched policy: keeps POLICIES_IN_FORCE non-zero in aggregate -- see
        // class javadoc for why the primary policy alone would net to zero. ----
        Fixture secondFixture = buildFixture("PROJECTION-E2E-INFORCE");
        String secondPolicyNumber = issueAnnualPolicy(secondFixture, new BigDecimal("500000"), new BigDecimal("30000.00"));

        // ---- The primary chain: issue under a real 50% QUOTA_SHARE treaty. ----
        createQuotaShareTreaty(new BigDecimal("50.00"));
        Fixture fixture = buildFixture("PROJECTION-E2E-PRIMARY");
        String policyNumber = issueAnnualPolicy(fixture, sumAssured, premium);

        // ---- Assertion 1: policy_dimension, written by the real PolicyIssued. ----
        TenantContext.set(SEEDED_TENANT);
        PolicyDimension dimension = policyDimensionRepository.findByTenantIdAndPolicyNumber(SEEDED_TENANT, policyNumber)
            .orElseThrow(() -> new AssertionError("Expected a policy_dimension row for " + policyNumber));
        assertThat(dimension.getProductId()).isEqualTo(fixture.productId());
        assertThat(dimension.getSumAssuredAmount()).isEqualByComparingTo(sumAssured);

        // ---- Assertion 2: reinsurance_movement, written by the real CessionRecorded -- reinsurance
        // ceded this off its OWN PolicyIssued listener; nothing here invoked reinsurance directly. ----
        TenantContext.set(SEEDED_TENANT);
        ReinsuranceMovement reinsuranceMovement = reinsuranceMovementRepository.findByTenantIdAndPeriod(SEEDED_TENANT, period)
            .orElseThrow(() -> new AssertionError("Expected a reinsurance_movement row for " + period));
        assertThat(reinsuranceMovement.getCededRiskAmount()).isEqualByComparingTo("1000000.00");

        // ---- Assertion 3: premium_movement, written by the real PremiumCollected. ----
        TenantContext.set(SEEDED_TENANT);
        InvoiceView invoice = billingApi.getNextDueInvoice(policyNumber);
        assertThat(invoice).as("billing must have generated exactly one invoice for an ANNUALLY policy").isNotNull();
        TenantContext.set(SEEDED_TENANT);
        billingApi.applyConfirmedPayment(invoice.invoiceId(), premium, CURRENCY, "projection-e2e-payment-ref");

        TenantContext.set(SEEDED_TENANT);
        PremiumMovement premiumMovement = premiumMovementRepository
            .findByTenantIdAndPeriodAndProductId(SEEDED_TENANT, period, fixture.productId())
            .orElseThrow(() -> new AssertionError("Expected a premium_movement row for " + fixture.productId()));
        assertThat(premiumMovement.getCollectedAmount()).isEqualByComparingTo(premium);

        // ---- The claim chain: register, assess, approve (real API), settle through the real
        // payment request/confirm loop (WireMock stand-in), which also closes the policy. ----
        UUID claimId = registerAndAssessDeathClaim(fixture, policyNumber, "projection-e2e-reg-01", "assessor-projection-01");
        String settleKey = "projection-e2e-settle-" + claimId;
        TenantContext.set(SEEDED_TENANT);
        claimsApi.decideSettlement(claimId, true, sumAssured, CURRENCY, null,
            "MPESA-0719000001", settleKey, "manager-projection-01");

        // ---- Assertion 4: claim_dimension, written by the real ClaimRegistered. ----
        TenantContext.set(SEEDED_TENANT);
        ClaimDimension claimDimension = claimDimensionRepository.findByTenantIdAndClaimId(SEEDED_TENANT, claimId)
            .orElseThrow(() -> new AssertionError("Expected a claim_dimension row for " + claimId));
        assertThat(claimDimension.getClaimType()).isEqualTo("DEATH");
        assertThat(claimDimension.getPolicyNumber()).isEqualTo(policyNumber);

        // ---- Assertion 5: claims_movement, written by the real Registered/Approved/Settled. ----
        TenantContext.set(SEEDED_TENANT);
        ClaimsMovement claimsMovement = claimsMovementRepository
            .findByTenantIdAndPeriodAndClaimType(SEEDED_TENANT, period, "DEATH")
            .orElseThrow(() -> new AssertionError("Expected a DEATH claims_movement row for " + period));
        assertThat(claimsMovement.getRegisteredCount()).isEqualTo(1);
        assertThat(claimsMovement.getApprovedCount()).isEqualTo(1);
        assertThat(claimsMovement.getApprovedAmount()).isEqualByComparingTo(sumAssured);
        assertThat(claimsMovement.getSettledCount()).isEqualTo(1);
        assertThat(claimsMovement.getSettledAmount()).isEqualByComparingTo(sumAssured);

        // ---- Assertion 6: policy_movement -- issued by PolicyIssued AND claim-terminated by the
        // real PolicySurrendered the settlement caused (policy.PolicyApiImpl.terminateForSettledClaim,
        // fired via claims.ClaimSettled -> payment -> ... -> policy). ----
        TenantContext.set(SEEDED_TENANT);
        PolicyMovement policyMovement = policyMovementRepository
            .findByTenantIdAndPeriodAndProductId(SEEDED_TENANT, period, fixture.productId())
            .orElseThrow(() -> new AssertionError("Expected a policy_movement row for " + fixture.productId()));
        assertThat(policyMovement.getPoliciesIssued()).isEqualTo(1);
        assertThat(policyMovement.getSumAssuredIssued()).isEqualByComparingTo(sumAssured);
        assertThat(policyMovement.getPoliciesClaimTerminated()).isEqualTo(1);
        assertThat(policyMovement.getSumAssuredTerminated()).isEqualByComparingTo(sumAssured);

        wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo("/disburse")));

        // ---- Assertion 7: generating a return reports non-zero for all three brief-named lines. ----
        TenantContext.set(SEEDED_TENANT);
        RegulatoryReturnView generated = regreportingApi.generateReturn("QUARTERLY_PRUDENTIAL", period, "projection-e2e-tester");
        assertThat(generated.lines()).hasSize(10);

        ReturnLineView policiesInForce = lineFor(generated, "POLICIES_IN_FORCE");
        ReturnLineView premiumCollected = lineFor(generated, "PREMIUM_COLLECTED");
        ReturnLineView claimsSettled = lineFor(generated, "CLAIMS_SETTLED");

        // POLICIES_IN_FORCE nets the primary policy's own issue/claim-terminate pair to zero; the
        // untouched second policy is what keeps the AGGREGATE genuinely non-zero -- see class javadoc.
        assertThat(policiesInForce.numericValue())
            .as("POLICIES_IN_FORCE must be non-zero: the untouched second policy stays in force")
            .isNotEqualByComparingTo(BigDecimal.ZERO);
        assertThat(premiumCollected.numericValue())
            .as("PREMIUM_COLLECTED must be non-zero")
            .isNotEqualByComparingTo(BigDecimal.ZERO);
        assertThat(claimsSettled.numericValue())
            .as("CLAIMS_SETTLED must be non-zero")
            .isNotEqualByComparingTo(BigDecimal.ZERO);
    }

    private static ReturnLineView lineFor(RegulatoryReturnView view, String metricName) {
        return view.lines().stream().filter(l -> metricName.equals(l.metricName())).findFirst()
            .orElseThrow(() -> new AssertionError("Expected a " + metricName + " line on the generated return"));
    }
}
