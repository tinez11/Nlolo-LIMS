package tz.co.nlolo.lifeplatform.refdata;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-level coverage for {@code ReferenceDataController}'s fail-closed per-realm allowlist,
 * following {@code FinaccountingContractTest}'s harness shape: {@code @Testcontainers} + {@code
 * @AutoConfigureMockMvc} + {@code @SpringBootTest(classes = Application.class, webEnvironment =
 * MOCK)} against a real Postgres, with {@code jwt()} post-processors fabricating tokens per realm.
 * {@code @WebMvcTest}-style slicing is not used anywhere on this platform (verified by grepping the
 * whole test tree), so this class does not introduce it either.
 *
 * <p>The allowlist's job is to stop a customer token reading TZ_BASE_PREMIUM_RATE_PER_MILLE -- the
 * company's premium pricing basis. The draft OpenAPI document this milestone replaced carried a
 * blanket customers/agents/staff security block, which would have served exactly that to anyone.
 *
 * <p>No OpenAPI spec file exists for this one endpoint (Task 5 predates any refdata contract-test
 * task), so unlike {@code FinaccountingContractTest} there is no {@code openApi().isValid(...)}
 * assertion here -- this class checks status codes, JSON shape and the allowlist's realm/key
 * behaviour directly instead.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ReferenceDataAllowlistTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void applyMigrations() throws Exception {
        // Nothing else is touched by this endpoint: refdata.reference_code_set is global, not
        // tenant-scoped (refdata/V1's own header), so no product/policy/party schema is needed to
        // exercise it -- only audit/V1 (bootstraps app_role's WORM grants, same as every other
        // contract test) and the four refdata migrations that seed the nine real keys.
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            "db-migrations/refdata/V4__seed_distribution_parameters.sql");
    }

    @Autowired private MockMvc mockMvc;

    @AfterEach
    void resetAfterEach() {
        TenantContext.clear();
    }

    // --- token shapes -------------------------------------------------------------------------

    private static RequestPostProcessor customerOf(UUID tenantId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
            .jwt(builder -> builder.subject("customer").claim("tenant_id", tenantId.toString()));
    }

    private static RequestPostProcessor agentOf(UUID tenantId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
            .jwt(builder -> builder.subject("agent").claim("tenant_id", tenantId.toString())
                .claim("party_id", UUID.randomUUID().toString()));
    }

    /** No refdata-specific fine-grained role exists -- ROLE_REALM_STAFF alone is what {@code
     * ReferenceDataController.mayRead} checks for the staff-reads-everything sentinel. */
    private static RequestPostProcessor staffOf(UUID tenantId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
            .jwt(builder -> builder.subject("staff").claim("tenant_id", tenantId.toString()));
    }

    /** Regulators carry no fine-grained roles -- SecurityConfig synthesises only ROLE_REALM_<REALM>
     * for them. tenant_id is still mandatory: TenantContextFilter 403s any token without one. */
    private static RequestPostProcessor regulatorOf(UUID tenantId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_REGULATORS"))
            .jwt(builder -> builder.subject("tira-regulator").claim("tenant_id", tenantId.toString()));
    }

    // ============================================================================================
    // GET /reference-codes/{codeSetKey}
    // ============================================================================================

    @Test
    void aCustomerCanReadTheLoanInterestRate() throws Exception {
        UUID tenantId = UUID.randomUUID();

        mockMvc.perform(get("/reference-codes/{codeSetKey}", "TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE")
                .with(customerOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.codeSetKey").value("TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE"))
            .andExpect(jsonPath("$.values.length()").value(1))
            .andExpect(jsonPath("$.values[0].code").value("DEFAULT"))
            // Returned as a JSON STRING, never a number -- the column is VARCHAR(255) and a bare
            // 12.0 in the response body would silently reinterpret it as a JSON number.
            .andExpect(jsonPath("$.values[0].value").value("12.0"))
            .andExpect(jsonPath("$.values[0].value").isString());
    }

    @Test
    void aCustomerCannotReadThePremiumPricingBasis() throws Exception {
        UUID tenantId = UUID.randomUUID();

        mockMvc.perform(get("/reference-codes/{codeSetKey}", "TZ_BASE_PREMIUM_RATE_PER_MILLE")
                .with(customerOf(tenantId)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("REFERENCE_CODE_SET_NOT_FOUND"));
    }

    @Test
    void anAgentCanReadTheFieldReceiptSlaThatACustomerCannot() throws Exception {
        UUID tenantId = UUID.randomUUID();

        mockMvc.perform(get("/reference-codes/{codeSetKey}", "OFFLINE_RECEIPT_SLA_HOURS")
                .with(agentOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.values[0].value").value("24"));

        mockMvc.perform(get("/reference-codes/{codeSetKey}", "OFFLINE_RECEIPT_SLA_HOURS")
                .with(customerOf(tenantId)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("REFERENCE_CODE_SET_NOT_FOUND"));
    }

    @Test
    void staffCanReadEverySeededKey() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String[] allNineKeys = {
            "TZ_CONTESTABILITY_MONTHS",
            "TZ_REINSTATEMENT_WINDOW_MONTHS",
            "TZ_SUSPENSION_TO_LAPSE_MONTHS",
            "OFFLINE_RECEIPT_SLA_HOURS",
            "TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE",
            "POLICY_SUSPENSION_ELIGIBLE_CATEGORIES",
            "TZ_BASE_PREMIUM_RATE_PER_MILLE",
            "DUNNING_ESCALATION_DAYS",
            "TZ_COMMISSION_CLAWBACK_MONTHS",
        };

        for (String key : allNineKeys) {
            mockMvc.perform(get("/reference-codes/{codeSetKey}", key).with(staffOf(tenantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codeSetKey").value(key))
                .andExpect(jsonPath("$.values.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        }
    }

    @Test
    void aRegulatorGetsTheDisclosedKeysButNotTheCommercialOnes() throws Exception {
        UUID tenantId = UUID.randomUUID();

        mockMvc.perform(get("/reference-codes/{codeSetKey}", "TZ_CONTESTABILITY_MONTHS")
                .with(regulatorOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.values[0].value").value("24"));

        mockMvc.perform(get("/reference-codes/{codeSetKey}", "TZ_BASE_PREMIUM_RATE_PER_MILLE")
                .with(regulatorOf(tenantId)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("REFERENCE_CODE_SET_NOT_FOUND"));
    }

    @Test
    void anAgentCannotReadThePremiumPricingBasis() throws Exception {
        // TZ_BASE_PREMIUM_RATE_PER_MILLE is in neither PUBLICLY_DISCLOSED nor AGENT_OPERATIONAL --
        // the single most sensitive key on the platform, and agents get no special exemption for
        // it just because they are allowed some commercially-adjacent keys (e.g. the commission
        // clawback window). This is the mirror of aCustomerCannotReadThePremiumPricingBasis /
        // aRegulatorGetsTheDisclosedKeysButNotTheCommercialOnes for the third non-staff realm.
        UUID tenantId = UUID.randomUUID();

        mockMvc.perform(get("/reference-codes/{codeSetKey}", "TZ_BASE_PREMIUM_RATE_PER_MILLE")
                .with(agentOf(tenantId)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("REFERENCE_CODE_SET_NOT_FOUND"));
    }

    @Test
    void regulatorsAndCustomersAreDeniedTheRemainingAgentOperationalKeys() throws Exception {
        // AGENT_OPERATIONAL's three keys are readable by agents (and staff) only. One combination
        // (customer + OFFLINE_RECEIPT_SLA_HOURS) is already covered by
        // anAgentCanReadTheFieldReceiptSlaThatACustomerCannot; this closes the remaining five
        // realm/key denial combinations in one loop rather than five near-duplicate test methods.
        record Denial(String realmName, RequestPostProcessor token, String codeSetKey) {}
        UUID tenantId = UUID.randomUUID();
        List<Denial> denials = List.of(
            new Denial("regulator", regulatorOf(tenantId), "OFFLINE_RECEIPT_SLA_HOURS"),
            new Denial("regulator", regulatorOf(tenantId), "POLICY_SUSPENSION_ELIGIBLE_CATEGORIES"),
            new Denial("regulator", regulatorOf(tenantId), "TZ_COMMISSION_CLAWBACK_MONTHS"),
            new Denial("customer", customerOf(tenantId), "POLICY_SUSPENSION_ELIGIBLE_CATEGORIES"),
            new Denial("customer", customerOf(tenantId), "TZ_COMMISSION_CLAWBACK_MONTHS"));

        for (Denial denial : denials) {
            mockMvc.perform(get("/reference-codes/{codeSetKey}", denial.codeSetKey()).with(denial.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("REFERENCE_CODE_SET_NOT_FOUND"));
        }
    }

    @Test
    void aDeniedKeyAndAnUnknownKeyAreByteIdentical() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String deniedKey = "TZ_BASE_PREMIUM_RATE_PER_MILLE";
        String unknownKey = "NOT_A_REAL_CODE_SET_KEY";

        String deniedBody = mockMvc.perform(get("/reference-codes/{codeSetKey}", deniedKey).with(customerOf(tenantId)))
            .andExpect(status().isNotFound())
            .andReturn().getResponse().getContentAsString();

        String unknownBody = mockMvc.perform(get("/reference-codes/{codeSetKey}", unknownKey).with(customerOf(tenantId)))
            .andExpect(status().isNotFound())
            .andReturn().getResponse().getContentAsString();

        // Both bodies are compared directly, not merely "both are 404". The only two things that
        // may legitimately differ between them are: (1) traceId, which RefdataExceptionHandler
        // stamps fresh with a random UUID on every single call regardless of cause, so it would
        // differ even between two requests for the SAME denied key; and (2) the codeSetKey text
        // itself, which conveys no new information to the caller -- they already know what key
        // they typed into the URL. Normalizing away exactly those two, and nothing else, is what
        // makes this a genuine oracle-resistance check rather than a tautology: any OTHER
        // difference (a distinct errorCode, an extra field, a different HTTP reason phrase, a
        // different message template) would survive this normalization and fail the assertion.
        assertThat(normalizeNotFoundBody(deniedBody, deniedKey))
            .isEqualTo(normalizeNotFoundBody(unknownBody, unknownKey));
    }

    private static String normalizeNotFoundBody(String body, String requestedCodeSetKey) {
        return body
            .replace(requestedCodeSetKey, "<KEY>")
            .replaceAll("\"traceId\":\\s*\"[^\"]*\"", "\"traceId\":\"<TRACE_ID>\"");
    }
}
