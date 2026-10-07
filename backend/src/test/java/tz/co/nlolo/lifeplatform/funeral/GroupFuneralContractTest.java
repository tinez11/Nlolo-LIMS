package tz.co.nlolo.lifeplatform.funeral;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;

import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.TODAY;
import static tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures.groupProposal;
import static tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures.life;

/** A group funeral scheme's families over the wire (openapi-policy.yaml, 2026-10-07). */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import({FuneralTestFixtures.class, AnnuityTestFixtures.class})
class GroupFuneralContractTest {

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
    @Autowired private AnnuityTestFixtures annuityFixtures;

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String... roles) {
        return request.with(jwt().authorities(java.util.Arrays.stream(roles)
                .<org.springframework.security.core.GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList())
            .jwt(builder -> builder.claim("tenant_id", TENANT.toString())));
    }

    @Test
    void familiesJoinGrowShrinkAndLeaveToSpec() throws Exception {
        var product = fixtures.publishGroupFamilia(TENANT);
        UUID association = fixtures.association(TENANT);
        String policyNumber = fixtures.issueGroupScheme(TENANT, product, association, groupProposal("A", List.of(
            life("M001", FuneralRole.MAIN_MEMBER, "Juma Ali", 40), life("M001", FuneralRole.CHILD, "Neema Juma", 10))));
        annuityFixtures.collect(TENANT, policyNumber, "3000.00", TODAY);

        mockMvc.perform(as(get("/group-schemes/" + policyNumber), "REALM_STAFF"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.benefitBasis").value("FUNERAL_PLAN"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));

        String joined = mockMvc.perform(as(post("/group-schemes/" + policyNumber + "/families"), "REALM_STAFF", "UNDERWRITER")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"lives":[
                      {"memberReference":"M002","role":"MAIN_MEMBER","fullName":"Rehema Said","dateOfBirth":"%s",
                       "beneficiaryName":"Said Omar","beneficiaryRelationship":"Brother","beneficiaryPhone":"+255713000002"},
                      {"memberReference":"M002","role":"CHILD","fullName":"Zuri Said","dateOfBirth":"%s"}]}
                    """.formatted(TODAY.minusYears(45), TODAY.minusYears(6))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.memberName").value("Rehema Said"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andReturn().getResponse().getContentAsString();
        String rehema = JsonPath.read(joined, "$.policyMemberId");

        String added = mockMvc.perform(as(post("/group-schemes/" + policyNumber + "/members/" + rehema + "/lives"), "REALM_STAFF")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"SPOUSE\",\"fullName\":\"Omari Said\",\"dateOfBirth\":\"%s\"}"
                    .formatted(TODAY.minusYears(47))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.role").value("SPOUSE"))
            .andExpect(jsonPath("$.yearlyPremium").value(0.0))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andReturn().getResponse().getContentAsString();
        String omari = JsonPath.read(added, "$.coveredLifeId");

        mockMvc.perform(as(get("/group-schemes/" + policyNumber + "/families"), "REALM_STAFF"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[1].memberReference").value("M002"))
            .andExpect(jsonPath("$[1].mainMemberName").value("Rehema Said"))
            .andExpect(jsonPath("$[1].status").value("ACTIVE"))
            .andExpect(jsonPath("$[1].beneficiaryName").value("Said Omar"))
            .andExpect(jsonPath("$[1].familyCover").value(2500000.0))
            .andExpect(jsonPath("$[1].lives.length()").value(3))
            .andExpect(jsonPath("$[1].lives[0].role").value("MAIN_MEMBER"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));

        mockMvc.perform(as(post("/group-schemes/" + policyNumber + "/covered-lives/" + omari + "/removal"), "REALM_STAFF")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Divorced\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.coverEnd").isNotEmpty())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));

        mockMvc.perform(as(post("/group-schemes/" + policyNumber + "/members/" + rehema + "/departure"), "REALM_STAFF", "UNDERWRITER")
                .contentType(MediaType.APPLICATION_JSON).content("{\"leftOn\":\"%s\"}".formatted(TODAY)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("EXITED"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));

        // A family joins on an underwriter's word, as any member does; staff alone may not.
        mockMvc.perform(as(post("/group-schemes/" + policyNumber + "/families"), "REALM_STAFF")
                .contentType(MediaType.APPLICATION_JSON).content("{\"lives\":[]}"))
            .andExpect(status().isForbidden());
    }
}
