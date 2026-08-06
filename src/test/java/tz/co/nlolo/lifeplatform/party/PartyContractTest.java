package tz.co.nlolo.lifeplatform.party;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class PartyContractTest {

    private static final String SPEC_PATH = "api/openapi/openapi-party.yaml";

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
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/audit/V1__create_audit_schema.sql");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void registerIndividualMatchesOpenApiContract() throws Exception {
        UUID tenantId = UUID.randomUUID();
        mockMvc.perform(post("/parties/individuals")
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Amina Hassan","dateOfBirth":"1990-05-12","contactInfo":{"phoneNumber":"+255712345678","email":"amina@example.tz"}}
                    """))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void registerCorporateMatchesOpenApiContract() throws Exception {
        UUID tenantId = UUID.randomUUID();
        mockMvc.perform(post("/parties/corporates")
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"registeredName":"Kilimanjaro SACCO","registrationNumber":"CONTRACT-TEST-001","contactInfo":{"phoneNumber":"+255712345999"}}
                    """))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void registerIndividualRejectsUnauthenticatedRequest() throws Exception {
        mockMvc.perform(post("/parties/individuals")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void getPartyRejectsCustomerReadingSomeoneElsesRecord() throws Exception {
        UUID otherPartyId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID customersOwnPartyId = UUID.randomUUID();
        mockMvc.perform(get("/parties/" + otherPartyId)
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder
                        .claim("tenant_id", tenantId.toString())
                        .claim("party_id", customersOwnPartyId.toString()))))
            .andExpect(status().isForbidden());
    }

    @Test
    void tenantContextIsIsolatedPerRequest() throws Exception {
        // Prove that TenantContextFilter reads tenant_id fresh from each request's
        // JWT claim, not leaking a stale value across sequential requests with
        // different tenants (mirroring TenantAwareDataSourceIntegrationTest's
        // per-request isolation proof). Register two individuals under two
        // different tenant_id claims, then fetch each back using the
        // tenant-specific JWT.
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        // Register individual under tenant A
        MvcResult resultA = mockMvc.perform(post("/parties/individuals")
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantA.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Tenant A Party","dateOfBirth":"1990-05-12","contactInfo":{"phoneNumber":"+255712345678","email":"a@example.tz"}}
                    """))
            .andExpect(status().isCreated())
            .andReturn();
        PartyView partyA = objectMapper.readValue(resultA.getResponse().getContentAsString(), PartyView.class);

        // Register individual under tenant B
        MvcResult resultB = mockMvc.perform(post("/parties/individuals")
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantB.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Tenant B Party","dateOfBirth":"1990-06-13","contactInfo":{"phoneNumber":"+255712345679","email":"b@example.tz"}}
                    """))
            .andExpect(status().isCreated())
            .andReturn();
        PartyView partyB = objectMapper.readValue(resultB.getResponse().getContentAsString(), PartyView.class);

        // Verify tenant A can read their own party
        mockMvc.perform(get("/parties/" + partyA.partyId())
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder
                        .claim("tenant_id", tenantA.toString())
                        .claim("party_id", partyA.partyId().toString()))))
            .andExpect(status().isOk());

        // Verify tenant B can read their own party
        mockMvc.perform(get("/parties/" + partyB.partyId())
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder
                        .claim("tenant_id", tenantB.toString())
                        .claim("party_id", partyB.partyId().toString()))))
            .andExpect(status().isOk());

        // Verify tenant A cannot read tenant B's party (access denied, not "not found" leak)
        mockMvc.perform(get("/parties/" + partyB.partyId())
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder
                        .claim("tenant_id", tenantA.toString())
                        .claim("party_id", partyA.partyId().toString()))))
            .andExpect(status().isForbidden());

        // Verify tenant B cannot read tenant A's party (access denied, not leak)
        mockMvc.perform(get("/parties/" + partyA.partyId())
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder
                        .claim("tenant_id", tenantB.toString())
                        .claim("party_id", partyB.partyId().toString()))))
            .andExpect(status().isForbidden());
    }
}
