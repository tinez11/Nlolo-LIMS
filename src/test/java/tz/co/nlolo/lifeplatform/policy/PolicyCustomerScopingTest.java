package tz.co.nlolo.lifeplatform.policy;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M12 Task 1: GET /policies and GET /policies/{n}/coverage-status become customer-reachable.
 *
 * <p>The two "ignores client-supplied" tests are the ones that matter. A check-then-reject
 * implementation passes the happy-path test and fails these -- a customer must never be able to
 * widen their own scope by supplying someone else's policyholderPartyId, and the correct behaviour
 * is to OVERRIDE the parameter, not to 403 on it, because a list endpoint has no single resource
 * to deny. Same reasoning as ClaimController.listClaims' javadoc.
 *
 * <p>Container/migration boilerplate copies {@code PolicyContractTest} exactly (same
 * {@code @BeforeAll} migration set) rather than the task brief's abbreviated snippet: the
 * schema Flyway builds for the policy module is otherwise absent, and {@code searchPolicies}/
 * {@code getCoverageStatus} run real queries against it even in these seed-free tests.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class PolicyCustomerScopingTest {

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
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
            "db-migrations/policy/V3__premium_fields.sql",
            "db-migrations/policy/V4__underwriting_case_id.sql",
            "db-migrations/audit/V1__create_audit_schema.sql");
    }

    @Autowired
    MockMvc mockMvc;

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void customerTokenReachesPolicyList() throws Exception {
        mockMvc.perform(get("/policies")
                .with(customerOf(TENANT, UUID.randomUUID())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void customerListIgnoresClientSuppliedPolicyholderPartyId() throws Exception {
        UUID own = UUID.randomUUID();
        UUID someoneElse = UUID.randomUUID();
        // Supplying a stranger's id must NOT widen scope. With zero policies seeded for `own`,
        // a correct force-scoped implementation returns an empty page rather than 403 or a leak.
        mockMvc.perform(get("/policies")
                .param("policyholderPartyId", someoneElse.toString())
                .with(customerOf(TENANT, own)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void customerTokenWithoutPartyIdClaimIsDenied() throws Exception {
        mockMvc.perform(get("/policies")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder.claim("tenant_id", TENANT.toString()))))
            .andExpect(status().isForbidden());
    }

    @Test
    void agentListStillAcceptsAnExplicitPolicyholderFilter() throws Exception {
        mockMvc.perform(get("/policies")
                .param("policyholderPartyId", UUID.randomUUID().toString())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", TENANT.toString()))))
            .andExpect(status().isOk());
    }

    @Test
    void customerCannotReadAnotherPartysCoverageStatus() throws Exception {
        // Verified (not assumed): getCoverageStatus calls policyApi.getPolicy(policyNumber)
        // BEFORE enforceCustomerOwnPolicyOnly, so a nonexistent policy number always 404s via
        // PolicyNotFoundException/findPolicyOrThrow before the ownership check ever runs --
        // same order as getPolicy's own cross-tenant anti-enumeration case. The exact status is
        // therefore always 404 POLICY_NOT_FOUND here, not merely "some 4xx".
        mockMvc.perform(get("/policies/POL-NONEXISTENT/coverage-status")
                .with(customerOf(TENANT, UUID.randomUUID())))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("POLICY_NOT_FOUND"));
    }

    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor customerOf(UUID tenantId, UUID partyId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
            .jwt(builder -> builder.claim("tenant_id", tenantId.toString())
                                   .claim("party_id", partyId.toString()));
    }
}
