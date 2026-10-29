package tz.co.nlolo.lifeplatform.iam;

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import tz.co.nlolo.lifeplatform.iam.application.StaffLogins;

import java.util.Arrays;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Users and Roles screens' rules (2026-10-26): not yourself, a second administrator for Administration, never the
 * last administrator, single permissions add to roles. The Keycloak login half is a mock -- what is proven here is
 * what the platform decides and records.
 */
@Testcontainers
@SpringBootTest(classes = Application.class, properties = "app.access.source=platform")
@AutoConfigureMockMvc
class AccessAdministrationIntegrationTest {

    private static final String SPEC = "api/openapi/openapi-iam.yaml";

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void applyMigration() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/iam/V1__access_control.sql");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper json;
    @MockBean private StaffLogins logins;

    private UUID tenant;
    private UUID asha;      // an administrator
    private UUID baraka;    // a second administrator

    private static RequestPostProcessor staff(UUID tenant, UUID user, String name, String... roles) {
        SimpleGrantedAuthority[] authorities = new SimpleGrantedAuthority[roles.length + 1];
        authorities[0] = new SimpleGrantedAuthority("ROLE_REALM_STAFF");
        for (int i = 0; i < roles.length; i++) authorities[i + 1] = new SimpleGrantedAuthority("ROLE_" + roles[i]);
        return jwt().authorities(Arrays.asList(authorities))
            .jwt(b -> b.subject(user.toString()).claim("tenant_id", tenant.toString()).claim("name", name)
                .claim("preferred_username", name.toLowerCase().replace(' ', '.')));
    }

    private RequestPostProcessor asha() { return staff(tenant, asha, "Asha Admin", "ADMIN"); }
    private RequestPostProcessor baraka() { return staff(tenant, baraka, "Baraka Admin", "ADMIN"); }

    @BeforeEach
    void twoAdministrators() throws Exception {
        tenant = UUID.randomUUID();
        asha = UUID.randomUUID();
        baraka = UUID.randomUUID();
        // First sight records both, with the ADMIN role their Keycloak tokens carried.
        mockMvc.perform(get("/me/access").with(asha())).andExpect(status().isOk());
        mockMvc.perform(get("/me/access").with(baraka())).andExpect(status().isOk());
    }

