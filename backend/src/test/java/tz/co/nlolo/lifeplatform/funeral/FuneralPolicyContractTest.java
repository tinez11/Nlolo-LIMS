package tz.co.nlolo.lifeplatform.funeral;

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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures;
import tz.co.nlolo.lifeplatform.party.api.Sex;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures.family;

/**
 * A funeral policy over the wire (openapi-policy.yaml): its covered lives, every field by name, and the
 * manual-issue refusal. Issued through the real case path -- the only path that records a family.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import({FuneralTestFixtures.class, AnnuityTestFixtures.class})
class FuneralPolicyContractTest {

    private static final String SPEC_PATH = "api/openapi/openapi-policy.yaml";

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
            FuneralTestMigrations.ALL);
    }

    private static final UUID TENANT = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private FuneralTestFixtures fixtures;

    @Test
    void theCoveredLivesReadBackToSpec() throws Exception {
        var product = fixtures.publishFamilia(TENANT);
        UUID juma = fixtures.person(TENANT, 40, Sex.MALE);
        String policyNumber = fixtures.issueFamily(TENANT, product, juma, family());

        mockMvc.perform(get("/policies/" + policyNumber + "/covered-lives")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", TENANT.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(5))
            .andExpect(jsonPath("$[0].coveredLifeId").isNotEmpty())
            .andExpect(jsonPath("$[0].role").value("MAIN_MEMBER"))
            .andExpect(jsonPath("$[0].partyId").value(juma.toString()))
            .andExpect(jsonPath("$[2].role").value("CHILD"))
            .andExpect(jsonPath("$[2].fullName").value("Neema"))
            .andExpect(jsonPath("$[2].dateOfBirth").isNotEmpty())
            .andExpect(jsonPath("$[2].sex").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$[2].idNumber").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$[2].student").value(false))
            .andExpect(jsonPath("$[2].partyId").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$[2].benefit").value(1000000.0))
            .andExpect(jsonPath("$[2].yearlyPremium").value(6000.0))
            .andExpect(jsonPath("$[2].pricedAtAge").value(10))
            .andExpect(jsonPath("$[2].coverStart").isNotEmpty())
            .andExpect(jsonPath("$[2].waitingPeriodEnds").isNotEmpty())
            .andExpect(jsonPath("$[2].coverEnd").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$[2].status").value("ACTIVE"))
            .andExpect(jsonPath("$[2].endReason").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$[2].endedOn").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void aFuneralPlanCannotBeIssuedByHand() throws Exception {
        var product = fixtures.publishFamilia(TENANT);
        UUID juma = fixtures.person(TENANT, 40, Sex.MALE);

        mockMvc.perform(post("/policies/manual-issue")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_UNDERWRITER"))
                    .jwt(builder -> builder.claim("tenant_id", TENANT.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"underwritingCaseId":"%s","policyholderPartyId":"%s","productVersionId":"%s",
                     "sumAssured":{"amount":"2000000.00","currencyCode":"TZS"},
                     "premiumAmount":{"amount":"12075.00","currencyCode":"TZS"},"premiumFrequency":"MONTHLY",
                     "reasonForManualIssue":"Trying to skip the case","issuanceBasis":"MIGRATION"}
                    """.formatted(UUID.randomUUID(), juma, product.versionId())))
            .andExpect(status().is4xxClientError())
            .andExpect(jsonPath("$.detail").value("A funeral plan is issued from its underwriting case, which records"
                + " the family and prices it; it cannot be issued by hand"));
    }
}
