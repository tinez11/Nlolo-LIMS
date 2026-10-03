package tz.co.nlolo.lifeplatform.annuity;

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
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
import tz.co.nlolo.lifeplatform.party.api.Sex;
import tz.co.nlolo.lifeplatform.underwriting.api.AnnuityChoice;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.TODAY;

/** The annuity surface against its own OpenAPI spec, every response field asserted by name (step 4 L9). */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(AnnuityTestFixtures.class)
class AnnuityContractTest {

    private static final String SPEC_PATH = "api/openapi/openapi-annuity.yaml";

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
            AnnuityTestMigrations.ALL);
    }

    private static final UUID TENANT = UUID.randomUUID();
    @Autowired private MockMvc mockMvc;
    @Autowired private AnnuityTestFixtures fixtures;

    private static RequestPostProcessor as(String realmRole) {
        return jwt().authorities(new SimpleGrantedAuthority(realmRole))
            .jwt(builder -> builder.subject("tester").claim("tenant_id", TENANT.toString()));
    }

    @Test
    void aQuoteIsPricedToSpecForAnAgent() throws Exception {
        var product = fixtures.publish(TENANT, AnnuityTestFixtures.everyForm());
        UUID annuitant = fixtures.person(TENANT, 61, Sex.MALE);
        mockMvc.perform(post("/annuity-quotes").with(as("ROLE_REALM_AGENTS")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productVersionId\":\"" + product.versionId() + "\",\"formCode\":\"LIFE-BS\",\"frequency\":\"MONTHLY\","
                    + "\"purchasePrice\":50000000,\"annuitantPartyId\":\"" + annuitant + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.formCode").value("LIFE-BS"))
            .andExpect(jsonPath("$.frequency").value("MONTHLY"))
            .andExpect(jsonPath("$.paymentsPerYear").value(12))
            .andExpect(jsonPath("$.timing").value("ARREARS"))
            .andExpect(jsonPath("$.annuitantAge").value(61))
            .andExpect(jsonPath("$.rateSex").value("MALE"))
            .andExpect(jsonPath("$.annualRatePerMille").value("75"))
            .andExpect(jsonPath("$.factor").value("0.98"))
            // 50,000,000 x 75 x 0.98 / 12,000 = 306,250.00
            .andExpect(jsonPath("$.instalment.amount").value("306250.00"))
            .andExpect(jsonPath("$.annualIncome.amount").value("3750000.00"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void anUnpriceableQuoteIsA422InThePricersWords() throws Exception {
        var product = fixtures.publish(TENANT, AnnuityTestFixtures.everyForm());
        UUID noSex = fixtures.person(TENANT, 61, null);
        mockMvc.perform(post("/annuity-quotes").with(as("ROLE_REALM_STAFF")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productVersionId\":\"" + product.versionId() + "\",\"formCode\":\"LIFE-BS\",\"frequency\":\"MONTHLY\","
                    + "\"purchasePrice\":50000000,\"annuitantPartyId\":\"" + noSex + "\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errorCode").value("ANNUITY_NOT_PRICEABLE"))
            .andExpect(jsonPath("$.detail").value("Form LIFE-BS is priced by sex and the annuitant's sex is not recorded"));
    }

    @Test
    void aPolicysContractIsReadToSpecOnceLocked() throws Exception {
        var product = fixtures.publish(TENANT, AnnuityTestFixtures.everyForm());
        String policy = fixtures.buy(TENANT, product, fixtures.person(TENANT, 61, null), "50000000.00",
            AnnuityChoice.of("LIFE-10G", "MONTHLY", null));
        fixtures.collect(TENANT, policy, "50000000.00", TODAY);
        mockMvc.perform(get("/policies/" + policy + "/annuity").with(as("ROLE_REALM_STAFF")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.policyNumber").value(policy))
            .andExpect(jsonPath("$.status").value("IN_PAYMENT"))
            .andExpect(jsonPath("$.formCode").value("LIFE-10G"))
            .andExpect(jsonPath("$.guaranteeYears").value(10))
            .andExpect(jsonPath("$.joint").value(false))
            .andExpect(jsonPath("$.capitalProtected").value(false))
            .andExpect(jsonPath("$.escalationPercent").value("0"))
            .andExpect(jsonPath("$.timing").value("ARREARS"))
            .andExpect(jsonPath("$.frequency").value("MONTHLY"))
            .andExpect(jsonPath("$.purchasePrice.amount").value("50000000.00"))
            .andExpect(jsonPath("$.lockedOn").value(TODAY.toString()))
            .andExpect(jsonPath("$.annuitantAge").value(61))
            .andExpect(jsonPath("$.annualRatePerMille").value("72"))
            .andExpect(jsonPath("$.instalment.amount").value("294000.00"))
            .andExpect(jsonPath("$.annualIncome.amount").value("3600000.00"))
            .andExpect(jsonPath("$.firstDueDate").value(TODAY.plusMonths(1).toString()))
            .andExpect(jsonPath("$.guaranteeEndDate").value(TODAY.plusMonths(1).plusYears(10).toString()))
            .andExpect(jsonPath("$.overpaymentOwed.amount").value("0.00"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void anOrdinaryPolicyIsA404WithItsOwnCode() throws Exception {
        mockMvc.perform(get("/policies/POL-NOPE0001/annuity").with(as("ROLE_REALM_STAFF")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("NOT_AN_ANNUITY"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }
}
