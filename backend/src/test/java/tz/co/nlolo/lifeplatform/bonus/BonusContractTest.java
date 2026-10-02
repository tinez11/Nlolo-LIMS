package tz.co.nlolo.lifeplatform.bonus;

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
import tz.co.nlolo.lifeplatform.accumulation.DepositTestMigrations;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.bonus.application.BonusApiImpl;
import tz.co.nlolo.lifeplatform.bonus.application.DeclarationDrain;
import tz.co.nlolo.lifeplatform.bonus.domain.Eligibility;
import tz.co.nlolo.lifeplatform.product.api.BonusPlan;
import tz.co.nlolo.lifeplatform.product.api.CashValuePlan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The bonus surface against its own OpenAPI spec, over the wire, with strict response validation. */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(BonusTestFixtures.class)
class BonusContractTest {

    private static final String SPEC_PATH = "api/openapi/openapi-bonus.yaml";

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
    @Autowired private MockMvc mockMvc;
    @Autowired private BonusTestFixtures fixtures;

    private static RequestPostProcessor staff(String role, String subject) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_" + role))
            .jwt(builder -> builder.subject(subject).claim("tenant_id", TENANT.toString()));
    }

    private static String key() { return UUID.randomUUID().toString(); }

    /** A real, valid body -- a 403 test against an invalid one would prove nothing (memory). */
    private static final String VALID = "{\"valuationDate\":\"2026-12-31\",\"reversionaryRatePercent\":3.5,\"terminalRatePercent\":40}";

    private UUID withProfitsProduct() {
        return fixtures.issue(TENANT, BonusTestFixtures.COMPOUND_NONE, CashValuePlan.none()).productId();
    }

    @Test
    void aDeclarationIsProposedListedAndApprovedToSpec() throws Exception {
        UUID product = withProfitsProduct();
        String created = mockMvc.perform(post("/products/" + product + "/bonus-declarations").header("Idempotency-Key", key())
                .with(staff("ADMIN", "admin-one")).contentType(MediaType.APPLICATION_JSON).content(VALID))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.reversionaryRatePercent").value("3.5"))
            .andExpect(jsonPath("$.status").value("PROPOSED"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.declarationId");

        mockMvc.perform(get("/products/" + product + "/bonus-declarations").with(staff("FINANCE_OFFICER", "finance-two")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].declarationId").value(id))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));

        mockMvc.perform(post("/bonus-declarations/" + id + "/approve").with(staff("FINANCE_OFFICER", "finance-two")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.approvedBy").value("finance-two"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void anUnderwriterCannotProposeEvenWithAValidBody() throws Exception {
        UUID product = withProfitsProduct();
        mockMvc.perform(post("/products/" + product + "/bonus-declarations").header("Idempotency-Key", key())
                .with(staff("UNDERWRITER", "uw")).contentType(MediaType.APPLICATION_JSON).content(VALID))
            .andExpect(status().isForbidden());
    }

    @Test
    void theProposerApprovingIsA422InTheServersWords() throws Exception {
        UUID product = withProfitsProduct();
        String created = mockMvc.perform(post("/products/" + product + "/bonus-declarations").header("Idempotency-Key", key())
                .with(staff("ADMIN", "admin-one")).contentType(MediaType.APPLICATION_JSON).content(VALID))
            .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/bonus-declarations/" + JsonPath.read(created, "$.declarationId") + "/approve")
                .with(staff("ADMIN", "admin-one")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errorCode").value("BONUS_REFUSED"))
            .andExpect(jsonPath("$.detail").value("A bonus declaration must be approved by someone other than the person who proposed it"));
    }

    @Test
    void aProposalSentTwiceWithOneKeyIsMadeOnceAndNoKeyIsA400() throws Exception {
        UUID product = withProfitsProduct();
        String k = key();
        String first = mockMvc.perform(post("/products/" + product + "/bonus-declarations").header("Idempotency-Key", k)
                .with(staff("ADMIN", "admin-one")).contentType(MediaType.APPLICATION_JSON).content(VALID))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/products/" + product + "/bonus-declarations").header("Idempotency-Key", k)
                .with(staff("ADMIN", "admin-one")).contentType(MediaType.APPLICATION_JSON).content(VALID))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(second, "$.declarationId")).isEqualTo(JsonPath.read(first, "$.declarationId"));
        mockMvc.perform(post("/products/" + product + "/bonus-declarations")
                .with(staff("ADMIN", "admin-one")).contentType(MediaType.APPLICATION_JSON).content(VALID))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/products/" + product + "/bonus-declarations").with(staff("FINANCE_OFFICER", "f")))
            .andExpect(jsonPath("$.length()").value(1));
    }

    // ---- Task 7: a policy's bonuses ------------------------------------------------------------

    private static final LocalDate TODAY = LocalDate.now(Eligibility.CIVIL_ZONE);
    @Autowired private BonusApiImpl api;
    @Autowired private DeclarationDrain drain;

    /** A with-profits policy with one 3% declaration (50% terminal) attached as at today. */
    private BonusTestFixtures.Issued withOneBonus() {
        var issued = fixtures.issue(TENANT, BonusTestFixtures.COMPOUND_NONE, CashValuePlan.none());
        UUID declarationId;
        TenantContext.set(TENANT);
        try {
            declarationId = api.proposeDeclaration(issued.productId(), TODAY, new BigDecimal("3"), new BigDecimal("50"),
                "admin-one").declarationId();
            api.approveDeclaration(declarationId, "finance-two");
        } finally {
            TenantContext.clear();
        }
        drain.drainOne(declarationId, TENANT);
        return issued;
    }

    @Test
    void aPolicysBonusesAreReadToSpecWithTheEntryAndWhyItAttached() throws Exception {
        var issued = withOneBonus();
        mockMvc.perform(get("/policies/" + issued.policyNumber() + "/bonuses").with(staff("UNDERWRITER", "reader")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.attachedTotal.amount").value("30000.00"))
            .andExpect(jsonPath("$.entries[0].type").value("REVERSIONARY"))
            .andExpect(jsonPath("$.entries[0].ratePercent").value("3"))
            .andExpect(jsonPath("$.entries[0].basis.amount").value("1000000.00"))
            .andExpect(jsonPath("$.outcomes[0].outcome").value("ATTACHED"))
            .andExpect(jsonPath("$.outcomes[0].valuationDate").value(TODAY.toString()))
            .andExpect(jsonPath("$.settlements").isEmpty())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void anOrdinaryPolicyHasNoBonusesAndSaysSoWithA404() throws Exception {
        var issued = fixtures.issue(TENANT, BonusPlan.none(), CashValuePlan.none());
        mockMvc.perform(get("/policies/" + issued.policyNumber() + "/bonuses").with(staff("UNDERWRITER", "reader")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("NOT_WITH_PROFITS"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
        mockMvc.perform(get("/policies/" + issued.policyNumber() + "/bonuses/value").with(staff("UNDERWRITER", "reader")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("NOT_WITH_PROFITS"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void theValueAtADateIsItsThreePartsAndTheirTotal() throws Exception {
        var issued = withOneBonus();
        // attached 30,000; interim (1,000,000 + 30,000) x 3% x 5/12 = 12,875.00; terminal 50% x 30,000 = 15,000.
        mockMvc.perform(get("/policies/" + issued.policyNumber() + "/bonuses/value")
                .param("asOf", TODAY.plusMonths(5).toString()).with(staff("UNDERWRITER", "reader")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.attached.amount").value("30000.00"))
            .andExpect(jsonPath("$.interim.amount").value("12875.00"))
            .andExpect(jsonPath("$.terminal.amount").value("15000.00"))
            .andExpect(jsonPath("$.total.amount").value("57875.00"))
            .andExpect(jsonPath("$.terminalRatePercent").value("50"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }
}
