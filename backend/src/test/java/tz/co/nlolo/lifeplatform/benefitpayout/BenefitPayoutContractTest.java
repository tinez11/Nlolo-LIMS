package tz.co.nlolo.lifeplatform.benefitpayout;

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.benefitpayout.application.PayoutDueDrain;
import tz.co.nlolo.lifeplatform.product.api.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The payout surface against its own OpenAPI spec, over the wire.
 *
 * <p>Validation is strict, so a response shape that drifts from the spec fails here rather than in
 * a console typecheck weeks later -- the seam a service-level test cannot see.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(PayoutTestFixtures.class)
class BenefitPayoutContractTest {

    private static final String SPEC_PATH = "api/openapi/openapi-benefitpayout.yaml";
    private static final UUID TENANT = UUID.randomUUID();

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
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private PayoutTestFixtures fixtures;
    @Autowired private PayoutDueDrain drain;
    @Autowired private tz.co.nlolo.lifeplatform.benefitpayout.application.PaymentRunDrain runDrain;

    /** 12,000 a year paid monthly over two policy years, and proof of life every 12 months. */
    private static final PayoutPlan INCOME = PayoutPlan.authored(
        new PayoutTerms(15, 12, null, null),
        List.of(new PayoutRowInput(PayoutKind.INCOME, 1, 2, PayoutAmountBasis.FIXED,
                    new BigDecimal("12000"), PayoutFrequency.MONTHLY),
                new PayoutRowInput(PayoutKind.MATURITY, null, null, PayoutAmountBasis.PERCENT_OF_SA,
                    new BigDecimal("100"), null)));

    private static final PayoutPlan MONEY_BACK = PayoutPlan.authored(
        new PayoutTerms(15, null, false, null),
        List.of(
            new PayoutRowInput(PayoutKind.SURVIVAL, 5, 5, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("10"), PayoutFrequency.ANNUAL),
            new PayoutRowInput(PayoutKind.MATURITY, null, null, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("100"), null)));

