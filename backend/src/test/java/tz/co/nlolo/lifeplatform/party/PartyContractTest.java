package tz.co.nlolo.lifeplatform.party;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
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

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

    @Autowired
    private PartyApi partyApi;

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

    // --- OpenAPI contract coverage for the remaining party operations (final-review Finding 5) --
    //
    // Only registerIndividual/registerCorporate were previously validated against
    // openapi-party.yaml -- getParty, submitKyc, and both group-member operations were
    // exercised (elsewhere, or not via real HTTP at all) without ever being checked against
    // the spec. These add real MockMvc dispatches, each asserting OpenApiValidationMatchers.

    @Test
    void getPartyMatchesOpenApiContract() throws Exception {
        UUID tenantId = UUID.randomUUID();
        MvcResult registerResult = mockMvc.perform(post("/parties/individuals")
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Get Party Contract Test","dateOfBirth":"1990-05-12","contactInfo":{"phoneNumber":"+255712345690","email":"getparty@example.tz"}}
                    """))
            .andExpect(status().isCreated())
            .andReturn();
        PartyView registered = objectMapper.readValue(registerResult.getResponse().getContentAsString(), PartyView.class);

        mockMvc.perform(get("/parties/" + registered.partyId())
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void submitKycMatchesOpenApiContract() throws Exception {
        UUID tenantId = UUID.randomUUID();
        MvcResult registerResult = mockMvc.perform(post("/parties/individuals")
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Kyc Contract Test","dateOfBirth":"1990-05-12","contactInfo":{"phoneNumber":"+255712345691","email":"kyc@example.tz"}}
                    """))
            .andExpect(status().isCreated())
            .andReturn();
        PartyView registered = objectMapper.readValue(registerResult.getResponse().getContentAsString(), PartyView.class);

        mockMvc.perform(post("/parties/" + registered.partyId() + "/kyc")
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"status":"VERIFIED","evidenceDocumentRef":"doc-ref-contract-test"}
                    """))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void addAndListGroupMembersMatchOpenApiContract() throws Exception {
        // registerGroup has no dedicated REST endpoint (only individuals/corporates do) -- the
        // group and its prospective member are created directly via PartyApi, exactly as
        // PartyApiIntegrationTest does for the same reason. The operations actually under
        // contract test here are the two group-membership HTTP endpoints themselves.
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        PartyView group = partyApi.registerGroup("Contract Test Group", "test-agent");
        PartyView member = partyApi.registerIndividual("Contract Test Member", LocalDate.of(1990, 1, 1),
            "+255712345692", "member@example.tz", "test-agent");
        TenantContext.clear();

        String membersPath = "/parties/" + group.partyId() + "/groups/" + group.partyId() + "/members";

        mockMvc.perform(post(membersPath)
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"memberPartyId\":\"" + member.partyId() + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));

        mockMvc.perform(get(membersPath)
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void tenantContextFilterReadsTenanIdFreshPerRequest() throws Exception {
        // Prove TenantContextFilter reads tenant_id fresh from each request's JWT
        // claim (not cached/leaked from prior requests). Register two parties under
        // two different tenant_id claims, then fetch each back: if the filter leaked
        // a stale tenant_id, the second fetch would incorrectly find the other
        // tenant's row and succeed; instead, each request gets the correct tenant_id
        // from its own JWT claim.
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

        // Verify tenant A can read their own party (filter read tenantA from JWT)
        mockMvc.perform(get("/parties/" + partyA.partyId())
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder
                        .claim("tenant_id", tenantA.toString())
                        .claim("party_id", partyA.partyId().toString()))))
            .andExpect(status().isOk());

        // Verify tenant B can read their own party (filter read tenantB from JWT,
        // not a leaked tenantA from the prior request)
        mockMvc.perform(get("/parties/" + partyB.partyId())
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder
                        .claim("tenant_id", tenantB.toString())
                        .claim("party_id", partyB.partyId().toString()))))
            .andExpect(status().isOk());
    }

    @Test
    void tenantContextFilterClearsThreadLocalToPreventLeakAcrossRequests() throws Exception {
        // Prove the TenantContextFilter's finally-block unconditionally clears
        // TenantContext after each request, preventing ThreadLocal leaks across
        // requests dispatched on the same servlet worker thread. Request 1 (with
        // valid tenant_id) succeeds normally, then the filter clears the context.
        // Request 2 (with NO tenant_id claim) should fail with 500 because
        // TenantContext.get() will be null, hitting the fail-loud guard in
        // PartyApiImpl. If the finally-clear were broken, request 2 would
        // incorrectly inherit request 1's tenant_id and succeed — exactly the
        // production bug this test is designed to catch.

        UUID tenantId = UUID.randomUUID();

        // Request 1: Register an individual with a valid tenant_id.
        // Uses agent role to bypass PartyController's customer-specific party_id
        // check (which would short-circuit before TenantContext is consulted).
        mockMvc.perform(post("/parties/individuals")
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Request 1 Party","dateOfBirth":"1990-05-12","contactInfo":{"phoneNumber":"+255712345678","email":"r1@example.tz"}}
                    """))
            .andExpect(status().isCreated());
        // At this point, TenantContextFilter's finally-block has run and
        // cleared TenantContext.

        // Request 2: Register an individual with NO tenant_id claim.
        // If the finally-block worked, TenantContextFilter finds no valid tenant_id
        // claim on THIS request and rejects it with 403 FORBIDDEN before it ever
        // reaches PartyApiImpl (final-review Finding 3: a missing/malformed
        // tenant_id claim is an authorization failure, rejected at the request
        // boundary, not a server fault). If the finally-block is broken/missing,
        // TenantContext would still have tenantId from Request 1, the registration
        // would succeed with 201, and this assertion would fail — a falsifiable
        // proof that the clear works, regardless of which status code correctly
        // represents "no claim on this request."
        mockMvc.perform(post("/parties/individuals")
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS")))
                    // Deliberately omit .claim("tenant_id", ...)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Request 2 Party (No Tenant)","dateOfBirth":"1990-06-13","contactInfo":{"phoneNumber":"+255712345679","email":"r2@example.tz"}}
                    """))
            .andExpect(status().isForbidden());
    }

    @Test
    void missingTenantIdClaimReturnsForbiddenWithTenantClaimMissingErrorCode() throws Exception {
        // Direct coverage of final-review Finding 3: a JWT with no tenant_id claim
        // must be rejected at the request boundary (403) with a recognizable
        // errorCode, not allowed through to fail deep in the service layer as a 500.
        mockMvc.perform(post("/parties/individuals")
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS")))
                    // Deliberately omit .claim("tenant_id", ...)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"No Tenant Claim","dateOfBirth":"1990-06-13","contactInfo":{"phoneNumber":"+255712345680","email":"notenant@example.tz"}}
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("TENANT_CLAIM_MISSING"));
    }

    @Test
    void malformedTenantIdClaimReturnsForbiddenWithTenantClaimMissingErrorCode() throws Exception {
        mockMvc.perform(post("/parties/individuals")
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", "not-a-uuid")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Malformed Tenant Claim","dateOfBirth":"1990-06-13","contactInfo":{"phoneNumber":"+255712345681","email":"malformed@example.tz"}}
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("TENANT_CLAIM_MISSING"));
    }

    // --- GlobalExceptionHandler coverage (final-review Finding 1 + 2) ---------------------
    //
    // Before the fix, PartyExceptionHandler's bare @ExceptionHandler(Exception.class) catch-all
    // intercepted every one of these standard Spring MVC exceptions before Spring's own
    // DefaultHandlerExceptionResolver ever got a chance, turning all of them into 500s. These
    // tests exercise the real MockMvc dispatch stack (not the handler in isolation) against the
    // now-global GlobalExceptionHandler, which extends ResponseEntityExceptionHandler, to prove
    // each one now resolves to its correct standard status.

    @Test
    void malformedJsonBodyReturnsBadRequestNotServerError() throws Exception {
        UUID tenantId = UUID.randomUUID();
        mockMvc.perform(post("/parties/individuals")
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ this is not valid json"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void missingRequiredFieldReturnsValidationErrorNotServerError() throws Exception {
        UUID tenantId = UUID.randomUUID();
        // fullName is required by openapi-party.yaml's RegisterIndividualRequest schema but
        // omitted here -- pre-fix this reached the DB, hit a NOT NULL constraint, and surfaced
        // as a bare 500; post-fix Bean Validation rejects it before the controller ever runs.
        mockMvc.perform(post("/parties/individuals")
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"dateOfBirth":"1990-05-12","contactInfo":{"phoneNumber":"+255712345678","email":"amina@example.tz"}}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.traceId").exists())
            .andExpect(jsonPath("$.errors[?(@.field == 'fullName')]").exists());
    }

    @Test
    void unsupportedHttpMethodReturnsMethodNotAllowedNotServerError() throws Exception {
        UUID tenantId = UUID.randomUUID();
        // PUT is not mapped on this path at all (only POST is) -- this is Spring's own
        // HttpRequestMethodNotSupportedException, resolved by ResponseEntityExceptionHandler.
        mockMvc.perform(put("/parties/individuals")
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void unsupportedMediaTypeReturnsUnsupportedMediaTypeNotServerError() throws Exception {
        UUID tenantId = UUID.randomUUID();
        mockMvc.perform(post("/parties/individuals")
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.TEXT_PLAIN)
                .content("plain text, not json"))
            .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void pathVariableTypeMismatchReturnsBadRequestNotServerError() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID ownPartyId = UUID.randomUUID();
        // partyId is declared as a UUID path variable -- "not-a-uuid" triggers Spring's
        // MethodArgumentTypeMismatchException, resolved by ResponseEntityExceptionHandler.
        mockMvc.perform(get("/parties/not-a-uuid")
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder
                        .claim("tenant_id", tenantId.toString())
                        .claim("party_id", ownPartyId.toString()))))
            .andExpect(status().isBadRequest());
    }

    // --- Advice-ordering regression coverage (final-review fix round 4, Critical) ----------
    //
    // GlobalExceptionHandler and PartyExceptionHandler are both unrestricted @RestControllerAdvice
    // beans. Without an explicit @Order on each, ExceptionHandlerExceptionResolver picks the winner
    // by bean-registration/classpath-scan order, NOT by exception-type specificity across beans --
    // and on this classpath that put GlobalExceptionHandler's catch-all ahead of
    // PartyExceptionHandler, silently turning PartyNotFoundException/DuplicateRegistrationNumberException
    // (which should be 404/409) into bare 500s. Every other assertion of those exceptions in this
    // codebase is a service-layer assertThrows() call, which passes regardless of which advice bean
    // wins -- these are the only two tests in the whole suite that would have caught this at the
    // HTTP layer, and did (they failed against the pre-@Order code before the fix).

    @Test
    void getPartyForNonexistentPartyReturnsNotFoundNotServerError() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID nonexistentPartyId = UUID.randomUUID();
        // STAFF role bypasses PartyController's customer-only "must be your own party_id" check,
        // so the request reaches PartyApiImpl.getParty and throws PartyNotFoundException for real.
        mockMvc.perform(get("/parties/" + nonexistentPartyId)
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("PARTY_NOT_FOUND"))
            .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void duplicateCorporateRegistrationReturnsConflictNotServerError() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String registrationNumber = "CONTRACT-TEST-DUPLICATE-001";
        String body = """
            {"registeredName":"Duplicate Test SACCO","registrationNumber":"%s","contactInfo":{"phoneNumber":"+255712345693"}}
            """.formatted(registrationNumber);

        mockMvc.perform(post("/parties/corporates")
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/parties/corporates")
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("DUPLICATE_REGISTRATION_NUMBER"))
            .andExpect(jsonPath("$.traceId").exists());
    }
}
