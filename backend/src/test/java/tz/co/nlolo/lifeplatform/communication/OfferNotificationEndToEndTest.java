package tz.co.nlolo.lifeplatform.communication;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceView;
import tz.co.nlolo.lifeplatform.communication.domain.NotificationDispatch;
import tz.co.nlolo.lifeplatform.communication.infrastructure.NotificationDispatchRepository;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.IssuanceBasis;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.product.api.BenefitType;
import tz.co.nlolo.lifeplatform.product.api.FactorType;
import tz.co.nlolo.lifeplatform.product.api.IfrsMeasurementModel;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;
import tz.co.nlolo.lifeplatform.product.api.ProductSnapshotView;
import tz.co.nlolo.lifeplatform.product.api.ProductSummaryView;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static tz.co.nlolo.lifeplatform.communication.NextSmsStubs.NEXTSMS_ACCEPTED;
import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;
import tz.co.nlolo.lifeplatform.product.api.BenefitCalculationMethod;

/**
 * The whole point of the project, proven through the real chain: a policy is issued through
 * {@code PolicyApi}, the real {@code AFTER_COMMIT} listener reacts to the real event, and a real
 * row appears saying the customer was told.
 *
 * <p>No fabricated envelopes anywhere. A hand-built event would prove this listener parses a
 * payload somebody typed, not that it parses the one the producer actually publishes — which is
 * exactly the drift the platform's own event catalogue has been corrected for twice.
 */