    private UUID addUser(String username, String... roles) throws Exception {
        UUID id = UUID.randomUUID();
        when(logins.create(any())).thenReturn(id);
        String body = json.writeValueAsString(java.util.Map.of("username", username, "firstName", "Neema",
            "lastName", "Mushi", "email", username + "@nlolo.co.tz", "homeBranch", "DSM",
            "roleCodes", java.util.List.of(roles), "permissions", java.util.List.of()));
        mockMvc.perform(post("/access/users").with(asha()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.delivery").value("EMAIL_LINK"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC));
        return id;
    }

    @Test
    void anAdministratorAddsAUserWithARoleAndTheLoginGetsTheIdentityProvidersSetPasswordEmail() throws Exception {
        UUID neema = addUser("neema.mushi", "UNDERWRITER");

        verify(logins).create(any());
        verify(logins).sendSetPasswordLink(neema);
        mockMvc.perform(get("/access/users/{id}", neema).with(asha()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.roles[0].code").value("UNDERWRITER"))
            .andExpect(jsonPath("$.user.effectivePermissions", hasItem("underwriting.decide")))
            .andExpect(jsonPath("$.history[0].changeType").value("ACCESS_CHANGED"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC));
    }

    /** No email to send a link to: a one-time password, returned once -- the portal invite's last resort. */
    @Test
    void withNoEmailTheAdministratorIsGivenAOneTimePasswordOnce() throws Exception {
        UUID id = UUID.randomUUID();
        when(logins.create(any())).thenReturn(id);
        mockMvc.perform(post("/access/users").with(asha()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"juma.noemail\",\"firstName\":\"Juma\",\"lastName\":\"Hassan\","
                    + "\"roleCodes\":[\"CLAIMS_ASSESSOR\"],\"permissions\":[]}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.delivery").value("TEMPORARY_PASSWORD"))
            .andExpect(jsonPath("$.temporaryPassword").isString())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC));
        verify(logins).setTemporaryPassword(eq(id), any());
    }

    @Test
    void aSinglePermissionAddsToTheRoleAndTakesEffectAtOnce() throws Exception {
        UUID neema = addUser("neema.single", "CLAIMS_ASSESSOR");
        mockMvc.perform(put("/access/users/{id}/access", neema).with(asha()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"roleCodes\":[\"CLAIMS_ASSESSOR\"],\"permissions\":[\"ledger.view\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.applied").value(true));

        mockMvc.perform(get("/me/access").with(staff(tenant, neema, "Neema Mushi")))
            .andExpect(jsonPath("$.permissions", hasItem("ledger.view")))
            .andExpect(jsonPath("$.permissions", hasItem("claims.assess")));
    }

    @Test
    void noOneChangesTheirOwnAccess() throws Exception {
        mockMvc.perform(put("/access/users/{id}/access", asha).with(asha()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"roleCodes\":[\"ADMIN\",\"FINANCE_APPROVER\"],\"permissions\":[]}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("ACCESS_SELF_CHANGE"));
        mockMvc.perform(put("/access/users/{id}/active", asha).with(asha()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void noOneChangesARoleTheyHold() throws Exception {
        String adminRole = roleId("ADMIN");
        mockMvc.perform(put("/access/roles/{id}", adminRole).with(asha()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":[\"users.view\",\"users.manage\",\"roles.manage\",\"ledger.approve\"]}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("ACCESS_SELF_CHANGE"));
    }

    /** No second administrator (2026-10-28, user decision): giving someone Administration takes effect at once. */
    @Test
    void givingSomeoneAdministrationTakesEffectAtOnce() throws Exception {
        UUID neema = addUser("neema.admin", "UNDERWRITER");
        mockMvc.perform(put("/access/users/{id}/access", neema).with(asha())
                .contentType(MediaType.APPLICATION_JSON).content("{\"roleCodes\":[\"UNDERWRITER\",\"ADMIN\"],\"permissions\":[]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.applied").value(true));

        mockMvc.perform(get("/access/users/{id}", neema).with(asha()))
            .andExpect(jsonPath("$.user.pendingApproval").value(false))
            .andExpect(jsonPath("$.user.effectivePermissions", hasItem("users.manage")));
    }

    @Test
    void aDeactivatedPersonIsOutAtOnceAndTheirLoginIsClosed() throws Exception {
        mockMvc.perform(put("/access/users/{id}/active", baraka).with(asha()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}"))
            .andExpect(status().isNoContent());
        verify(logins).setEnabled(eq(baraka), eq(false));

        // Baraka is out: his next request holds nothing, whatever his token still says.
        mockMvc.perform(get("/access/users").with(baraka())).andExpect(status().isForbidden());
        mockMvc.perform(get("/access/users/{id}", baraka).with(asha()))
            .andExpect(jsonPath("$.user.active").value(false))
            .andExpect(jsonPath("$.history[0].changeType").value("USER_DEACTIVATED"));
    }

    @Test
    void aRoleOfTheAdministratorsOwnMakingCanBeCreatedAndGiven() throws Exception {
        mockMvc.perform(post("/access/roles").with(asha()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"branch manager\",\"name\":\"Branch manager\",\"permissions\":"
                    + "[\"clients.view\",\"policies.view\",\"collections.view\"]}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value("BRANCH_MANAGER"))
            .andExpect(jsonPath("$.builtIn").value(false))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC));

        UUID neema = addUser("neema.branch", "BRANCH_MANAGER");
        mockMvc.perform(get("/me/access").with(staff(tenant, neema, "Neema Mushi")))
            .andExpect(jsonPath("$.primaryRole.name").value("Branch manager"))
            .andExpect(jsonPath("$.permissions", hasItem("collections.view")))
            .andExpect(jsonPath("$.permissions", not(hasItem("underwriting.view"))));
    }

    @Test
    void someoneWithoutUsersManageCannotChangeAnyone() throws Exception {
        UUID clerk = UUID.randomUUID();
        mockMvc.perform(put("/access/users/{id}/access", asha).with(staff(tenant, clerk, "Clerk Person", "CUSTOMER_SERVICE_REP"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"roleCodes\":[],\"permissions\":[]}"))
            .andExpect(status().isForbidden());
    }

    private String roleId(String code) throws Exception {
        JsonNode roles = json.readTree(mockMvc.perform(get("/access/roles").with(asha()))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andReturn().getResponse().getContentAsString());
        for (JsonNode r : roles) if (code.equals(r.get("code").asText())) return r.get("roleId").asText();
        throw new AssertionError("no role " + code);
    }
}
