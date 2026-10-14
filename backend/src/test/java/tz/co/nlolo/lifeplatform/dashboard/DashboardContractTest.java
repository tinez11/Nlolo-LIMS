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
import tz.co.nlolo.lifeplatform.dashboard.api.ClaimsDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.api.FinanceDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.api.Money;
import tz.co.nlolo.lifeplatform.dashboard.api.UnderwritingDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.application.ClaimsDashboard;
import tz.co.nlolo.lifeplatform.dashboard.application.FinanceDashboard;
import tz.co.nlolo.lifeplatform.dashboard.application.UnderwritingDashboard;
import tz.co.nlolo.lifeplatform.reporting.ReportingTestMigrations;

import java.time.LocalDate;
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
    @MockBean private ClaimsDashboard claims;
    @MockBean private FinanceDashboard finance;

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

    @Test
    void aClaimsManagerReadsTheClaimsDashboardWithTheSettlementQueue() throws Exception {
        when(claims.build(true)).thenReturn(new ClaimsDashboardView(17L, 9L, 6L, 4L, 2L, 128L, "SETTLEMENT_REQUESTED",
            List.of(new ClaimsDashboardView.QueuedClaim(UUID.randomUUID(), "POL-00A4B7A1", UUID.randomUUID(), "DEATH",
                LocalDate.of(2026, 9, 29), "SETTLEMENT_REQUESTED"))));

        mockMvc.perform(get("/dashboards/claims").with(staff("CLAIMS_MANAGER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.queueStatus").value("SETTLEMENT_REQUESTED"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void anAssessorGetsTheAssessorsQueue() throws Exception {
        when(claims.build(false)).thenReturn(new ClaimsDashboardView(17L, 9L, 6L, 4L, 2L, 128L, "REGISTERED", List.of()));

        mockMvc.perform(get("/dashboards/claims").with(staff("CLAIMS_ASSESSOR")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.queueStatus").value("REGISTERED"));
    }

    @Test
    void anUnderwriterIsRefusedTheClaimsDashboard() throws Exception {
        mockMvc.perform(get("/dashboards/claims").with(staff("UNDERWRITER"))).andExpect(status().isForbidden());
    }

    @Test
    void aFinanceOfficerReadsTheFinanceDashboard() throws Exception {
        when(finance.build(org.mockito.ArgumentMatchers.any())).thenReturn(new FinanceDashboardView(
            "2026-09", new Money("50204310.00", "TZS"), "2.2", "2026-10", new Money("742580120.00", "TZS"),
            new Money("512340210.00", "TZS"), "2026-01", "2026-10", new Money("-1200.00", "TZS"),
            4L, 7L, 3L, null,
            List.of(new FinanceDashboardView.MonthPoint("2026-09", new Money("50204310.00", "TZS"))), List.of(),
            "2026-10"));

        mockMvc.perform(get("/dashboards/finance").with(staff("FINANCE_OFFICER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.premiumsCollected.amount").value("50204310.00"))
            .andExpect(jsonPath("$.arrearsAtLapse").isEmpty())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void anUnderwriterIsRefusedTheFinanceDashboard() throws Exception {
        mockMvc.perform(get("/dashboards/finance").with(staff("UNDERWRITER"))).andExpect(status().isForbidden());
    }
}
