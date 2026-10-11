package tz.co.nlolo.lifeplatform.reporting;

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.reporting.ReportingTestEvents.Sale;

import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(ReportingTestEvents.class)
class ReportingContractTest {

    private static final String SPEC = "api/openapi/openapi-reporting.yaml";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
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
    @Autowired private ReportingTestEvents events;
    private final UUID tenant = UUID.randomUUID();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static RequestPostProcessor staff(UUID tenant, String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_" + role))
            .jwt(b -> b.subject(role.toLowerCase()).claim("tenant_id", tenant.toString()));
    }

    @Test
    void theCatalogueIsReadableByEachFinanceRoleAndAdmin() throws Exception {
        for (String role : new String[] { "FINANCE_OFFICER", "FINANCE_APPROVER", "ADMIN" }) {
            mockMvc.perform(get("/reports/catalogue").with(staff(tenant, role)))
                .andExpect(status().isOk())
                .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
                .andExpect(jsonPath("$[0].key").value("policies-in-force"));
        }
    }

    @Test
    void anUnderwriterIsRefused() throws Exception {
        mockMvc.perform(get("/reports/catalogue").with(staff(tenant, "UNDERWRITER"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/reports/policies-in-force").param("asOf", "2026-01-31").with(staff(tenant, "UNDERWRITER")))
            .andExpect(status().isForbidden());
    }

    @Test
    void aReportRunsAndMatchesTheContractWithStringFigures() throws Exception {
        events.sell(tenant, Sale.of("W-1", UUID.randomUUID(), LocalDate.parse("2026-01-05")));
        mockMvc.perform(get("/reports/policies-in-force").param("asOf", "2026-01-31").with(staff(tenant, "FINANCE_OFFICER")))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andExpect(jsonPath("$.sections[0].rows[0].policies").value("1"))
            .andExpect(jsonPath("$.sections[0].rows[0].policies").isString())
            .andExpect(jsonPath("$.sections[0].rows[0].sumAssured").isString());
    }

    @Test
    void aMissingOrMismatchedDateIs400AndAnUnknownReport404() throws Exception {
        mockMvc.perform(get("/reports/policies-in-force").with(staff(tenant, "FINANCE_OFFICER")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("REPORT_PARAMETER_INVALID"));
        mockMvc.perform(get("/reports/policies-in-force").param("asOf", "31/01/2026").with(staff(tenant, "FINANCE_OFFICER")))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/reports/no-such-report").param("asOf", "2026-01-31").with(staff(tenant, "FINANCE_OFFICER")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("REPORT_NOT_FOUND"));
    }

    @Test
    void onlyAnAdminMayRebuild() throws Exception {
        mockMvc.perform(post("/reports/rebuild").with(staff(tenant, "FINANCE_OFFICER"))).andExpect(status().isForbidden());
        mockMvc.perform(post("/reports/rebuild").with(staff(tenant, "ADMIN")))
            .andExpect(status().isAccepted())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC));
        mockMvc.perform(get("/reports/rebuild/status").with(staff(tenant, "FINANCE_OFFICER")))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC));
    }

    @Test
    void aReportDownloadsAsEachFormat() throws Exception {
        events.sell(tenant, Sale.of("X-1", UUID.randomUUID(), LocalDate.parse("2026-01-05")));
        for (String[] f : new String[][] { { "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" },
                { "csv", "text/csv" }, { "pdf", "application/pdf" } }) {
            mockMvc.perform(get("/reports/policies-in-force/export").param("asOf", "2026-01-31").param("format", f[0])
                    .with(staff(tenant, "FINANCE_APPROVER")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", startsWith(f[1])))
                .andExpect(header().string("Content-Disposition", containsString("." + f[0])));
        }
        mockMvc.perform(get("/reports/policies-in-force/export").param("asOf", "2026-01-31").param("format", "docx")
                .with(staff(tenant, "FINANCE_APPROVER")))
            .andExpect(status().isBadRequest());
    }
}
