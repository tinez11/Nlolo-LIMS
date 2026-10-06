package tz.co.nlolo.lifeplatform.benefitpayout;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.benefitpayout.api.*;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyStatus;
import tz.co.nlolo.lifeplatform.product.api.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The customer changing their mind inside the free-look window (guide §21.3).
 *
 * <p>Through the real APIs and the real disbursement rail -- an in-process WireMock stand-in, the
 * shape {@code PayoutPaymentEndToEndTest} uses -- so an approved cancellation is followed all the
 * way to a refund the rail confirms.
 *
 * <p><b>A death during the free-look window is CLAIMED, not cancelled.</b> {@code wasOnRiskOn}
 * answers false only once the policy has been cancelled, so a claim registered before that is
 * assessed against an ACTIVE policy in the ordinary way. There is no claims query this module is
 * allowed to make, and inventing one to pre-empt the case would be the wrong dependency for a
 * situation the claims path already handles correctly.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(PayoutTestFixtures.class)
class FreeLookIntegrationTest {

    private static final UUID TENANT = UUID.randomUUID();

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static WireMockServer wireMock;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @DynamicPropertySource
    static void mobileMoneyProperties(DynamicPropertyRegistry registry) {
        registry.add("mobile-money.base-url", () -> wireMock.baseUrl());
    }

