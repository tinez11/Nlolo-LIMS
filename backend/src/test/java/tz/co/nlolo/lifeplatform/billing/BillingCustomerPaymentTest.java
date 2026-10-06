package tz.co.nlolo.lifeplatform.billing;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M12 Task 2: POST /invoices/{invoiceId}/payment-request becomes customer-reachable, gated on the
 * invoice's own policy. The cross-party test is the load-bearing one: without the ownership check
 * any authenticated customer could trigger a collection against a stranger's invoice, and the
 * endpoint takes no policyNumber, so the check depends entirely on resolving invoice -> policy.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class BillingCustomerPaymentTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
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
            "db-migrations/product/V15__exclusion_periods.sql",
            "db-migrations/product/V16__base_rate_term_bands.sql",
            "db-migrations/product/V17__cash_value.sql",
            "db-migrations/product/V18__payout_schedule.sql",
            "db-migrations/product/V19__accumulation_terms.sql",
            "db-migrations/product/V20__deposit_rate_grid.sql",
            "db-migrations/product/V21__bonus_terms.sql",
            "db-migrations/product/V27__ifrs17_classification.sql",
            "db-migrations/product/V28__survival_investment_component.sql",
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
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
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
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            "db-migrations/refdata/V8__ifrs17_branches_and_channels.sql",
            "db-migrations/policyloan/V1__create_policyloan_schema.sql",
            "db-migrations/policyloan/V3__money_check_constraints.sql",
            "db-migrations/policyloan/V7__q4_2026_partitions.sql",
            "db-migrations/policyloan/V8__interest_month_published.sql",
            "db-migrations/billing/V1__create_billing_schema.sql",
            "db-migrations/billing/V2__grants_rls_money_checks_and_notification_columns.sql",
            "db-migrations/billing/V3__amount_paid.sql",
            "db-migrations/billing/V5__single_premium_invoice.sql",
            "db-migrations/billing/V6__premium_credit.sql",
            "db-migrations/billing/V7__policy_inception_invoice.sql",
            "db-migrations/billing/V8__schedule_premium_paying_until.sql",
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql");
    }

    @Autowired
    MockMvc mockMvc;

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String BODY = "{\"payerRef\":\"255700000001\"}";

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @Test
    void customerRealmIsNoLongerRejectedOutright() throws Exception {
        // A nonexistent invoice must produce a 404-family answer, NOT the 403 that the old
        // @PreAuthorize produced for every customer token regardless of ownership.
        mockMvc.perform(post("/invoices/{id}/payment-request", UUID.randomUUID())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(BODY)
                .with(customerOf(TENANT, UUID.randomUUID())))
            .andExpect(status().isNotFound());
    }

    @Test
    void idempotencyKeyIsStillRequiredForCustomers() throws Exception {
        mockMvc.perform(post("/invoices/{id}/payment-request", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(BODY)
                .with(customerOf(TENANT, UUID.randomUUID())))
            .andExpect(status().isBadRequest());
    }

    @Test
    void customerCannotRequestPaymentForAnotherPartysInvoice() throws Exception {
        // `seedInvoiceOwnedBy` is this test's fixture idiom (mirroring BillingContractTest.issuePolicy):
        // register a real party (underwriting's openCase validates applicantPartyId via
        // partyApi.getParty, so an arbitrary UUID cannot stand in for the owner), issue a policy for
        // it, let the schedule generate invoices, and return the first invoice id plus the owner's
        // real partyId.
        SeededInvoice seeded = seedInvoiceOwnedBy();

        mockMvc.perform(post("/invoices/{id}/payment-request", seeded.invoiceId())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(BODY)
                .with(customerOf(TENANT, UUID.randomUUID())))   // a different party
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/invoices/{id}/payment-request", seeded.invoiceId())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(BODY)
                .with(customerOf(TENANT, seeded.owner())))
            .andExpect(status().isAccepted());
    }

    private record SeededInvoice(UUID invoiceId, UUID owner) {}

    /**
     * Copies BillingContractTest.issuePolicy's full-HTTP fixture chain (register applicant ->
     * publish product -> open underwriting case -> manual-issue with an explicit premiumAmount),
     * scoped to this test's fixed TENANT so customerOf(TENANT, ...) tokens resolve against it, and
     * returns the id of the first generated invoice together with the real partyId of the
     * policyholder that owns it.
     */
    private SeededInvoice seedInvoiceOwnedBy() throws Exception {
        // product.product_definition.product_code is VARCHAR(30) (db-migrations/product/V1), so this
        // must stay short -- unlike partyId/policyholderPartyId which are full UUIDs, an 8-char
        // truncated suffix is plenty unique within a single test run's single call to this fixture.
        String productCode = "BILLING-CUSTPAY-" + UUID.randomUUID().toString().substring(0, 8);

        String applicantResponse = mockMvc.perform(post("/parties/individuals")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder.claim("tenant_id", TENANT.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Customer Payment Applicant","dateOfBirth":"1990-01-01","contactInfo":{"phoneNumber":"+255713%06d"}}
                    """.formatted(Math.abs(productCode.hashCode() % 1000000))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID owner = UUID.fromString(JsonPath.read(applicantResponse, "$.partyId"));

        String productResponse = mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", TENANT.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"%s","productName":"Customer Payment Product","category":"TERM_LIFE","portfolioCode":"TERM","defaultCurrency":"TZS"}
                    """.formatted(productCode)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String productId = JsonPath.read(productResponse, "$.productId");

        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", TENANT.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ifrsMeasurementModel":"PAA","effectiveDate":"2026-01-01",
                     "payoutTerms":{"freeLookDays":15},"tiraFiling":{"reference":"TIRA/TEST/0001","approvalDate":"2020-01-01"},
                     "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0,"ageFrom":30,"ageTo":39},{"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
                     "benefitSchedule":[{"benefitType":"DEATH","calculationMethod":"SUM_ASSURED"}]}
                    """))
            .andExpect(status().isCreated());

        String snapshotResponse = mockMvc.perform(get("/products/" + productId + "/active-snapshot")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", TENANT.toString()))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String productVersionId = JsonPath.read(snapshotResponse, "$.productVersionId");

        String caseResponse = mockMvc.perform(post("/underwriting/cases")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", TENANT.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"applicantPartyId":"%s","productId":"%s","productVersionId":"%s","sumAssured":{"amount":"1000000.00","currencyCode":"TZS"}}
                    """.formatted(owner, productId, productVersionId)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String caseId = JsonPath.read(caseResponse, "$.caseId");

        String policyResponse = mockMvc.perform(post("/policies/manual-issue")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", TENANT.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"issuanceBasis":"UNDERWRITING_OVERRIDE","underwritingCaseId":"%s","policyholderPartyId":"%s","productVersionId":"%s",
                     "sumAssured":{"amount":"1000000.00","currencyCode":"TZS"},
                     "premiumAmount":{"amount":"15000.00","currencyCode":"TZS"},"premiumFrequency":"MONTHLY",
                     "agentOfRecordId":null,"reasonForManualIssue":"Customer payment test issuance"}
                    """.formatted(caseId, owner, productVersionId)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String policyNumber = JsonPath.read(policyResponse, "$.policyNumber");

        String listResponse = mockMvc.perform(get("/policies/" + policyNumber + "/invoices")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", TENANT.toString()))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String invoiceId = JsonPath.read(listResponse, "$[0].invoiceId");
        return new SeededInvoice(UUID.fromString(invoiceId), owner);
    }

    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor customerOf(UUID tenantId, UUID partyId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
            .jwt(builder -> builder.claim("tenant_id", tenantId.toString())
                                   .claim("party_id", partyId.toString()));
    }
}
