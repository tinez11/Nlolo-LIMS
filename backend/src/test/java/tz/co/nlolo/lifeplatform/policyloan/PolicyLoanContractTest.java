package tz.co.nlolo.lifeplatform.policyloan;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.SpecTypeConformance;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.policyloan.api.PolicyLoanApi;
import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-level contract coverage for {@code PolicyLoanController} against
 * {@code api/openapi/openapi-policyloan.yaml} -- the falsifiability gate for Tasks 6-7.
 * Validation is STRICT ({@code openApi().isValid(SPEC_PATH)} with no custom level resolver), so
 * an undeclared response status or an off-schema body is a hard failure here.
 *
 * <p><b>Deliberately does NOT re-cover</b> the negative/zero-amount rejection already proven by
 * {@code PolicyLoanMoneyDtoValidationTest} (the {@code @DecimalMin} constraint itself, with its
 * own negative control) and {@code PolicyLoanControllerValidationContractTest} (that same input's
 * actual over-the-wire 400 {@code VALIDATION_ERROR} shape). Every amount below is therefore
 * >= 0.01, as {@code policyloan.infrastructure.MoneyDto} now requires.
 *
 * <p><b>Scale note (why every amount below is written with two decimal places).</b>
 * {@code PolicyLoanApiImpl.originateLoan} returns {@code toView()} built from the IN-MEMORY
 * entity, so the 202 body renders exactly the {@code BigDecimal} parsed from the request string,
 * while a later {@code GET /loans/{loanId}} re-reads the same value from a
 * {@code NUMERIC(19,2)} column. A request of {@code "1000.5"} would therefore render
 * {@code "1000.5"} on originate and {@code "1000.50"} on read -- both satisfy openapi-common's
 * {@code Money} pattern {@code ^-?\d+(\.\d{1,2})?$}, so this is NOT a contract violation and
 * {@code toView} is deliberately left alone. Scale-2 inputs make originate and read render
 * identically, so the cross-check in
 * {@code getLoanMatchesOpenApiContractAndRendersTheSameAmountAsOriginate} is stable rather than
 * input-dependent.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class PolicyLoanContractTest {

    private static final String SPEC_PATH = "api/openapi/openapi-policyloan.yaml";

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
            "db-migrations/product/V19__accumulation_terms.sql",
            "db-migrations/product/V20__deposit_rate_grid.sql",
            "db-migrations/product/V21__bonus_terms.sql",
            "db-migrations/product/V27__ifrs17_classification.sql",
            "db-migrations/product/V28__survival_investment_component.sql",
            "db-migrations/product/V29__funeral_group_rate.sql",
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
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V8__ifrs17_branches_and_channels.sql",
            "db-migrations/refdata/V9__journal_reason_codes.sql",
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
            "db-migrations/policyloan/V1__create_policyloan_schema.sql",
            "db-migrations/policyloan/V3__money_check_constraints.sql",
            "db-migrations/policyloan/V4__persist_reservation_id.sql",
            "db-migrations/policyloan/V5__loan_interest_accrual.sql",
            "db-migrations/policyloan/V7__q4_2026_partitions.sql",
            "db-migrations/policyloan/V8__interest_month_published.sql",
            // NOT optional, and NOT in the task brief's list: every domain event these tests
            // publish (LoanOriginated, LoanDisbursementRequested, LoanRepaid, PolicyIssued, ...)
            // is picked up application-wide by audit.DomainEventAuditListener. It swallows its
            // own persistence failures, so omitting this migration still "passes" while flooding
            // the build log with SQLGrammarException stack traces for a missing audit.audit_log.
            // Same reason PolicyLoanApiIntegrationTest and policy.PolicyApiIntegrationTest carry it.
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql");
    }

    @Autowired private MockMvc mockMvc;

    /** Only used to reach {@code markDisbursed}, which has no HTTP endpoint by design -- it is
     * driven by {@code policyloan.application.PaymentEventListener} consuming
     * {@code payment.DisbursementCompleted} (M5). Called directly here in place of running the
     * real event chain, purely so the repayment path (which needs a DISBURSED loan) is
     * reachable in a contract test that otherwise has no payment infrastructure wired in;
     * {@code LoanDisbursementEndToEndTest} is what proves the real chain itself. */
    @Autowired private PolicyLoanApi policyLoanApi;
    /** Only to collect the first premium in the fixture below -- a loan needs cover, not an offer. */
    @Autowired private tz.co.nlolo.lifeplatform.policy.api.PolicyApi policyApi;

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    private record Fixture(UUID tenantId, String policyNumber, UUID policyholderPartyId) {}

    /**
     * Full-HTTP fixture chain (register applicant -> publish product -> open underwriting case ->
     * manual-issue), mirroring {@code policy.PolicyContractTest.manualIssue}. Deliberately opens
     * an underwriting case and stops there instead of submitting an assessment:
     * {@code UnderwritingDecisionEventListener} auto-issues a policy synchronously on an
     * ACCEPT/LOADED decision, so submitting an assessment and then calling manual-issue would
     * double-issue.
     *
     * <p>The JDBC bump at the end is mandatory, not cosmetic: {@code policy.policy_account
     * .cash_value_amount} is hardcoded to ZERO at issuance (real cash value accrues via billing,
     * M4), so without it every {@code originateLoan} would fail with
     * {@code InsufficientLoanValueException}. Same shortcut as
     * {@code PolicyLoanApiIntegrationTest.issuePolicyWithCashValue}.
     */
    private Fixture issuePolicyWithCashValue(String productCode, String cashValue) throws Exception {
        UUID tenantId = UUID.randomUUID();
        String applicantResponse = mockMvc.perform(post("/parties/individuals")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Loan Contract Applicant","dateOfBirth":"1990-01-01","contactInfo":{"phoneNumber":"+255713%06d"}}
                    """.formatted(Math.abs(productCode.hashCode() % 1000000))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID applicantId = UUID.fromString(JsonPath.read(applicantResponse, "$.partyId"));

        String productResponse = mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"%s","productName":"Loan Contract Product","category":"TERM_LIFE","portfolioCode":"TERM","defaultCurrency":"TZS"}
                    """.formatted(productCode)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String productId = JsonPath.read(productResponse, "$.productId");

        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
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
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String productVersionId = JsonPath.read(snapshotResponse, "$.productVersionId");

        String caseResponse = mockMvc.perform(post("/underwriting/cases")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"applicantPartyId":"%s","productId":"%s","productVersionId":"%s","sumAssured":{"amount":"1000000.00","currencyCode":"TZS"}}
                    """.formatted(applicantId, productId, productVersionId)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String caseId = JsonPath.read(caseResponse, "$.caseId");

        String policyResponse = mockMvc.perform(post("/policies/manual-issue")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"issuanceBasis":"UNDERWRITING_OVERRIDE","underwritingCaseId":"%s","policyholderPartyId":"%s","productVersionId":"%s",
                     "sumAssured":{"amount":"1000000.00","currencyCode":"TZS"},
                     "premiumAmount":{"amount":"15000.00","currencyCode":"TZS"},"agentOfRecordId":null,
                     "reasonForManualIssue":"Loan contract test issuance"}
                    """.formatted(caseId, applicantId, productVersionId)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String policyNumber = JsonPath.read(policyResponse, "$.policyNumber");

        // Manual issue produces an OFFER, and a loan needs a policy in force. Through the real
        // API rather than a status UPDATE beside the cash-value bump below, so the aggregate's
        // own PROPOSED guard is exercised instead of stepped around.
        TenantContext.set(tenantId);
        policyApi.activateOnFirstPremium(policyNumber);
        TenantContext.clear();

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement(
                 "UPDATE policy.policy_account SET cash_value_amount = ?::numeric WHERE policy_number = ?")) {
            statement.setString(1, cashValue);
            statement.setString(2, policyNumber);
            // Asserted, not discarded. A seed that matched zero rows would leave cash value at
            // 0.00, and originateLoanRejectsAnAmountExceedingAvailableWith409 -- the one test here
            // that seeds a DELIBERATELY SMALL cash value and asserts a 409 -- would still get its
            // 409 (0.00 available rejects any positive request) and pass having proven nothing
            // about availableLoanValue's arithmetic. Every other test seeds a large value and
            // would fail loudly on its own, but this guard is what makes that one non-vacuous.
            assertThat(statement.executeUpdate())
                .as("cash-value seed for %s must update exactly one policy_account row", policyNumber)
                .isEqualTo(1);
        }
        return new Fixture(tenantId, policyNumber, applicantId);
    }

    private static RequestPostProcessor agentOf(UUID tenantId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
            .jwt(builder -> builder.claim("tenant_id", tenantId.toString()));
    }

    private static RequestPostProcessor customerOf(UUID tenantId, UUID partyId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
            .jwt(builder -> builder.claim("tenant_id", tenantId.toString()).claim("party_id", partyId.toString()));
    }

    private static final String ORIGINATE_BODY = """
        {"requestedAmount":{"amount":"200000.00","currencyCode":"TZS"},"payeeRef":"MPESA-0712345678"}
        """;

    // --- POST /policies/{policyNumber}/loans ------------------------------------------------

    @Test
    void originateLoanMatchesOpenApiContract() throws Exception {
        Fixture fixture = issuePolicyWithCashValue("LOAN-CONTRACT-01", "1000000.00");

        mockMvc.perform(post("/policies/" + fixture.policyNumber() + "/loans")
                .with(agentOf(fixture.tenantId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(ORIGINATE_BODY))
            .andExpect(status().isAccepted())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            // openApi().isValid() does NOT check primitive JSON types (measured: a type:string field
            // emitted as a number reports hasErrors=false). LoanView is the densest money surface in
            // the platform -- two Money objects whose amounts must stay decimal STRINGS, plus
            // currentInterestRate, which conversely must stay a JSON number.
            .andExpect(SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "LoanView"))
            .andExpect(jsonPath("$.status").value("DISBURSEMENT_REQUESTED"))
            .andExpect(jsonPath("$.policyNumber").value(fixture.policyNumber()))
            .andExpect(jsonPath("$.principalAmount.amount").value("200000.00"))
            .andExpect(jsonPath("$.principalAmount.currencyCode").value("TZS"))
            .andExpect(jsonPath("$.outstandingBalance.amount").value("200000.00"))
            .andExpect(jsonPath("$.loanId").exists());
    }

    @Test
    void originateLoanRejectsAnAmountExceedingAvailableWith409() throws Exception {
        // Cash value 100,000.00 against a 200,000.00 request -- PolicyApiImpl.reserveLoanValue
        // rejects with InsufficientLoanValueException, which policy's own advice maps to 409
        // application-wide (policyloan deliberately does not duplicate that mapping).
        Fixture fixture = issuePolicyWithCashValue("LOAN-CONTRACT-02", "100000.00");

        mockMvc.perform(post("/policies/" + fixture.policyNumber() + "/loans")
                .with(agentOf(fixture.tenantId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(ORIGINATE_BODY))
            .andExpect(status().isConflict())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_LOAN_VALUE"))
            .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void originateLoanAgainstAnUnknownPolicyReturnsNotFoundNotServerError() throws Exception {
        // originateLoan's first act is policyApi.isPolicyInForce -> findPolicyOrThrow, so an
        // unknown (or other-tenant) policyNumber surfaces as policy's own 404, not a 500 and not
        // policyloan's LOAN_NOT_FOUND. The path segment still matches openapi-common's
        // PolicyNumberRef pattern, so the request half of the contract validates too.
        mockMvc.perform(post("/policies/NOSUCHPOLICY1/loans")
                .with(agentOf(UUID.randomUUID()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(ORIGINATE_BODY))
            .andExpect(status().isNotFound())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("POLICY_NOT_FOUND"));
    }

    // --- Object-level ownership: all four endpoints, both directions -------------------------

    /**
     * {@code policy.PolicyContractTest} proves the AccessDeniedException -> 403 idiom for
     * {@code policy}; this is {@code policyloan}'s own equivalent. All FOUR endpoints call
     * {@code enforceCustomerOwnPolicyOnly}, so all four are exercised deliberately rather than
     * assuming one endpoint's pass generalizes -- {@code getLoan} and {@code recordRepayment}
     * in particular resolve the loan FIRST and only then check ownership, a different order from
     * {@code originateLoan}/{@code listLoans}.
     */
    @Test
    void everyLoanEndpointRejectsACustomerActingOnSomeoneElsesPolicyWith403() throws Exception {
        Fixture fixture = issuePolicyWithCashValue("LOAN-CONTRACT-03", "1000000.00");
        String originateResponse = mockMvc.perform(post("/policies/" + fixture.policyNumber() + "/loans")
                .with(agentOf(fixture.tenantId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(ORIGINATE_BODY))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        UUID loanId = UUID.fromString(JsonPath.read(originateResponse, "$.loanId"));

        // Same tenant, wrong party_id -- a 403 (not a disguised 404): the anti-enumeration
        // disguise is for CROSS-TENANT access, which policy's tenant-scoped query already 404s.
        UUID stranger = UUID.randomUUID();

        mockMvc.perform(post("/policies/" + fixture.policyNumber() + "/loans")
                .with(customerOf(fixture.tenantId(), stranger))
                .contentType(MediaType.APPLICATION_JSON)
                .content(ORIGINATE_BODY))
            .andExpect(status().isForbidden())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

        mockMvc.perform(get("/policies/" + fixture.policyNumber() + "/loans")
                .with(customerOf(fixture.tenantId(), stranger)))
            .andExpect(status().isForbidden())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));

        mockMvc.perform(get("/loans/" + loanId)
                .with(customerOf(fixture.tenantId(), stranger)))
            .andExpect(status().isForbidden())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));

        mockMvc.perform(post("/loans/" + loanId + "/repayments")
                .with(customerOf(fixture.tenantId(), stranger))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"amount":{"amount":"50000.00","currencyCode":"TZS"},"paymentReference":"PAY-CONTRACT-403"}
                    """))
            .andExpect(status().isForbidden())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    /**
     * The positive control the 403 test above needs to mean anything: without it, an
     * {@code enforceCustomerOwnPolicyOnly} that rejected EVERY customer (or a
     * {@code @PreAuthorize} that omitted {@code REALM_CUSTOMERS} altogether) would pass the 403
     * test for entirely the wrong reason. Here the customer's {@code party_id} claim MATCHES the
     * real policyholder, and every endpoint must let them through.
     */
    @Test
    void everyLoanEndpointAdmitsTheOwningCustomer() throws Exception {
        Fixture fixture = issuePolicyWithCashValue("LOAN-CONTRACT-04", "1000000.00");
        String originateResponse = mockMvc.perform(post("/policies/" + fixture.policyNumber() + "/loans")
                .with(customerOf(fixture.tenantId(), fixture.policyholderPartyId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(ORIGINATE_BODY))
            .andExpect(status().isAccepted())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andReturn().getResponse().getContentAsString();
        UUID loanId = UUID.fromString(JsonPath.read(originateResponse, "$.loanId"));

        mockMvc.perform(get("/policies/" + fixture.policyNumber() + "/loans")
                .with(customerOf(fixture.tenantId(), fixture.policyholderPartyId())))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));

        mockMvc.perform(get("/loans/" + loanId)
                .with(customerOf(fixture.tenantId(), fixture.policyholderPartyId())))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));

        // The owner's repayment attempt gets past the ownership gate and is then rejected on
        // STATE, not authorization: the loan legitimately rests at DISBURSEMENT_REQUESTED (nothing
        // consumes LoanDisbursementRequested until payment, M5). A 409 LOAN_NOT_ELIGIBLE here is
        // therefore the proof the ownership check admitted them -- a 403 would mean it did not.
        mockMvc.perform(post("/loans/" + loanId + "/repayments")
                .with(customerOf(fixture.tenantId(), fixture.policyholderPartyId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"amount":{"amount":"50000.00","currencyCode":"TZS"},"paymentReference":"PAY-CONTRACT-OWNER"}
                    """))
            .andExpect(status().isConflict())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("LOAN_NOT_ELIGIBLE"));
    }

    // --- GET /policies/{policyNumber}/loans --------------------------------------------------

    @Test
    void listLoansMatchesOpenApiContract() throws Exception {
        Fixture fixture = issuePolicyWithCashValue("LOAN-CONTRACT-05", "1000000.00");
        mockMvc.perform(post("/policies/" + fixture.policyNumber() + "/loans")
                .with(agentOf(fixture.tenantId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(ORIGINATE_BODY))
            .andExpect(status().isAccepted());

        mockMvc.perform(get("/policies/" + fixture.policyNumber() + "/loans")
                .with(agentOf(fixture.tenantId())))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].policyNumber").value(fixture.policyNumber()))
            .andExpect(jsonPath("$[0].status").value("DISBURSEMENT_REQUESTED"));
    }

    // --- GET /loans/{loanId} -----------------------------------------------------------------

    @Test
    void getLoanMatchesOpenApiContractAndRendersTheSameAmountAsOriginate() throws Exception {
        Fixture fixture = issuePolicyWithCashValue("LOAN-CONTRACT-06", "1000000.00");
        String originateResponse = mockMvc.perform(post("/policies/" + fixture.policyNumber() + "/loans")
                .with(agentOf(fixture.tenantId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(ORIGINATE_BODY))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        UUID loanId = UUID.fromString(JsonPath.read(originateResponse, "$.loanId"));
        String originatedPrincipal = JsonPath.read(originateResponse, "$.principalAmount.amount");

        // originatedPrincipal comes from the in-memory entity; the value asserted below is read
        // back out of NUMERIC(19,2). See this class's scale note -- a scale-2 request amount is
        // what makes these two renderings comparable at all.
        mockMvc.perform(get("/loans/" + loanId)
                .with(agentOf(fixture.tenantId())))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.loanId").value(loanId.toString()))
            .andExpect(jsonPath("$.policyNumber").value(fixture.policyNumber()))
            .andExpect(jsonPath("$.principalAmount.amount").value(originatedPrincipal))
            .andExpect(jsonPath("$.principalAmount.amount").value("200000.00"))
            .andExpect(jsonPath("$.outstandingBalance.amount").value("200000.00"))
            .andExpect(jsonPath("$.status").value("DISBURSEMENT_REQUESTED"));
    }

    @Test
    void getLoanForNonexistentLoanReturnsNotFoundNotServerError() throws Exception {
        mockMvc.perform(get("/loans/" + UUID.randomUUID())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", UUID.randomUUID().toString()))))
            .andExpect(status().isNotFound())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("LOAN_NOT_FOUND"));
    }

    // --- POST /loans/{loanId}/repayments -----------------------------------------------------

    @Test
    void recordRepaymentIsRejectedBeforeDisbursementAndAcceptedAfterIt() throws Exception {
        Fixture fixture = issuePolicyWithCashValue("LOAN-CONTRACT-07", "1000000.00");
        String originateResponse = mockMvc.perform(post("/policies/" + fixture.policyNumber() + "/loans")
                .with(agentOf(fixture.tenantId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(ORIGINATE_BODY))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        UUID loanId = UUID.fromString(JsonPath.read(originateResponse, "$.loanId"));

        String repaymentBody = """
            {"amount":{"amount":"50000.00","currencyCode":"TZS"},"paymentReference":"PAY-CONTRACT-01"}
            """;

        mockMvc.perform(post("/loans/" + loanId + "/repayments")
                .with(agentOf(fixture.tenantId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(repaymentBody))
            .andExpect(status().isConflict())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("LOAN_NOT_ELIGIBLE"));

        // markDisbursed has no HTTP endpoint by design (it is not in openapi-policyloan.yaml) --
        // in production it is driven by PaymentEventListener consuming
        // payment.DisbursementCompleted, so it is called from Java directly here.
        // TenantContext is set explicitly here because this call does NOT go through
        // TenantContextFilter, which is what populates it on every MockMvc request above.
        TenantContext.set(fixture.tenantId());
        try {
            policyLoanApi.markDisbursed(loanId, "MM-TEST-REF", Instant.now());
        } finally {
            TenantContext.clear();
        }

        mockMvc.perform(post("/loans/" + loanId + "/repayments")
                .with(agentOf(fixture.tenantId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(repaymentBody))
            .andExpect(status().isAccepted())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.status").value("REPAYING"))
            .andExpect(jsonPath("$.outstandingBalance.amount").value("150000.00"));
    }

    @Test
    void recordRepaymentForNonexistentLoanReturnsNotFoundNotServerError() throws Exception {
        mockMvc.perform(post("/loans/" + UUID.randomUUID() + "/repayments")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", UUID.randomUUID().toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"amount":{"amount":"50000.00","currencyCode":"TZS"},"paymentReference":"PAY-CONTRACT-404"}
                    """))
            .andExpect(status().isNotFound())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("LOAN_NOT_FOUND"));
    }

    /**
     * {@code GET /loans/forced-lapse-review-queue} must resolve as a LITERAL path and never be
     * swallowed by {@code GET /loans/{loanId}}, whose {@code @PathVariable UUID loanId} would
     * fail to parse "forced-lapse-review-queue" and answer 400 instead.
     *
     * <p>Spring's {@code PathPatternParser} does sort a literal ahead of a pattern containing a
     * variable, but that is a claim about framework behavior sitting between two same-prefix
     * mappings -- worth PINNING rather than asserting in a comment, because the failure mode is a
     * 400 on a working endpoint and reordering the two handlers would not otherwise show up.
     */
    @Test
    void theForcedLapseReviewQueueResolvesAsALiteralPathNotAsALoanId() throws Exception {
        mockMvc.perform(get("/loans/forced-lapse-review-queue")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", UUID.randomUUID().toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            // A fresh tenant has nothing queued -- an empty array, not a 400 and not a 404.
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * Both forced-lapse endpoints are STAFF-only, and this is the test that holds them there.
     * The evaluation can TERMINATE A POLICY, so a customer or agent reaching it -- even against
     * their own policy, which every other operation on this controller deliberately allows --
     * would be a customer able to lapse their own cover. The queue is staff-only for a different
     * reason: it spans the tenant's whole loan book, so the own-policy-only check the other
     * endpoints apply has nothing to bind to.
     */
    @Test
    void bothForcedLapseEndpointsRejectCustomersAndAgents() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID someParty = UUID.randomUUID();
        UUID someLoan = UUID.randomUUID();

        mockMvc.perform(get("/loans/forced-lapse-review-queue").with(customerOf(tenantId, someParty)))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/loans/forced-lapse-review-queue").with(agentOf(tenantId)))
            .andExpect(status().isForbidden());

        // A random loan id is fine: @PreAuthorize runs BEFORE the method body, so a 403 here
        // proves the gate rather than the lookup. If the gate were missing this would be a 404
        // instead, which is exactly the difference being asserted.
        mockMvc.perform(post("/loans/" + someLoan + "/forced-lapse-evaluation")
                .with(customerOf(tenantId, someParty)))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/loans/" + someLoan + "/forced-lapse-evaluation")
                .with(agentOf(tenantId)))
            .andExpect(status().isForbidden());
    }

    /**
     * The shortfall test over HTTP on a well-collateralized loan: 200, the loan untouched, and
     * the body on-contract. The 200-not-202 distinction is deliberate and declared in the spec --
     * this operation completes synchronously, with no external rail involved.
     */
    @Test
    void forcedLapseEvaluationLeavesAWellCollateralizedLoanAloneAndMatchesTheContract() throws Exception {
        Fixture fixture = issuePolicyWithCashValue("LOAN-CONTRACT-FL", "1000000.00");

        String originateResponse = mockMvc.perform(post("/policies/" + fixture.policyNumber() + "/loans")
                .with(agentOf(fixture.tenantId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"requestedAmount":{"amount":"200000.00","currencyCode":"TZS"},"payeeRef":"MPESA-0712345678"}
                    """))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        UUID loanId = UUID.fromString(JsonPath.read(originateResponse, "$.loanId"));

        // Disbursed so the loan is in a status the shortfall test actually examines; same
        // no-HTTP-endpoint-by-design reasoning as the repayment test above.
        TenantContext.set(fixture.tenantId());
        try {
            policyLoanApi.markDisbursed(loanId, "MM-TEST-REF", Instant.now());
        } finally {
            TenantContext.clear();
        }

        mockMvc.perform(post("/loans/" + loanId + "/forced-lapse-evaluation")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", fixture.tenantId().toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            // 200,000 borrowed against 1,000,000 of cash value: nowhere near a shortfall, so
            // the loan must come back exactly as it was.
            .andExpect(jsonPath("$.status").value("DISBURSED"))
            .andExpect(jsonPath("$.outstandingBalance.amount").value("200000.00"));
    }

    @Test
    void forcedLapseEvaluationForNonexistentLoanReturnsNotFoundNotServerError() throws Exception {
        mockMvc.perform(post("/loans/" + UUID.randomUUID() + "/forced-lapse-evaluation")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", UUID.randomUUID().toString()))))
            .andExpect(status().isNotFound())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("LOAN_NOT_FOUND"));
    }
}
