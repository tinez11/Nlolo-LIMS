package tz.co.nlolo.lifeplatform.billing;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.SpecTypeConformance;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.billing.domain.FieldReceipt;
import tz.co.nlolo.lifeplatform.billing.infrastructure.FieldReceiptRepository;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-level contract coverage for {@code BillingController}/{@code BillingSyncController}
 * against {@code api/openapi/openapi-billing.yaml} -- the falsifiability gate for Tasks 3-6,
 * mirroring the role {@code PolicyLoanContractTest} played for M3's {@code policyloan}.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class BillingContractTest {

    private static final String SPEC_PATH = "api/openapi/openapi-billing.yaml";

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
            "db-migrations/product/V2__base_rate_table.sql",
            "db-migrations/product/V3__base_rate_structured_age.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
            "db-migrations/policy/V3__premium_fields.sql",
            "db-migrations/policy/V4__underwriting_case_id.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            "db-migrations/policyloan/V1__create_policyloan_schema.sql",
            "db-migrations/policyloan/V3__money_check_constraints.sql",
            "db-migrations/billing/V1__create_billing_schema.sql",
            "db-migrations/billing/V2__grants_rls_money_checks_and_notification_columns.sql",
            // M5 (Task 7) addition: PremiumInvoice now maps amount_paid -- every JPA insert
            // issuePolicy's billing.PolicyEventListener -> generateInvoicesAhead chain triggers
            // for this class's own fixtures would otherwise fail against a table missing this
            // column.
            "db-migrations/billing/V3__amount_paid.sql",
            "db-migrations/audit/V1__create_audit_schema.sql");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private FieldReceiptRepository fieldReceiptRepository;

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    private record Fixture(UUID tenantId, String policyNumber, UUID policyholderPartyId) {}

    /**
     * Full-HTTP fixture chain (register applicant -> publish product -> open underwriting case ->
     * manual-issue with an explicit premiumAmount), mirroring
     * {@code PolicyLoanContractTest.issuePolicyWithCashValue}'s own chain. Manual issuance (not
     * automatic, via a submitted assessment) is used specifically so this test controls the
     * premium amount directly rather than depending on TZ_BASE_PREMIUM_RATE_PER_MILLE's
     * placeholder rate -- billing's schedule/invoice generation reacts identically to
     * policy.PolicyIssued regardless of which issuance path produced it.
     */
    private Fixture issuePolicy(String productCode) throws Exception {
        UUID tenantId = UUID.randomUUID();
        String applicantResponse = mockMvc.perform(post("/parties/individuals")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Billing Contract Applicant","dateOfBirth":"1990-01-01","contactInfo":{"phoneNumber":"+255713%06d"}}
                    """.formatted(Math.abs(productCode.hashCode() % 1000000))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID applicantId = UUID.fromString(JsonPath.read(applicantResponse, "$.partyId"));

        String productResponse = mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"%s","productName":"Billing Contract Product","category":"TERM_LIFE","defaultCurrency":"TZS"}
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
                     "premiumAmount":{"amount":"15000.00","currencyCode":"TZS"},"premiumFrequency":"MONTHLY",
                     "agentOfRecordId":"%s","reasonForManualIssue":"Billing contract test issuance"}
                    """.formatted(caseId, applicantId, productVersionId, UUID.randomUUID())))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String policyNumber = JsonPath.read(policyResponse, "$.policyNumber");

        return new Fixture(tenantId, policyNumber, applicantId);
    }

    private static RequestPostProcessor staffOf(UUID tenantId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
            .jwt(builder -> builder.claim("tenant_id", tenantId.toString()));
    }

    private static RequestPostProcessor agentOf(UUID tenantId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
            .jwt(builder -> builder.claim("tenant_id", tenantId.toString()));
    }

    private static RequestPostProcessor customerOf(UUID tenantId, UUID partyId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
            .jwt(builder -> builder.claim("tenant_id", tenantId.toString()).claim("party_id", partyId.toString()));
    }

    // --- GET /policies/{policyNumber}/invoices -----------------------------------------------

    @Test
    void listInvoicesMatchesOpenApiContractWithRealInvoices() throws Exception {
        Fixture fixture = issuePolicy("BILLING-CONTRACT-LIST-01");

        mockMvc.perform(get("/policies/" + fixture.policyNumber() + "/invoices")
                .with(staffOf(fixture.tenantId())))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            // openApi().isValid() does NOT check primitive JSON types (measured: a type:string field
            // emitted as a number reports hasErrors=false). Named schema is the ELEMENT type here,
            // since this response is a bare JSON array -- every one of the 12 invoices is checked,
            // covering amount.amount (decimal string) and the nullable integer dunningLevel.
            .andExpect(SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "InvoiceView"))
            // 12-month schedule horizon / MONTHLY frequency, same math as
            // BillingApiIntegrationTest.issuingAPolicyGeneratesAnActiveScheduleAndInvoicesAhead.
            .andExpect(jsonPath("$.length()").value(12))
            .andExpect(jsonPath("$[0].policyNumber").value(fixture.policyNumber()))
            .andExpect(jsonPath("$[0].amount.amount").value("15000.00"))
            .andExpect(jsonPath("$[0].amount.currencyCode").value("TZS"))
            .andExpect(jsonPath("$[0].status").value("DUE"));
    }

    @Test
    void listInvoicesRejectsWrongCustomerWith403() throws Exception {
        Fixture fixture = issuePolicy("BILLING-CONTRACT-LIST-02");
        UUID stranger = UUID.randomUUID();

        mockMvc.perform(get("/policies/" + fixture.policyNumber() + "/invoices")
                .with(customerOf(fixture.tenantId(), stranger)))
            .andExpect(status().isForbidden())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    void listInvoicesForNonexistentPolicyReturnsNotFoundNotServerError() throws Exception {
        mockMvc.perform(get("/policies/NOSUCHPOLICY1/invoices")
                .with(staffOf(UUID.randomUUID())))
            .andExpect(status().isNotFound())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("POLICY_NOT_FOUND"));
    }

    // --- GET /policies/{policyNumber}/invoices/next-due --------------------------------------

    @Test
    void getNextDueInvoiceMatchesOpenApiContract() throws Exception {
        Fixture fixture = issuePolicy("BILLING-CONTRACT-NEXTDUE-01");

        mockMvc.perform(get("/policies/" + fixture.policyNumber() + "/invoices/next-due")
                .with(agentOf(fixture.tenantId())))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.policyNumber").value(fixture.policyNumber()))
            .andExpect(jsonPath("$.status").value("DUE"));
    }

    @Test
    void getNextDueInvoiceReturns404WhenEveryInvoiceHasBeenWaived() throws Exception {
        Fixture fixture = issuePolicy("BILLING-CONTRACT-NEXTDUE-02");

        String listResponse = mockMvc.perform(get("/policies/" + fixture.policyNumber() + "/invoices")
                .with(staffOf(fixture.tenantId())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> invoiceIds = JsonPath.read(listResponse, "$[*].invoiceId");
        assertThat(invoiceIds).isNotEmpty();
        for (String invoiceId : invoiceIds) {
            mockMvc.perform(post("/invoices/" + invoiceId + "/waiver")
                    .with(staffOf(fixture.tenantId()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"reason":"Contract test bulk waiver"}
                        """))
                .andExpect(status().isOk());
        }

        mockMvc.perform(get("/policies/" + fixture.policyNumber() + "/invoices/next-due")
                .with(agentOf(fixture.tenantId())))
            .andExpect(status().isNotFound())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("INVOICE_NOT_FOUND"));
    }

    // --- POST /invoices/{invoiceId}/waiver ---------------------------------------------------

    @Test
    void waiveInvoiceMatchesOpenApiContract() throws Exception {
        Fixture fixture = issuePolicy("BILLING-CONTRACT-WAIVER-01");
        String listResponse = mockMvc.perform(get("/policies/" + fixture.policyNumber() + "/invoices")
                .with(staffOf(fixture.tenantId())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String invoiceId = JsonPath.read(listResponse, "$[0].invoiceId");

        mockMvc.perform(post("/invoices/" + invoiceId + "/waiver")
                .with(staffOf(fixture.tenantId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reason":"Contract test waiver reason"}
                    """))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));

        mockMvc.perform(get("/policies/" + fixture.policyNumber() + "/invoices")
                .with(staffOf(fixture.tenantId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].status").value("WAIVED"));
    }

    @Test
    void waiveInvoiceRejectsNonStaffWith403() throws Exception {
        Fixture fixture = issuePolicy("BILLING-CONTRACT-WAIVER-02");
        String listResponse = mockMvc.perform(get("/policies/" + fixture.policyNumber() + "/invoices")
                .with(staffOf(fixture.tenantId())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String invoiceId = JsonPath.read(listResponse, "$[0].invoiceId");

        mockMvc.perform(post("/invoices/" + invoiceId + "/waiver")
                .with(agentOf(fixture.tenantId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reason":"Contract test waiver reason"}
                    """))
            .andExpect(status().isForbidden())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    void waiveInvoiceForNonexistentInvoiceReturnsNotFoundNotServerError() throws Exception {
        mockMvc.perform(post("/invoices/" + UUID.randomUUID() + "/waiver")
                .with(staffOf(UUID.randomUUID()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reason":"Contract test waiver reason"}
                    """))
            .andExpect(status().isNotFound())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("INVOICE_NOT_FOUND"));
    }

    @Test
    void waiveInvoiceRejectsAShortReasonWith400() throws Exception {
        Fixture fixture = issuePolicy("BILLING-CONTRACT-WAIVER-03");
        String listResponse = mockMvc.perform(get("/policies/" + fixture.policyNumber() + "/invoices")
                .with(staffOf(fixture.tenantId())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String invoiceId = JsonPath.read(listResponse, "$[0].invoiceId");

        // No openApi().isValid(SPEC_PATH) here -- the request body ITSELF violates the schema
        // (reason under minLength 10), so strict request-side validation would fail before the
        // response is even considered. Same reason PolicyLoanContractTest punts 400-body-shape
        // coverage to its own dedicated *ValidationContractTest classes rather than this matcher.
        mockMvc.perform(post("/invoices/" + invoiceId + "/waiver")
                .with(staffOf(fixture.tenantId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reason":"short"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    // --- POST /invoices/{invoiceId}/payment-request (M5, Task 7) ----------------------------

    @Test
    void requestPaymentForInvoiceMatchesOpenApiContractForAStaffToken() throws Exception {
        Fixture fixture = issuePolicy("BILLING-CONTRACT-PAYREQ-01");
        String listResponse = mockMvc.perform(get("/policies/" + fixture.policyNumber() + "/invoices")
                .with(staffOf(fixture.tenantId())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String invoiceId = JsonPath.read(listResponse, "$[0].invoiceId");

        mockMvc.perform(post("/invoices/" + invoiceId + "/payment-request")
                .with(staffOf(fixture.tenantId()))
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "contract-payreq-01-attempt-1")
                .content("""
                    {"payerRef":"MPESA-0712345678"}
                    """))
            .andExpect(status().isAccepted())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    /** @PreAuthorize on requestPaymentForInvoice allows REALM_STAFF or REALM_AGENTS -- unlike
     * waiveInvoice (staff-only), this endpoint has two admitted roles, so this test proves the
     * agent branch specifically, not just that staff (already proven above) also works. */
    @Test
    void requestPaymentForInvoiceAlsoAcceptsAnAgentToken() throws Exception {
        Fixture fixture = issuePolicy("BILLING-CONTRACT-PAYREQ-02");
        String listResponse = mockMvc.perform(get("/policies/" + fixture.policyNumber() + "/invoices")
                .with(staffOf(fixture.tenantId())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String invoiceId = JsonPath.read(listResponse, "$[0].invoiceId");

        mockMvc.perform(post("/invoices/" + invoiceId + "/payment-request")
                .with(agentOf(fixture.tenantId()))
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "contract-payreq-02-attempt-1")
                .content("""
                    {"payerRef":"MPESA-0712345679"}
                    """))
            .andExpect(status().isAccepted())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void requestPaymentForInvoiceRejectsANonOwningCustomerWith403() throws Exception {
        Fixture fixture = issuePolicy("BILLING-CONTRACT-PAYREQ-03");
        String listResponse = mockMvc.perform(get("/policies/" + fixture.policyNumber() + "/invoices")
                .with(staffOf(fixture.tenantId())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String invoiceId = JsonPath.read(listResponse, "$[0].invoiceId");

        // M12 Task 2: @PreAuthorize on requestPaymentForInvoice now also admits REALM_CUSTOMERS,
        // gated on object-level ownership (enforceCustomerOwnPolicyOnly). A stranger customer --
        // not the policy's own policyholderPartyId -- is still rejected with 403; the policy
        // owner's own-invoice acceptance path is covered by
        // BillingCustomerPaymentTest.customerCannotRequestPaymentForAnotherPartysInvoice.
        mockMvc.perform(post("/invoices/" + invoiceId + "/payment-request")
                .with(customerOf(fixture.tenantId(), UUID.randomUUID()))
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "contract-payreq-03-attempt-1")
                .content("""
                    {"payerRef":"MPESA-0712345680"}
                    """))
            .andExpect(status().isForbidden())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    void requestPaymentForInvoiceForNonexistentInvoiceReturnsNotFoundNotServerError() throws Exception {
        mockMvc.perform(post("/invoices/" + UUID.randomUUID() + "/payment-request")
                .with(staffOf(UUID.randomUUID()))
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "contract-payreq-04-attempt-1")
                .content("""
                    {"payerRef":"MPESA-0712345681"}
                    """))
            .andExpect(status().isNotFound())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("INVOICE_NOT_FOUND"));
    }

    @Test
    void requestPaymentForInvoiceRejectsABlankPayerRefWith400() throws Exception {
        Fixture fixture = issuePolicy("BILLING-CONTRACT-PAYREQ-04");
        String listResponse = mockMvc.perform(get("/policies/" + fixture.policyNumber() + "/invoices")
                .with(staffOf(fixture.tenantId())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String invoiceId = JsonPath.read(listResponse, "$[0].invoiceId");

        // No openApi().isValid(SPEC_PATH) here, same reason as waiveInvoiceRejectsAShortReasonWith400
        // -- PaymentRequestDto's real @NotBlank on payerRef is what rejects this, not the OpenAPI
        // schema (which places no minLength on payerRef), so this proves the Java-side validation
        // annotation is genuinely wired, not merely declared.
        mockMvc.perform(post("/invoices/" + invoiceId + "/payment-request")
                .with(staffOf(fixture.tenantId()))
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "contract-payreq-05-attempt-1")
                .content("""
                    {"payerRef":""}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    /**
     * Review fix (I1). The {@code Idempotency-Key} header is REQUIRED on this endpoint and must be
     * rejected with 400 rather than defaulted to anything, because every candidate default
     * reintroduces a real bug: defaulting to the invoice id is the original single-shot-forever bug
     * (payment claims {@code (tenant_id, key)} once and drops every later attempt, so an operator
     * retry after an {@code INSUFFICIENT_FUNDS} decline reaches the rail zero times while this
     * endpoint still answers 202), and defaulting to a fresh random value silently discards
     * duplicate-submission protection for a double-clicking operator.
     *
     * <p>Asserts the openApi validity of the 400 as well as its status, since openapi-billing.yaml
     * now declares the header {@code required: true} -- so the spec and the implementation are
     * pinned to each other in both directions rather than only the happy path.
     */
    @Test
    void requestPaymentForInvoiceWithoutAnIdempotencyKeyHeaderIsRejectedWith400() throws Exception {
        Fixture fixture = issuePolicy("BILLING-CONTRACT-PAYREQ-06");
        String listResponse = mockMvc.perform(get("/policies/" + fixture.policyNumber() + "/invoices")
                .with(staffOf(fixture.tenantId())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String invoiceId = JsonPath.read(listResponse, "$[0].invoiceId");

        // No Idempotency-Key header at all.
        mockMvc.perform(post("/invoices/" + invoiceId + "/payment-request")
                .with(staffOf(fixture.tenantId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"payerRef":"MPESA-0712345682"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        // Present but blank is the same rejection, via the same single code path -- a header that
        // exists with an empty value must not slip through a presence-only check.
        mockMvc.perform(post("/invoices/" + invoiceId + "/payment-request")
                .with(staffOf(fixture.tenantId()))
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "   ")
                .content("""
                    {"payerRef":"MPESA-0712345682"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    /**
     * Review fix (I1), the other half: a RETRY with a NEW key is accepted. Before the fix this was
     * impossible at the domain level -- the first attempt permanently consumed the invoice's only
     * key -- and impossible to express at the HTTP level, since there was no way for a client to say
     * "this is a new attempt, not a redelivery of the old one". Both requests here target the SAME
     * invoice with DIFFERENT keys and both must be accepted.
     *
     * <p>This test proves the HTTP contract admits the retry. The domain-level proof that a new key
     * actually reaches the rail a second time while a repeated key does not lives in
     * {@code BillingApiIntegrationTest.aRetryWithANewIdempotencyKeyReachesTheRailAgainWhileARepeatedKeyDoesNot},
     * which counts real gateway calls -- an HTTP 202 alone cannot distinguish those two, which is
     * precisely how the original bug went unnoticed.
     */
    @Test
    void requestPaymentForInvoiceAcceptsARetryWithADifferentIdempotencyKey() throws Exception {
        Fixture fixture = issuePolicy("BILLING-CONTRACT-PAYREQ-07");
        String listResponse = mockMvc.perform(get("/policies/" + fixture.policyNumber() + "/invoices")
                .with(staffOf(fixture.tenantId())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String invoiceId = JsonPath.read(listResponse, "$[0].invoiceId");

        mockMvc.perform(post("/invoices/" + invoiceId + "/payment-request")
                .with(staffOf(fixture.tenantId()))
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "contract-payreq-07-attempt-1")
                .content("""
                    {"payerRef":"MPESA-0712345683"}
                    """))
            .andExpect(status().isAccepted())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));

        // A genuine second attempt against the same invoice, distinguished only by its key.
        mockMvc.perform(post("/invoices/" + invoiceId + "/payment-request")
                .with(staffOf(fixture.tenantId()))
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "contract-payreq-07-attempt-2")
                .content("""
                    {"payerRef":"MPESA-0712345683"}
                    """))
            .andExpect(status().isAccepted())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    // --- POST /agents/{agentId}/field-receipts -----------------------------------------------

    @Test
    void captureFieldReceiptMatchesOpenApiContractAndPersistsPendingReconciliation() throws Exception {
        Fixture fixture = issuePolicy("BILLING-CONTRACT-RECEIPT-01");
        UUID agentId = UUID.randomUUID();
        String idempotencyKey = "contract-key-" + UUID.randomUUID();

        String response = mockMvc.perform(post("/agents/" + agentId + "/field-receipts")
                .with(agentOf(fixture.tenantId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"policyNumber":"%s","amount":{"amount":"15000.00","currencyCode":"TZS"},
                     "clientIdempotencyKey":"%s","capturedAt":"2026-08-11T10:00:00Z"}
                    """.formatted(fixture.policyNumber(), idempotencyKey)))
            .andExpect(status().isAccepted())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.status").value("PENDING_RECONCILIATION"))
            .andExpect(jsonPath("$.receiptId").exists())
            .andReturn().getResponse().getContentAsString();
        UUID receiptId = UUID.fromString(JsonPath.read(response, "$.receiptId"));

        // State change, not just status code: confirms the row genuinely landed in
        // PENDING_RECONCILIATION, not merely that the HTTP layer echoed that string back.
        TenantContext.set(fixture.tenantId());
        try {
            Optional<FieldReceipt> receipt = fieldReceiptRepository.findByTenantIdAndClientIdempotencyKey(fixture.tenantId(), idempotencyKey);
            assertThat(receipt).isPresent();
            assertThat(receipt.get().getReceiptId()).isEqualTo(receiptId);
            assertThat(receipt.get().getStatus()).isEqualTo("PENDING_RECONCILIATION");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void captureFieldReceiptRejectsAnInvalidBodyWith400() throws Exception {
        // No openApi().isValid(SPEC_PATH) here, for the same reason as
        // waiveInvoiceRejectsAShortReasonWith400 above -- the missing policyNumber makes the
        // REQUEST itself schema-invalid, which strict validation flags before the response.
        mockMvc.perform(post("/agents/" + UUID.randomUUID() + "/field-receipts")
                .with(agentOf(UUID.randomUUID()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"amount":{"amount":"15000.00","currencyCode":"TZS"},
                     "clientIdempotencyKey":"missing-policy-number","capturedAt":"2026-08-11T10:00:00Z"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void captureFieldReceiptIsIdempotentOnARepeatedClientKey() throws Exception {
        Fixture fixture = issuePolicy("BILLING-CONTRACT-RECEIPT-02");
        UUID agentId = UUID.randomUUID();
        String idempotencyKey = "contract-key-" + UUID.randomUUID();
        String body = """
            {"policyNumber":"%s","amount":{"amount":"15000.00","currencyCode":"TZS"},
             "clientIdempotencyKey":"%s","capturedAt":"2026-08-11T10:00:00Z"}
            """.formatted(fixture.policyNumber(), idempotencyKey);

        String firstResponse = mockMvc.perform(post("/agents/" + agentId + "/field-receipts")
                .with(agentOf(fixture.tenantId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String firstReceiptId = JsonPath.read(firstResponse, "$.receiptId");

        String secondResponse = mockMvc.perform(post("/agents/" + agentId + "/field-receipts")
                .with(agentOf(fixture.tenantId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isAccepted())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andReturn().getResponse().getContentAsString();
        String secondReceiptId = JsonPath.read(secondResponse, "$.receiptId");

        assertThat(secondReceiptId).isEqualTo(firstReceiptId);

        TenantContext.set(fixture.tenantId());
        try {
            List<FieldReceipt> all = fieldReceiptRepository.findAll().stream()
                .filter(r -> r.getClientIdempotencyKey().equals(idempotencyKey)).toList();
            assertThat(all).hasSize(1);
        } finally {
            TenantContext.clear();
        }
    }
}