@Testcontainers
// Live against the LOCAL WireMock below, never the real aggregator: sending defaults off,
// so without this the adapter would refuse and every SENT assertion here would fail.
@SpringBootTest(classes = Application.class, properties = "communication.sms-gateway.live=true")
class OfferNotificationEndToEndTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static WireMockServer smsGateway;

    /** The tenant communication/V3 seeds templates for. Must match, or nothing renders. */
    private static final UUID SEEDED_TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("communication.sms-gateway-url", () -> smsGateway.baseUrl());
    }

    @BeforeAll
    static void startEverything() throws Exception {
        smsGateway = new WireMockServer(options().dynamicPort());
        smsGateway.start();
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
            "db-migrations/benefitpayout/V1__create_benefitpayout_schema.sql",
            "db-migrations/accumulation/V1__create_accumulation_schema.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            "db-migrations/refdata/V5__seed_offer_validity.sql",
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
            "db-migrations/billing/V1__create_billing_schema.sql",
            "db-migrations/billing/V2__grants_rls_money_checks_and_notification_columns.sql",
            "db-migrations/billing/V3__amount_paid.sql",
            "db-migrations/billing/V5__single_premium_invoice.sql",
            "db-migrations/billing/V6__premium_credit.sql",
            "db-migrations/billing/V7__policy_inception_invoice.sql",
            "db-migrations/billing/V8__schedule_premium_paying_until.sql",
            "db-migrations/communication/V1__create_communication_schema.sql",
            "db-migrations/communication/V2__template_identity.sql",
            "db-migrations/communication/V3__seed_offer_templates.sql",
            "db-migrations/communication/V4__dispatch_reason_and_policy.sql",
            "db-migrations/communication/V5__dispatch_claimed_status.sql",
            "db-migrations/communication/V6__grants_and_rls.sql",
            "db-migrations/communication/V7__null_safe_rls_and_pending_reminders.sql",
            "db-migrations/communication/V8__platform_default_templates.sql",
            "db-migrations/communication/V9__payment_received_template.sql",
            "db-migrations/communication/V10__account_statement_template.sql",
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql");
    }

    @AfterAll
    static void stopGateway() {
        smsGateway.stop();
    }

    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private BillingApi billingApi;
    @Autowired private NotificationDispatchRepository dispatchRepository;

    @BeforeEach
    void acceptEverySms() {
        smsGateway.resetAll();
        smsGateway.stubFor(post(urlPathEqualTo("/api/sms/v1/text/single"))
            .willReturn(okJson(NEXTSMS_ACCEPTED)));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private record Fixture(UUID applicantId, UUID productId, UUID productVersionId) {}

    private Fixture buildFixture(String tag) {
        TenantContext.set(SEEDED_TENANT);
        PartyView applicant = partyApi.registerIndividual("Notify E2E " + tag, LocalDate.of(1990, 1, 1),
            "+25571400" + String.format("%04d", Math.abs(tag.hashCode() % 10000)), null, "test-staff");
        ProductSummaryView product = productApi.createProduct(tag, "Notify E2E Product",
            ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)), null, ANY_FILING, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        return new Fixture(applicant.partyId(), product.productId(), snapshot.productVersionId());
    }

    private String issueOffer(Fixture fixture, IssuanceBasis basis) {
        TenantContext.set(SEEDED_TENANT);
        return policyApi.issuePolicy(UUID.randomUUID(), new PolicyApi.IssueRequest(
            fixture.applicantId(), fixture.productId(), fixture.productVersionId(),
            new BigDecimal("1000000"), "TZS", new BigDecimal("50000.00"), "TZS", "MONTHLY",
            null, List.of(), "Notification E2E", null, null, null, null, basis), "test-staff").policyNumber();
    }

    private List<NotificationDispatch> messagesAbout(String policyNumber) {
        TenantContext.set(SEEDED_TENANT);
        return dispatchRepository.findByTenantIdAndPolicyNumberOrderByCreatedAtDesc(SEEDED_TENANT, policyNumber);
    }

    @Test
    void issuingAnOfferTellsTheCustomerItExistsAndWhenItCloses() {
        String policyNumber = issueOffer(buildFixture("NOTIFY-OFFER-01"), null);

        assertThat(messagesAbout(policyNumber))
            .singleElement()
            .satisfies(dispatch -> {
                assertThat(dispatch.getTemplateKey()).isEqualTo("OFFER_MADE");
                assertThat(dispatch.getStatus()).isEqualTo("SENT");
                assertThat(dispatch.getChannel()).isEqualTo("SMS");
            });

        // The body actually sent, read off the wire. The dispatch row proves an attempt was
        // recorded; only the request proves the customer was told the right thing -- a template
        // that rendered the wrong deadline would satisfy every assertion above.
        String sent = smsGateway.getAllServeEvents().get(0).getRequest().getBodyAsString();
        assertThat(sent).contains(policyNumber);
        assertThat(sent).contains("TZS 50,000.00");
        assertThat(sent)
            .as("the deadline is the whole reason this message exists")
            .contains(LocalDate.now().plusDays(30).toString());
    }

    /**
     * The case that would have been a lie.
     *
     * <p>A MIGRATION issuance publishes PolicyIssued and PolicyActivated together, because the
     * contract is already in force elsewhere. Telling that customer to "pay by DATE to start your
     * cover" would be false — they are insured now. This is why PolicyIssued had to start
     * carrying its status: communication cannot ask policy which kind of issuance it is seeing.
     */
    @Test
    void aMigrationIsToldItsCoverStartedAndNeverToldToPayByADate() {
        String policyNumber = issueOffer(buildFixture("NOTIFY-MIGRATION-01"), IssuanceBasis.MIGRATION);

        assertThat(messagesAbout(policyNumber))
            .extracting(NotificationDispatch::getTemplateKey)
            .containsExactly("COVER_STARTED")
            .doesNotContain("OFFER_MADE");
    }

    @Test
    void payingTheFirstPremiumTellsTheCustomerTheyAreCovered() {
        String policyNumber = issueOffer(buildFixture("NOTIFY-COVER-01"), null);

        TenantContext.set(SEEDED_TENANT);
        policyApi.activateOnFirstPremium(policyNumber);

        assertThat(messagesAbout(policyNumber))
            .extracting(NotificationDispatch::getTemplateKey)
            .containsExactlyInAnyOrder("OFFER_MADE", "COVER_STARTED");
    }

    /**
     * Every premium is acknowledged, not just the one that starts cover.
     *
     * <p>The gap this closes was found by paying a real invoice twice: a customer's SECOND monthly
     * payment produced no message at all. Correct behaviour from activation's point of view — it
     * is silent on an already-ACTIVE policy so commission is not double-accrued and risk not
     * double-ceded — and a hole beside it, because the money arriving is a fact the customer is
     * owed either way.
     */
    @Test
    void everyCollectedPremiumIsAcknowledged() {
        Fixture fixture = buildFixture("NOTIFY-RECEIPT-01");
        String policyNumber = issueOffer(fixture, null);

        TenantContext.set(SEEDED_TENANT);
        InvoiceView first = billingApi.getNextDueInvoice(policyNumber);
        billingApi.applyConfirmedPayment(first.invoiceId(), new BigDecimal("50000.00"), "TZS", "ref-1");

        // The first payment says both things: the money arrived, and cover has started. Two true
        // and different facts -- collapsing them would lose the half that matters every month
        // after this one.
        assertThat(messagesAbout(policyNumber))
            .extracting(NotificationDispatch::getTemplateKey)
            .contains("PAYMENT_RECEIVED", "COVER_STARTED");

        TenantContext.set(SEEDED_TENANT);
        InvoiceView second = billingApi.getNextDueInvoice(policyNumber);
        billingApi.applyConfirmedPayment(second.invoiceId(), new BigDecimal("50000.00"), "TZS", "ref-2");

        // The second says only the first of them -- and before this listener existed, said nothing.
        assertThat(messagesAbout(policyNumber))
            .filteredOn(d -> "PAYMENT_RECEIVED".equals(d.getTemplateKey()))
            .as("a customer paying their second month must still hear that it arrived")
            .hasSizeGreaterThanOrEqualTo(2);
        assertThat(messagesAbout(policyNumber))
            .filteredOn(d -> "COVER_STARTED".equals(d.getTemplateKey()) && "SMS".equals(d.getChannel()))
            .as("cover starts once; the second payment must not claim it started again")
            .hasSize(1);
    }

    @Test
    void anExpiredOfferTellsTheCustomerTheyAreNotCovered() {
        String policyNumber = issueOffer(buildFixture("NOTIFY-EXPIRED-01"), null);

        TenantContext.set(SEEDED_TENANT);
        policyApi.expireOffer(policyNumber);

        assertThat(messagesAbout(policyNumber))
            .extracting(NotificationDispatch::getTemplateKey)
            .containsExactlyInAnyOrder("OFFER_MADE", "OFFER_EXPIRED");
    }

    /**
     * A customer is never told both that their cover started and that their offer expired.
     *
     * <p>Not a hypothetical: expireOffer races the customer's own payment, and the guard that
     * makes it silent on an already-ACTIVE policy is what keeps that race from producing two
     * contradictory messages about whether somebody is insured.
     */
    @Test
    void aPaidPolicyIsNeverAlsoToldItsOfferExpired() {
        String policyNumber = issueOffer(buildFixture("NOTIFY-RACE-01"), null);

        TenantContext.set(SEEDED_TENANT);
        policyApi.activateOnFirstPremium(policyNumber);
        policyApi.expireOffer(policyNumber);

        assertThat(messagesAbout(policyNumber))
            .extracting(NotificationDispatch::getTemplateKey)
            .doesNotContain("OFFER_EXPIRED");
    }
}
