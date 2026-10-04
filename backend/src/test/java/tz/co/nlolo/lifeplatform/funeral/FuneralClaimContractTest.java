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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures;
import tz.co.nlolo.lifeplatform.party.api.Sex;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.TODAY;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;
import static tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures.family;

/**
 * A funeral claim over the wire (openapi-claims.yaml, and the promotion on openapi-policy.yaml): the life
 * named and read back, the accident recorded, and a waiting-period decline by its reason.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import({FuneralTestFixtures.class, AnnuityTestFixtures.class})
class FuneralClaimContractTest {

    private static final String CLAIMS_SPEC = "api/openapi/openapi-claims.yaml";
    private static final String POLICY_SPEC = "api/openapi/openapi-policy.yaml";

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
    @Autowired private PolicyApi policyApi;

    private static RequestPostProcessor staff(String... roles) {
        SimpleGrantedAuthority[] authorities = new SimpleGrantedAuthority[roles.length + 1];
        authorities[0] = new SimpleGrantedAuthority("ROLE_REALM_STAFF");
        for (int i = 0; i < roles.length; i++) {
            authorities[i + 1] = new SimpleGrantedAuthority("ROLE_" + roles[i]);
        }
        // One subject per role set: claims refuses the person who assessed a claim deciding it too.
        String subject = roles.length == 0 ? "staff-clerk" : "staff-" + String.join("-", roles).toLowerCase();
        return jwt().authorities(authorities).jwt(builder -> builder.subject(subject).claim("tenant_id", TENANT.toString()));
    }

    @Test
    void aClaimNamesTheLifeAndADeathInsideTheWaitingPeriodIsDeclinedForItToSpec() throws Exception {
        var product = fixtures.publishFamilia(TENANT);
        UUID juma = fixtures.person(TENANT, 40, Sex.MALE);
        String policyNumber = fixtures.issueFamilyInForce(TENANT, product, juma, family(), annuityFixtures);
        UUID neema = asTenant(TENANT, () -> policyApi.coveredLives(policyNumber)).stream()
            .filter(l -> l.fullName().equals("Neema")).findFirst().orElseThrow().coveredLifeId();

        String registered = mockMvc.perform(post("/claims")
                .with(staff())
                .header("Idempotency-Key", "funeral-ct-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"policyNumber":"%s","claimantPartyId":"%s","claimType":"DEATH","dateOfEvent":"%s",
                     "details":{"claimType":"DEATH","causeOfDeath":"Malaria","placeOfDeath":"Dar es Salaam",
                     "dateOfDeath":"%s","attendingPhysician":"Dr. Kessy"},
                     "coveredLifeId":"%s","accidental":false}
                    """.formatted(policyNumber, juma, TODAY, TODAY, neema)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.coveredLifeId").value(neema.toString()))
            .andExpect(jsonPath("$.accidental").value(false))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(CLAIMS_SPEC))
            .andReturn().getResponse().getContentAsString();
        String claimId = JsonPath.read(registered, "$.claimId");

        mockMvc.perform(get("/claims/" + claimId).with(staff()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.coveredLifeId").value(neema.toString()))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(CLAIMS_SPEC));

        mockMvc.perform(put("/claims/" + claimId + "/accidental")
                .with(staff("CLAIMS_ASSESSOR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accidental\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accidental").value(false))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(CLAIMS_SPEC));

        mockMvc.perform(post("/claims/" + claimId + "/assessments")
                .with(staff("CLAIMS_ASSESSOR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"findings\":\"Death certificate seen\",\"fraudIndicator\":false,"
                    + "\"recommendedAmount\":{\"amount\":\"1000000.00\",\"currencyCode\":\"TZS\"}}"))
            .andExpect(status().is2xxSuccessful());

        // Approval inside the waiting period is a 409 ...
        mockMvc.perform(post("/claims/" + claimId + "/settlement-decision")
                .with(staff("CLAIMS_MANAGER"))
                .header("Idempotency-Key", "funeral-ct-settle-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"approved\":true,\"approvedAmount\":{\"amount\":\"1000000.00\",\"currencyCode\":\"TZS\"},"
                    + "\"payeeRef\":\"+255700000777\"}"))
            .andExpect(status().isConflict());
        // ... and the decline names its reason.
        mockMvc.perform(post("/claims/" + claimId + "/settlement-decision")
                .with(staff("CLAIMS_MANAGER"))
                .header("Idempotency-Key", "funeral-ct-decline-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"approved\":false,\"rejectionReason\":\"Inside the waiting period\","
                    + "\"declineReason\":\"WITHIN_WAITING_PERIOD\"}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("REJECTED"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(CLAIMS_SPEC));
    }

    @Test
    void claimsStaffPromoteADependantToSpec() throws Exception {
        var product = fixtures.publishFamilia(TENANT);
        UUID juma = fixtures.person(TENANT, 40, Sex.MALE);
        String policyNumber = fixtures.issueFamilyInForce(TENANT, product, juma, family(), annuityFixtures);
        UUID asha = asTenant(TENANT, () -> policyApi.coveredLives(policyNumber)).stream()
            .filter(l -> l.fullName().equals("Asha")).findFirst().orElseThrow().coveredLifeId();

        mockMvc.perform(post("/policies/" + policyNumber + "/covered-lives/" + asha + "/promotion")
                .with(staff("CLAIMS_ASSESSOR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"idType\":\"NATIONAL_ID\",\"idNumber\":\"19880101-33333-00003-33\",\"phoneNumber\":\"+255711000333\",\"sex\":\"FEMALE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.partyId").isNotEmpty())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(POLICY_SPEC));

        // Not a claims role: refused.
        mockMvc.perform(post("/policies/" + policyNumber + "/covered-lives/" + asha + "/promotion")
                .with(staff())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"idType\":\"NATIONAL_ID\",\"idNumber\":\"19880101-33333-00003-33\"}"))
            .andExpect(status().isForbidden());
    }
}
