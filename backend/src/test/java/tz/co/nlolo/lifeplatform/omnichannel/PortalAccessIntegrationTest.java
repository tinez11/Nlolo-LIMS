package tz.co.nlolo.lifeplatform.omnichannel;

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
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
import tz.co.nlolo.lifeplatform.omnichannel.application.CustomerAccounts;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Inviting a client to the customer portal (2026-10-08, the customer portal design step 1). Keycloak is replaced by a
 * recording fake: what is under test is who may be invited, what the login is created with, how the first password
 * reaches the customer, and the access's life -- invited, signed in, revoked, invited again.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import({AccumulationTestFixtures.class, PortalAccessIntegrationTest.FakeAccounts.class})
class PortalAccessIntegrationTest {

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
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            DepositTestMigrations.ALL);
    }

    private static final UUID TENANT = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));

    /** Keycloak, as the platform asks things of it. */
    static class Recording implements CustomerAccounts {
        final List<String> calls = new ArrayList<>();
        @Override public String create(String username, String email, String displayName, UUID tenantId, UUID partyId) {
            calls.add("create " + username + " email=" + email + " tenant=" + tenantId + " party=" + partyId);
            return "kc-" + partyId;
        }
        @Override public void sendSetPasswordLink(String userId) { calls.add("link " + userId); }
        @Override public void setTemporaryPassword(String userId, String password) { calls.add("password " + userId); }
        @Override public void setEnabled(String userId, boolean enabled) { calls.add((enabled ? "enable " : "disable ") + userId); }
    }

    @TestConfiguration
    static class FakeAccounts {
        @Bean @Primary Recording recordingAccounts() { return new Recording(); }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private AccumulationTestFixtures fixtures;
    @Autowired private PolicyApi policyApi;
    @Autowired private PartyApi partyApi;
    @Autowired private Recording keycloak;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clearCalls() { keycloak.calls.clear(); }

    private static <T> T asTenant(Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }

    private static MockHttpServletRequestBuilder staff(MockHttpServletRequestBuilder request, String... roles) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>(List.of(new SimpleGrantedAuthority("ROLE_REALM_STAFF")));
        for (String role : roles) authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        return request.with(jwt().authorities(authorities.toArray(new SimpleGrantedAuthority[0]))
            .jwt(b -> b.subject("csr-1").claim("name", "Rose Service").claim("tenant_id", TENANT.toString())));
    }

    private static MockHttpServletRequestBuilder customer(MockHttpServletRequestBuilder request, UUID partyId) {
        return request.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
            .jwt(b -> b.claim("tenant_id", TENANT.toString()).claim("party_id", partyId.toString())));
    }

    /** A policyholder with a phone number and no email -- the savings fixture's client. */
    private UUID policyholder() {
        String policyNumber = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, TODAY).policyNumber();
        return asTenant(() -> policyApi.getPolicy(policyNumber)).policyholderPartyId();
    }

    @Test
    void aPolicyholderWithOnlyAPhoneGetsAOneTimePasswordAndTheAccessLivesUntilRevokedAndCanBeOpenedAgain()
            throws Exception {
        UUID partyId = policyholder();
        String base = "/parties/" + partyId + "/portal-access";

        mockMvc.perform(staff(get(base))).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("NOT_INVITED"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC));

        String invited = mockMvc.perform(staff(post(base), "CUSTOMER_SERVICE_REP"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.access.status").value("INVITED"))
            .andExpect(jsonPath("$.access.delivery").value("TEMPORARY_PASSWORD"))
            .andExpect(jsonPath("$.access.invitedBy").value("Rose Service"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andReturn().getResponse().getContentAsString();
        String password = JsonPath.read(invited, "$.temporaryPassword");
        assertThat(password).hasSize(12).doesNotContainPattern("[0O1lI]");
        // The login carries the tenant and party the platform chose -- nobody typed them into Keycloak.
        assertThat(keycloak.calls).hasSize(2);
        assertThat(keycloak.calls.get(0)).startsWith("create +25571500").contains("email=null")
            .contains("tenant=" + TENANT).contains("party=" + partyId);
        assertThat(keycloak.calls.get(1)).isEqualTo("password kc-" + partyId);

        mockMvc.perform(staff(post(base), "CUSTOMER_SERVICE_REP"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PORTAL_ACCESS_EXISTS"));

        // The customer signs in: their own record, and the invite is used.
        mockMvc.perform(customer(get("/customer/me"), partyId)).andExpect(status().isOk())
            .andExpect(jsonPath("$.partyId").value(partyId.toString()))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC));
        mockMvc.perform(staff(get(base))).andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.activatedAt").isNotEmpty());

        String resent = mockMvc.perform(staff(post(base + "/resend"), "CUSTOMER_SERVICE_REP"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(resent, "$.temporaryPassword")).hasSize(12).isNotEqualTo(password);

        mockMvc.perform(staff(delete(base), "ADMIN")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REVOKED")).andExpect(jsonPath("$.revokedBy").value("Rose Service"));
        assertThat(keycloak.calls).contains("disable kc-" + partyId);

        keycloak.calls.clear();
        mockMvc.perform(staff(post(base), "CUSTOMER_SERVICE_REP")).andExpect(status().isCreated())
            .andExpect(jsonPath("$.access.status").value("INVITED"));
        assertThat(keycloak.calls).containsExactly("enable kc-" + partyId, "password kc-" + partyId);
    }

    @Test
    void aPolicyholderWithAnEmailIsSentTheSetPasswordLinkAndNoPasswordIsReturned() throws Exception {
        UUID partyId = policyholder();
        jdbc.update("UPDATE party.party SET email = 'Amina.Juma@Example.tz' WHERE party_id = ?", partyId);

        mockMvc.perform(staff(post("/parties/" + partyId + "/portal-access"), "CUSTOMER_SERVICE_REP"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.access.delivery").value("EMAIL_LINK"))
            .andExpect(jsonPath("$.access.username").value("amina.juma@example.tz"))
            .andExpect(jsonPath("$.temporaryPassword").doesNotExist());
        assertThat(keycloak.calls).containsExactly(
            "create amina.juma@example.tz email=Amina.Juma@Example.tz tenant=" + TENANT + " party=" + partyId,
            "link kc-" + partyId);
    }

    @Test
    void onlyAPolicyholderPersonCanBeInvitedAndOnlyCustomerServiceOrAnAdminInvites() throws Exception {
        UUID noPolicy = asTenant(() -> partyApi.registerIndividual("No Policy Yet", LocalDate.of(1990, 1, 1),
            "+255715999001", null, "test")).partyId();
        mockMvc.perform(staff(post("/parties/" + noPolicy + "/portal-access"), "CUSTOMER_SERVICE_REP"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("holds no policy")));

        UUID company = asTenant(() -> partyApi.registerCorporate("Nlolo Traders Ltd", "REG-PORTAL-1", "+255715999002",
            null, "test")).partyId();
        mockMvc.perform(staff(post("/parties/" + company + "/portal-access"), "CUSTOMER_SERVICE_REP"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Only a person")));

        UUID holder = policyholder();
        mockMvc.perform(staff(post("/parties/" + holder + "/portal-access"), "UNDERWRITER"))
            .andExpect(status().isForbidden());
        mockMvc.perform(customer(post("/parties/" + holder + "/portal-access"), holder))
            .andExpect(status().isForbidden());
        assertThat(keycloak.calls).isEmpty();
    }
}
