package tz.co.nlolo.lifeplatform.dashboard;

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import tz.co.nlolo.lifeplatform.dashboard.api.Money;
import tz.co.nlolo.lifeplatform.dashboard.api.UnderwritingDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.application.UnderwritingDashboard;
import tz.co.nlolo.lifeplatform.reporting.ReportingTestMigrations;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The dashboards over real HTTP: who may read which, and that each response is what the spec says.
 *
 * <p>The composers are mocked. What they compute is their own unit tests' business; this class proves the wire --
 * security per role, and the shape against {@code openapi-dashboard.yaml} with {@code additionalProperties: false}, so
 * a field added to a view without the spec fails here. The database is the reporting test set only because the
 * application context needs one to start; nothing here reads it.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class DashboardContractTest {

    private static final String SPEC_PATH = "api/openapi/openapi-dashboard.yaml";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void migrate() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            ReportingTestMigrations.ALL);
    }

    @Autowired private MockMvc mockMvc;
    @MockBean private UnderwritingDashboard underwriting;

    private static RequestPostProcessor staff(String... roles) {
        SimpleGrantedAuthority[] authorities = new SimpleGrantedAuthority[roles.length + 1];
        authorities[0] = new SimpleGrantedAuthority("ROLE_REALM_STAFF");
        for (int i = 0; i < roles.length; i++) {
            authorities[i + 1] = new SimpleGrantedAuthority("ROLE_" + roles[i]);
        }
        return jwt().authorities(authorities)
            .jwt(b -> b.subject("staff-user").claim("tenant_id", UUID.randomUUID().toString()));
    }

    @Test
    void anUnderwriterReadsTheUnderwritingDashboard() throws Exception {
        when(underwriting.build()).thenReturn(new UnderwritingDashboardView(238L, 41L, null, List.of(
            new UnderwritingDashboardView.QueuedCase(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(),
                new Money("25000000.00", "TZS"), "OPEN"))));

        mockMvc.perform(get("/dashboards/underwriting").with(staff("UNDERWRITER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.openCases").value(238))
            // Could not be read: null on the wire, never 0.
            .andExpect(jsonPath("$.casesAwaitingIssue").isEmpty())
            .andExpect(jsonPath("$.queue[0].sumAssured.amount").value("25000000.00"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void aClaimsAssessorIsRefusedTheUnderwritingDashboard() throws Exception {
        mockMvc.perform(get("/dashboards/underwriting").with(staff("CLAIMS_ASSESSOR")))
            .andExpect(status().isForbidden());
    }
}
