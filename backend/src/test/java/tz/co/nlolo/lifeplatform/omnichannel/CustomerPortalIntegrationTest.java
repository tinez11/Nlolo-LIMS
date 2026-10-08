package tz.co.nlolo.lifeplatform.omnichannel;

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.accumulation.AccumulationTestFixtures;
import tz.co.nlolo.lifeplatform.accumulation.DepositTestMigrations;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.function.Supplier;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The customer portal's dashboard and policy page (2026-10-08, the customer portal design step 2): a policyholder reads
 * their own business, every figure from the module that owns it, and nobody else's -- a different customer asking for
 * the policy is refused, and the dashboard can only ever be the token's own.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(AccumulationTestFixtures.class)
class CustomerPortalIntegrationTest {

    private static final String SPEC = "api/openapi/openapi-omnichannel.yaml";

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
        // The savings stack, and claims: the dashboard counts claims in progress and the policy page lists them.
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            java.util.stream.Stream.concat(java.util.Arrays.stream(DepositTestMigrations.ALL), java.util.stream.Stream.of(
                "db-migrations/claims/V1__create_claims_schema.sql",
                "db-migrations/claims/V2__grants_rls_money_checks_evidence_and_settlement_columns.sql",
                "db-migrations/claims/V3__registration_idempotency_key.sql",
                "db-migrations/claims/V4__rls_fail_closed.sql",
                "db-migrations/claims/V5__claim_policy_member.sql",
                "db-migrations/claims/V6__exclusion_decline.sql",
                "db-migrations/claims/V7__claim_assessment_assessor_name.sql",
                "db-migrations/claims/V8__claim_evidence_uploaded_by_name.sql",
                "db-migrations/claims/V9__zero_annuity_settlement.sql",
                "db-migrations/claims/V10__funeral_claims.sql")).toArray(String[]::new));
    }

    private static final UUID TENANT = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));

    @Autowired private MockMvc mockMvc;
    @Autowired private AccumulationTestFixtures fixtures;
    @Autowired private PolicyApi policyApi;

    private static <T> T asTenant(Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }

    private static MockHttpServletRequestBuilder customer(MockHttpServletRequestBuilder request, UUID partyId) {
        return request.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
            .jwt(b -> b.claim("tenant_id", TENANT.toString()).claim("party_id", partyId.toString())));
    }

    @Test
    void aPolicyholderSeesTheirDashboardAndPolicyAndAnotherCustomerIsRefused() throws Exception {
        String policyNumber = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, TODAY).policyNumber();
        UUID holder = asTenant(() -> policyApi.getPolicy(policyNumber)).policyholderPartyId();

        mockMvc.perform(customer(get("/customer/dashboard"), holder))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andExpect(jsonPath("$.displayName").value(org.hamcrest.Matchers.startsWith("Savings Test Life")))
            .andExpect(jsonPath("$.policies.length()").value(1))
            .andExpect(jsonPath("$.policies[0].policyNumber").value(policyNumber))
            .andExpect(jsonPath("$.policies[0].productName").value("Savings Test Product"))
            // An offer awaiting its first premium: that premium is what is due next.
            .andExpect(jsonPath("$.nextPremium.policyNumber").value(policyNumber))
            .andExpect(jsonPath("$.claimsInProgress").value(0));

        mockMvc.perform(customer(get("/customer/policies/" + policyNumber), holder))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andExpect(jsonPath("$.summary.policyNumber").value(policyNumber))
            .andExpect(jsonPath("$.lifeAssuredName").value(org.hamcrest.Matchers.startsWith("Savings Test Life")))
            .andExpect(jsonPath("$.coveredLives").doesNotExist())
            .andExpect(jsonPath("$.claims.length()").value(0));

        // Somebody else's customer token: the policy is not theirs, and their dashboard is empty.
        UUID stranger = UUID.randomUUID();
        mockMvc.perform(customer(get("/customer/policies/" + policyNumber), stranger))
            .andExpect(status().isForbidden());
    }

    @Test
    void staffAndAgentsDoNotUseTheCustomersOwnEndpoints() throws Exception {
        mockMvc.perform(get("/customer/dashboard").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                .jwt(b -> b.claim("tenant_id", TENANT.toString()))))
            .andExpect(status().isForbidden());
    }
}
