package tz.co.nlolo.lifeplatform.approvals;

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
import tz.co.nlolo.lifeplatform.accumulation.AccumulationTestFixtures;
import tz.co.nlolo.lifeplatform.accumulation.DepositTestMigrations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /approvals} over the wire against its spec (2026-10-27), with a real withdrawal waiting: the second
 * signer sees it to approve, the asker sees it as theirs and not to count, and somebody without accounts.approve does
 * not see the kind at all.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(AccumulationTestFixtures.class)
class ApprovalsContractTest {

    private static final String SPEC_PATH = "api/openapi/openapi-approvals.yaml";
    private static final UUID TENANT = UUID.randomUUID();

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

    @Autowired private MockMvc mockMvc;
    @Autowired private AccumulationTestFixtures fixtures;

    private static RequestPostProcessor staff(String role, String subject) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_" + role))
            .jwt(builder -> builder.subject(subject).claim("tenant_id", TENANT.toString()));
    }

    @Test
    void aWithdrawalWaitsForTheSecondSignerAndIsTheAskersOwn() throws Exception {
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now());
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("200000.00"),
            LocalDate.now());
        String asker = "service-" + UUID.randomUUID();
        mockMvc.perform(post("/policies/" + issued.policyNumber() + "/account/withdrawals")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .with(staff("CUSTOMER_SERVICE_REP", asker)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"40000.00\",\"payeeRef\":\"+255700000001\"}"))
            .andExpect(status().isCreated());

        String forSigner = mockMvc.perform(get("/approvals").with(staff("FINANCE_OFFICER", "finance-two")))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andReturn().getResponse().getContentAsString();
        Map<String, Object> row = withdrawalRow(forSigner, issued.policyNumber());
        assertThat(row.get("yours")).isEqualTo(false);
        assertThat(row.get("requestedBy")).isEqualTo(asker);
        assertThat(row.get("amount")).isEqualTo(Map.of("amount", "40000.00", "currencyCode", "TZS"));

        // The asker holds accounts.approve too here (a finance officer), so the row is listed as theirs.
        String forAsker = mockMvc.perform(get("/approvals").with(staff("FINANCE_OFFICER", asker)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(withdrawalRow(forAsker, issued.policyNumber()).get("yours")).isEqualTo(true);

        // Customer service cannot approve a withdrawal, so the kind is not theirs to see here.
        String forService = mockMvc.perform(get("/approvals").with(staff("CUSTOMER_SERVICE_REP", asker)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(forService, "$.groups[*].kind")).doesNotContain("account-withdrawal");
    }

    private static Map<String, Object> withdrawalRow(String json, String policyNumber) {
        List<Map<String, Object>> rows = JsonPath.read(json,
            "$.groups[?(@.kind == 'account-withdrawal')].rows[?(@.policyNumber == '" + policyNumber + "')]");
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }
}