    private static RequestPostProcessor staff(String role, String subject) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_" + role))
            .jwt(builder -> builder.subject(subject).claim("tenant_id", TENANT.toString()));
    }

    @Test
    void theScheduleMatchesTheContract() throws Exception {
        String policyNumber = fixtures.issueEndowment(TENANT, MONEY_BACK, new BigDecimal("1000000.00"), 240);

        mockMvc.perform(get("/policies/{n}/payouts", policyNumber).with(staff("FINANCE_OFFICER", "fin-1")))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$[0].kind").value("SURVIVAL"))
            // Money is a decimal STRING plus a currency code, never a JSON number.
            .andExpect(jsonPath("$[0].currentAmount.amount").value("100000.00"))
            .andExpect(jsonPath("$[0].currentAmount.currencyCode").value("TZS"));
    }

    @Test
    void reviewAndApproveMatchTheContractAndTheReviewerIsRefusedAsApprover() throws Exception {
        String policyNumber = fixtures.issueEndowment(TENANT, MONEY_BACK, new BigDecimal("1000000.00"), 240,
            LocalDate.now().minusYears(5).minusDays(1));
        fixtures.collectPremium(TENANT, policyNumber, new BigDecimal("3000000.00"), LocalDate.now().minusDays(1));
        drain.drain();

        String body = mockMvc.perform(get("/policies/{n}/payouts", policyNumber).with(staff("FINANCE_OFFICER", "fin-1")))
            .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$[0].instalmentId");

        mockMvc.perform(post("/payouts/{id}/review", id).with(staff("FINANCE_OFFICER", "fin-1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payeeRef\":\"+255700000009\",\"proofOfLifeMethod\":\"IN_PERSON\"}"))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.status").value("REVIEWED"));

        // The same person cannot approve what they reviewed, and says so in the server's own words.
        mockMvc.perform(post("/payouts/{id}/approve", id).with(staff("FINANCE_OFFICER", "fin-1")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errorCode").value("PAYOUT_REFUSED"))
            .andExpect(jsonPath("$.detail")
                .value("A payout must be approved by someone other than the person who reviewed it (fin-1)"));

        mockMvc.perform(post("/payouts/{id}/approve", id).with(staff("ADMIN", "admin-2")))
            .andExpect(status().isAccepted())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.approvedBy").value("admin-2"));
    }

    @Test
    void aCustomerServiceRepCannotReviewAPayout() throws Exception {
        // The body is a VALID review body on purpose: with an invented one a 400 from validation
        // would mask a missing role gate, and the test would pass while proving nothing.
        mockMvc.perform(post("/payouts/{id}/review", UUID.randomUUID())
                .with(staff("CUSTOMER_SERVICE_REP", "csr-1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payeeRef\":\"+255700000009\",\"proofOfLifeMethod\":\"IN_PERSON\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void theRegisterIsPagedAndFiltersByStatus() throws Exception {
        fixtures.issueEndowment(TENANT, MONEY_BACK, new BigDecimal("1000000.00"), 240);

        mockMvc.perform(get("/payouts").param("status", "SCHEDULED").param("pageSize", "1")
                .with(staff("FINANCE_OFFICER", "fin-1")))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.page.pageSize").value(1));
    }

    @Test
    void aPaymentRunIsListedAndApprovedOverTheContract() throws Exception {
        // An income plan on its OWN tenant: a payment run is one batch per tenant per day, so
        // sharing this class's tenant would mix another test's instalments into the count.
        UUID tenant = UUID.randomUUID();
        String policyNumber = fixtures.issueEndowment(tenant, INCOME, new BigDecimal("1000000.00"), 120,
            LocalDate.now().minusMonths(2).minusDays(1));
        fixtures.collectPremium(tenant, policyNumber, new BigDecimal("200000.00"), LocalDate.now());
        drain.drain();

        RequestPostProcessor finance = jwt()
            .authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_FINANCE_OFFICER"))
            .jwt(builder -> builder.subject("fin-run-1").claim("tenant_id", tenant.toString()));
        RequestPostProcessor approver = jwt()
            .authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
            .jwt(builder -> builder.subject("admin-run-2").claim("tenant_id", tenant.toString()));

        // The stream's first instalment through the ordinary two-person route, which activates it.
        String schedule = mockMvc.perform(get("/policies/{n}/payouts", policyNumber).with(finance))
            .andReturn().getResponse().getContentAsString();
        String firstIncome = JsonPath.<java.util.List<String>>read(schedule,
            "$[?(@.kind == 'INCOME')].instalmentId").get(0);
        mockMvc.perform(post("/payouts/{id}/review", firstIncome).with(finance)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payeeRef\":\"+255700000009\",\"proofOfLifeMethod\":\"IN_PERSON\"}"))
            .andExpect(status().isOk());
        mockMvc.perform(post("/payouts/{id}/approve", firstIncome).with(approver))
            .andExpect(status().isAccepted());

        runDrain.drain();

        String runs = mockMvc.perform(get("/payment-runs").with(finance))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$[0].status").value("PREPARED"))
            .andExpect(jsonPath("$[0].instalmentCount").value(1))
            // The total is money on the wire: a decimal STRING with a currency, never a number.
            .andExpect(jsonPath("$[0].total.amount").value("1000.00"))
            .andExpect(jsonPath("$[0].total.currencyCode").value("TZS"))
            .andReturn().getResponse().getContentAsString();
        String runId = JsonPath.read(runs, "$[0].paymentRunId");

        mockMvc.perform(get("/payment-runs/{id}/instalments", runId).with(finance))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$[0].status").value("DUE"));

        mockMvc.perform(post("/payment-runs/{id}/approve", runId).with(approver))
            .andExpect(status().isAccepted())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.approvedBy").value("admin-run-2"));

        // Released once. A second release would request every payment in the batch again.
        mockMvc.perform(post("/payment-runs/{id}/approve", runId).with(approver))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("PAYOUT_REFUSED"));
    }

    @Test
    void aFreeLookCancellationIsPreparedReadBackAndApprovedOverTheContract() throws Exception {
        String policyNumber = fixtures.issueEndowment(TENANT, MONEY_BACK, new BigDecimal("500000.00"), 240);
        fixtures.collectPremium(TENANT, policyNumber, new BigDecimal("50000.00"), LocalDate.now());

        // Nothing prepared yet: 204, because "this policy was never cancelled" is not an error.
        mockMvc.perform(get("/policies/{n}/free-look-cancellation", policyNumber)
                .with(staff("CUSTOMER_SERVICE_REP", "csr-1")))
            .andExpect(status().isNoContent());

        String created = mockMvc.perform(post("/policies/{n}/free-look-cancellation", policyNumber)
                .with(staff("CUSTOMER_SERVICE_REP", "csr-1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payeeRef\":\"+255700000009\",\"deductions\":"
                    + "[{\"description\":\"Medical examination\",\"amount\":\"8000.00\"}]}"))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.status").value("REQUESTED"))
            .andExpect(jsonPath("$.premiumsCollected.amount").value("50000.00"))
            .andExpect(jsonPath("$.refundAmount.amount").value("42000.00"))
            .andExpect(jsonPath("$.deductions[0].amount.amount").value("8000.00"))
            .andReturn().getResponse().getContentAsString();
        String cancellationId = JsonPath.read(created, "$.cancellationId");

        mockMvc.perform(get("/policies/{n}/free-look-cancellation", policyNumber)
                .with(staff("CUSTOMER_SERVICE_REP", "csr-1")))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.cancellationId").value(cancellationId));

        // Preparing is open to any staff member; releasing the money is finance's.
        mockMvc.perform(post("/free-look-cancellations/{id}/approve", cancellationId)
                .with(staff("CUSTOMER_SERVICE_REP", "csr-1")))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/free-look-cancellations/{id}/approve", cancellationId)
                .with(staff("FINANCE_OFFICER", "fin-2")))
            .andExpect(status().isAccepted())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.approvedBy").value("fin-2"));
    }

    @Test
    void aDeductionAmountMustBeADecimalString() throws Exception {
        // A JSON number would be parsed as a double somewhere between the browser and the ledger
        // and land a fraction of a cent out, on a figure the customer may check to the shilling.
        mockMvc.perform(post("/policies/{n}/free-look-cancellation", "POL-DOES-NOT-MATTER")
                .with(staff("CUSTOMER_SERVICE_REP", "csr-1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payeeRef\":\"+255700000009\",\"deductions\":"
                    + "[{\"description\":\"Medical examination\",\"amount\":\"8000.123\"}]}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void recordingProofOfLifeNeedsAMethodAndFinanceRights() throws Exception {
        // A CSR is refused before the body is even considered.
        mockMvc.perform(post("/payout-streams/{id}/proof-of-life", UUID.randomUUID())
                .with(staff("CUSTOMER_SERVICE_REP", "csr-1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"proofOfLifeMethod\":\"IN_PERSON\"}"))
            .andExpect(status().isForbidden());

        // "Proof of life was recorded" without saying HOW is the entry that makes the control
        // worthless, so the method is required rather than defaulted.
        mockMvc.perform(post("/payout-streams/{id}/proof-of-life", UUID.randomUUID())
                .with(staff("FINANCE_OFFICER", "fin-1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void anUnknownInstalmentIs404WithTheSharedProblemShape() throws Exception {
        mockMvc.perform(get("/payouts/{id}", UUID.randomUUID()).with(staff("FINANCE_OFFICER", "fin-1")))
            .andExpect(status().isNotFound())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("PAYOUT_NOT_FOUND"))
            .andExpect(jsonPath("$.traceId").exists());
    }
}