    @BeforeAll
    static void startGatewayAndApplyMigrations() throws Exception {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
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
            "db-migrations/product/V15__exclusion_periods.sql",
            "db-migrations/product/V16__base_rate_term_bands.sql",
            "db-migrations/product/V17__cash_value.sql",
            "db-migrations/product/V18__payout_schedule.sql",
            "db-migrations/product/V19__accumulation_terms.sql",
            "db-migrations/product/V20__deposit_rate_grid.sql",
            "db-migrations/product/V21__bonus_terms.sql",
            "db-migrations/product/V27__ifrs17_classification.sql",
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
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V8__ifrs17_branches_and_channels.sql",
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
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql",
            "db-migrations/payment/V1__create_payment_schema.sql",
            "db-migrations/payment/V2__grants_rls_money_checks_version_and_tenant_scoped_registries.sql",
            "db-migrations/payment/V6__disbursement_method.sql",
            "db-migrations/payment/V7__q4_2026_partitions.sql",
            "db-migrations/payment/V8__benefit_payout_purposes.sql",
            "db-migrations/payment/V9__account_purposes.sql");
    }

    @AfterAll
    static void stopGateway() {
        wireMock.stop();
    }

    @BeforeEach
    void gatewayAccepts() {
        wireMock.resetAll();
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-FREELOOK-OK\"}")));
    }

    @Autowired private BenefitPayoutApi api;
    @Autowired private PayoutTestFixtures fixtures;
    @Autowired private PolicyApi policyApi;
    @Autowired private JdbcTemplate jdbcTemplate;

    /** A 15-day free-look window, and a maturity the endowment validator insists on. */
    private static final PayoutPlan ENDOWMENT_FREE_LOOK_15 = PayoutPlan.authored(
        new PayoutTerms(15, null, null, null),
        List.of(new PayoutRowInput(PayoutKind.MATURITY, null, null, PayoutAmountBasis.PERCENT_OF_SA,
            new BigDecimal("100"), null)));

    private <T> T asTenant(Supplier<T> work) {
        TenantContext.set(TENANT);
        try {
            return work.get();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void anApprovedFreeLookVoidsTheContractAndRefundsThePremiumsLessDeductions() {
        String policyNumber = fixtures.issueEndowment(TENANT, ENDOWMENT_FREE_LOOK_15,
            new BigDecimal("500000.00"), 120);
        fixtures.collectPremium(TENANT, policyNumber, new BigDecimal("50000.00"), LocalDate.now());

        FreeLookCancellationView requested = asTenant(() -> api.requestFreeLook(policyNumber, "+255700000009",
            List.of(new FreeLookDeductionInput("Medical examination", new BigDecimal("8000.00"), null)), "csr-1"));
        assertThat(requested.status()).isEqualTo("REQUESTED");
        assertThat(requested.premiumsCollected()).isEqualByComparingTo("50000.00");
        assertThat(requested.refundAmount()).isEqualByComparingTo("42000.00");
        // Itemised, not a percentage: the customer is told what each withheld shilling was for.
        assertThat(requested.deductions()).singleElement()
            .satisfies(d -> assertThat(d.description()).isEqualTo("Medical examination"));

        // Preparing the figures is not releasing them.
        assertThat(asTenant(() -> policyApi.getPolicy(policyNumber)).status()).isEqualTo(PolicyStatus.ACTIVE);
        assertThatThrownBy(() -> asTenant(() -> api.approveFreeLook(requested.cancellationId(), "csr-1")))
            .isInstanceOf(PayoutStateException.class)
            .hasMessageContaining("other than the person who requested it (csr-1)");

        asTenant(() -> api.approveFreeLook(requested.cancellationId(), "fin-2"));

        // Voided from inception, not surrendered.
        assertThat(asTenant(() -> policyApi.getPolicy(policyNumber)).status())
            .isEqualTo(PolicyStatus.CANCELLED_FREE_LOOK);
        // The whole chain is synchronous and same-thread, so the rail has already confirmed.
        assertThat(asTenant(() -> api.findFreeLook(policyNumber)).orElseThrow().status()).isEqualTo("PAID");
        wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo("/disburse")));
        // And nothing is owed under a contract that never existed.
        assertThat(asTenant(() -> api.listForPolicy(policyNumber)))
            .isNotEmpty()
            .allSatisfy(v -> {
                assertThat(v.status()).isEqualTo(InstalmentStatus.CANCELLED);
                assertThat(v.statusReason()).isEqualTo("Cancelled in free-look");
            });
    }

    @Test
    void aRefundOfNothingRequestsNoDisbursement() {
        String policyNumber = fixtures.issueEndowment(TENANT, ENDOWMENT_FREE_LOOK_15,
            new BigDecimal("500000.00"), 120);
        fixtures.collectPremium(TENANT, policyNumber, new BigDecimal("8000.00"), LocalDate.now());

        FreeLookCancellationView requested = asTenant(() -> api.requestFreeLook(policyNumber, "+255700000009",
            List.of(new FreeLookDeductionInput("Medical examination", new BigDecimal("8000.00"), null)), "csr-1"));
        assertThat(requested.refundAmount()).isEqualByComparingTo("0.00");

        asTenant(() -> api.approveFreeLook(requested.cancellationId(), "fin-2"));

        // The policy is still cancelled. What does NOT happen is a zero payment to the rail, which
        // nobody could reconcile and which the cancellation would then wait on forever.
        assertThat(asTenant(() -> policyApi.getPolicy(policyNumber)).status())
            .isEqualTo(PolicyStatus.CANCELLED_FREE_LOOK);
        assertThat(asTenant(() -> api.findFreeLook(policyNumber)).orElseThrow().status()).isEqualTo("APPROVED");
        wireMock.verify(exactly(0), postRequestedFor(urlPathEqualTo("/disburse")));
    }

    @Test
    void aSecondCancellationOnTheSamePolicyIsRefusedRatherThanCrashing() {
        String policyNumber = fixtures.issueEndowment(TENANT, ENDOWMENT_FREE_LOOK_15,
            new BigDecimal("500000.00"), 120);
        fixtures.collectPremium(TENANT, policyNumber, new BigDecimal("50000.00"), LocalDate.now());
        asTenant(() -> api.requestFreeLook(policyNumber, "+255700000009", List.of(), "csr-1"));

        // ux_free_look_live would refuse the insert anyway, but a unique-index violation raises
        // DataIntegrityViolationException, which no handler maps -- so the slow second click of a
        // button would be a 500. The service answers instead.
        assertThatThrownBy(() -> asTenant(() ->
                api.requestFreeLook(policyNumber, "+255700000009", List.of(), "csr-2")))
            .isInstanceOf(PayoutStateException.class)
            .hasMessage("A free-look cancellation is already requested on policy " + policyNumber);
    }

    @Test
    void outsideTheWindowIsRefused() {
        String policyNumber = fixtures.issueEndowment(TENANT, ENDOWMENT_FREE_LOOK_15,
            new BigDecimal("500000.00"), 120);
        // Issued 16 days ago against a 15-day window. The test connects as the table owner, so RLS
        // does not apply -- the same device PolicyApiIntegrationTest.seedCashValue uses.
        jdbcTemplate.update("UPDATE policy.policy SET issue_date = current_date - 16 WHERE policy_number = ?",
            policyNumber);

        assertThatThrownBy(() -> asTenant(() -> api.requestFreeLook(policyNumber, "+255700000009", List.of(), "csr-1")))
            .isInstanceOf(PayoutStateException.class)
            .hasMessage("Policy " + policyNumber + "'s free-look period ended on " + LocalDate.now().minusDays(1));
    }

    @Test
    void aDeductionWithoutADescriptionOrAnAmountIsRefused() {
        String policyNumber = fixtures.issueEndowment(TENANT, ENDOWMENT_FREE_LOOK_15,
            new BigDecimal("500000.00"), 120);
        fixtures.collectPremium(TENANT, policyNumber, new BigDecimal("50000.00"), LocalDate.now());

        assertThatThrownBy(() -> asTenant(() -> api.requestFreeLook(policyNumber, "+255700000009",
                List.of(new FreeLookDeductionInput("  ", new BigDecimal("8000.00"), null)), "csr-1")))
            .isInstanceOf(PayoutStateException.class)
            .hasMessage("Every deduction needs a description and an amount greater than zero");
    }

    @Test
    void aSchemeIsNotCancelledUnderAnIndividualBuyersWindow() {
        // GROUP_LIFE carries no payout schedule at all, so it reaches the category gate first --
        // which is the rule being asserted: free-look is an individual buyer's right.
        String schemeNumber = fixtures.issue(TENANT, ProductCategory.GROUP_LIFE, PayoutPlan.none(),
            new BigDecimal("500000.00"), 12, LocalDate.now(), "MONTHLY");

        assertThatThrownBy(() -> asTenant(() -> api.requestFreeLook(schemeNumber, "+255700000009", List.of(), "csr-1")))
            .isInstanceOf(PayoutStateException.class)
            .hasMessageContaining("a GROUP_LIFE scheme is cancelled under its contract");
    }
}
