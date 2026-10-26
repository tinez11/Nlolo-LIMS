package tz.co.nlolo.lifeplatform.iam;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
import tz.co.nlolo.lifeplatform.iam.application.AccessService;
import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The platform holds staff access (2026-10-26): first sight imports the Keycloak roles once, and from then on the
 * platform -- not the token -- says what a person may do.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class AccessIntegrationTest {

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
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccessService accessService;

    private static RequestPostProcessor staff(UUID tenant, UUID user, String... roles) {
        SimpleGrantedAuthority[] authorities = new SimpleGrantedAuthority[roles.length + 1];
        authorities[0] = new SimpleGrantedAuthority("ROLE_REALM_STAFF");
        for (int i = 0; i < roles.length; i++) authorities[i + 1] = new SimpleGrantedAuthority("ROLE_" + roles[i]);
        return jwt().authorities(Arrays.asList(authorities))
            .jwt(b -> b.subject(user.toString()).claim("tenant_id", tenant.toString())
                .claim("preferred_username", "staff." + user.toString().substring(0, 6)).claim("name", "Neema Staff"));
    }

    @Test
    void firstSightImportsTheKeycloakRolesOnceAndThePlatformDecidesAfterThat() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();

        mockMvc.perform(get("/me/access").with(staff(tenant, user, "UNDERWRITER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.primaryRole.name").value("Underwriter"))
            .andExpect(jsonPath("$.permissions", hasItem("underwriting.decide")))
            .andExpect(jsonPath("$.permissions", not(hasItem("ledger.view"))))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM iam.role WHERE tenant_id = ? AND built_in", Integer.class,
            tenant)).isEqualTo(8);

        // Keycloak now says finance; the platform already holds this person, so it is not read again.
        accessService.evict(tenant, user);
        mockMvc.perform(get("/me/access").with(staff(tenant, user, "FINANCE_OFFICER")))
            .andExpect(jsonPath("$.roles.length()").value(1))
            .andExpect(jsonPath("$.roles[0].code").value("UNDERWRITER"));
    }

    @Test
    void theBadgeShowsOneRoleTheMostSeniorHeld() throws Exception {
        mockMvc.perform(get("/me/access").with(staff(UUID.randomUUID(), UUID.randomUUID(),
                "FINANCE_OFFICER", "CLAIMS_ASSESSOR", "ADMIN")))
            .andExpect(jsonPath("$.roles.length()").value(3))
            .andExpect(jsonPath("$.primaryRole.code").value("ADMIN"));
    }

    @Test
    void aSinglePermissionAddsToTheRolesAndAnUnknownOneIsHeldByNobody() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        mockMvc.perform(get("/me/access").with(staff(tenant, user, "CLAIMS_ASSESSOR"))).andExpect(status().isOk());

        jdbc.update("INSERT INTO iam.user_permission (tenant_id, user_id, permission, granted_by) VALUES (?, ?, ?, 'test')",
            tenant, user, "ledger.view");
        jdbc.update("INSERT INTO iam.user_permission (tenant_id, user_id, permission, granted_by) VALUES (?, ?, ?, 'test')",
            tenant, user, "nonsense.retired");
        accessService.evict(tenant, user);

        mockMvc.perform(get("/me/access").with(staff(tenant, user, "CLAIMS_ASSESSOR")))
            .andExpect(jsonPath("$.permissions", hasItem("ledger.view")))
            .andExpect(jsonPath("$.permissions", hasItem("claims.assess")))
            .andExpect(jsonPath("$.permissions", not(hasItem("nonsense.retired"))));
    }

    @Test
    void aDeactivatedPersonHoldsNothing() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        mockMvc.perform(get("/me/access").with(staff(tenant, user, "ADMIN"))).andExpect(status().isOk());

        jdbc.update("UPDATE iam.staff_user SET active = false WHERE tenant_id = ? AND user_id = ?", tenant, user);
        accessService.evict(tenant, user);

        mockMvc.perform(get("/me/access").with(staff(tenant, user, "ADMIN")))
            .andExpect(jsonPath("$.active").value(false))
            .andExpect(jsonPath("$.permissions.length()").value(0))
            .andExpect(jsonPath("$.primaryRole").doesNotExist())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC));
    }

    @Test
    void theFirstSightIsRecordedInTheHistory() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        mockMvc.perform(get("/me/access").with(staff(tenant, user, "UNDERWRITER", "SENIOR_UNDERWRITER")))
            .andExpect(status().isOk());

        String detail = jdbc.queryForObject("SELECT detail::text FROM iam.access_change WHERE tenant_id = ? AND user_id = ? "
            + "AND change_type = 'IMPORTED'", String.class, tenant, user);
        assertThat(detail).contains("UNDERWRITER").contains("SENIOR_UNDERWRITER");
    }

    @Test
    void anAgentHasNoPlatformAccessToRead() throws Exception {
        mockMvc.perform(get("/me/access").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                .jwt(b -> b.subject(UUID.randomUUID().toString()).claim("tenant_id", UUID.randomUUID().toString()))))
            .andExpect(status().isForbidden());
    }
}
