package tz.co.nlolo.lifeplatform.policyloan;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
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
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
            "db-migrations/policy/V3__premium_fields.sql",
            "db-migrations/policyloan/V1__create_policyloan_schema.sql",
            "db-migrations/policyloan/V3__money_check_constraints.sql",
            // NOT optional, and NOT in the task brief's list: every domain event these tests
            // publish (LoanOriginated, LoanDisbursementRequested, LoanRepaid, PolicyIssued, ...)
            // is picked up application-wide by audit.DomainEventAuditListener. It swallows its
            // own persistence failures, so omitting this migration still "passes" while flooding
            // the build log with SQLGrammarException stack traces for a missing audit.audit_log.
            // Same reason PolicyLoanApiIntegrationTest and policy.PolicyApiIntegrationTest carry it.
            "db-migrations/audit/V1__create_audit_schema.sql");
    }

    @Autowired private MockMvc mockMvc;

    /** Only used to reach {@code markDisbursed}, an internal-only test seam with no HTTP endpoint
     * (it stands in for consuming {@code payment.DisbursementCompleted}, M5, not built). It is the
     * only way to move a loan to DISBURSED so the repayment path is reachable at all. */
    @Autowired private PolicyLoanApi policyLoanApi;

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
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"%s","productName":"Loan Contract Product","category":"TERM_LIFE","defaultCurrency":"TZS"}
                    """.formatted(productCode)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String productId = JsonPath.read(productResponse, "$.productId");

        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ifrsMeasurementModel":"PAA","effectiveDate":"2026-01-01",
                     "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0},{"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
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
                    {"underwritingCaseId":"%s","policyholderPartyId":"%s","productVersionId":"%s",
                     "sumAssured":{"amount":"1000000.00","currencyCode":"TZS"},
                     "premiumAmount":{"amount":"15000.00","currencyCode":"TZS"},"agentOfRecordId":"%s",
                     "reasonForManualIssue":"Loan contract test issuance"}
                    """.formatted(caseId, applicantId, productVersionId, UUID.randomUUID())))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String policyNumber = JsonPath.read(policyResponse, "$.policyNumber");

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
        // it stands in for consuming payment.DisbursementCompleted, so it is called from Java.
        // TenantContext is set explicitly here because this call does NOT go through
        // TenantContextFilter, which is what populates it on every MockMvc request above.
        TenantContext.set(fixture.tenantId());
        try {
            policyLoanApi.markDisbursed(loanId);
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
}
