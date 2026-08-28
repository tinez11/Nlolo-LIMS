package tz.co.nlolo.lifeplatform.party;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.HashSet;
import java.util.Set;
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
            // GET /parties/{id}/documents reads document.document_record through DocumentApi, so
            // this class now needs the document schema too -- without it the endpoint 500s on a
            // missing relation, which is exactly how it first failed.
            "db-migrations/document/V1__create_document_schema.sql",
            "db-migrations/document/V2__add_content_type_and_file_name.sql",
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
    void staffCanRegisterAnIndividual() throws Exception {
        // Staff-assisted individual registration (e.g. a branch/call-center walk-in with no
        // agent involved) -- previously 403'd because registerIndividual only allowed
        // REALM_CUSTOMERS/REALM_AGENTS, an asymmetry with registerCorporate (which already
        // allows staff) that had no documented rationale (staff portal review, 2026-08-25).
        UUID tenantId = UUID.randomUUID();
        mockMvc.perform(post("/parties/individuals")
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Staff Assisted Walkin","dateOfBirth":"1990-05-12","contactInfo":{"phoneNumber":"+255712345694","email":"walkin@example.tz"}}
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

    // --- GET /parties/{partyId}: the full record, and who may read it ------------------------

    /** Registers as {@code subject}, returning the new party's id. */
    private String registerAs(UUID tenantId, String subject, String fullName, String phone) throws Exception {
        MvcResult result = mockMvc.perform(post("/parties/individuals")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.subject(subject).claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"" + fullName + "\",\"dateOfBirth\":\"1990-05-12\","
                    + "\"contactInfo\":{\"phoneNumber\":\"" + phone + "\",\"email\":\"scoped@example.tz\"}}"))
            .andExpect(status().isCreated())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("partyId").asText();
    }

    /**
     * The whole reason PartyDetailView exists. Every one of these columns has been stored since V1
     * and returned by nothing: PartyView carries four fields, so a date of birth typed into the
     * registration form could not be read back through any endpoint on the platform.
     */
    @Test
    void getPartyReturnsTheFullRecordNotJustTheFourListFields() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String partyId = registerAs(tenantId, "agent-detail", "Detail View Fixture", "+255712345821");

        mockMvc.perform(get("/parties/" + partyId)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.displayName").value("Detail View Fixture"))
            .andExpect(jsonPath("$.dateOfBirth").value("1990-05-12"))
            .andExpect(jsonPath("$.phoneNumber").value("+255712345821"))
            .andExpect(jsonPath("$.email").value("scoped@example.tz"))
            .andExpect(jsonPath("$.createdAt").exists())
            // The field the agents realm is scoped on, surfaced so staff can see which agent owns
            // the relationship without a second lookup.
            .andExpect(jsonPath("$.createdBy").value("agent-detail"))
            // PENDING until a KYC decision is recorded -- not merely absent.
            .andExpect(jsonPath("$.kycVerifiedAt").doesNotExist());
    }

    @Test
    void anAgentMayReadTheFullRecordOfAClientItRegistered() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String partyId = registerAs(tenantId, "agent-owner", "Own Client Fixture", "+255712345822");

        mockMvc.perform(get("/parties/" + partyId)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.subject("agent-owner").claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.phoneNumber").value("+255712345822"));
    }

    /**
     * The deferral this endpoint carried since M1 ("fine-grained agency-hierarchy scoping ...
     * explicitly deferred, not silently skipped"), now closed. Before PartyDetailView the exposure
     * was a name and a KYC status; it is now a date of birth, a phone number and an email address,
     * which is what made realm-role-only scoping untenable.
     */
    @Test
    void anAgentMayNotReadAClientAnotherAgentRegistered() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String partyId = registerAs(tenantId, "agent-a", "Other Agents Client", "+255712345823");

        mockMvc.perform(get("/parties/" + partyId)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.subject("agent-b").claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isForbidden());
    }

    @Test
    void staffMayReadAnyPartyRegardlessOfWhoRegisteredIt() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String partyId = registerAs(tenantId, "agent-c", "Staff Readable Fixture", "+255712345824");

        mockMvc.perform(get("/parties/" + partyId)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.subject("staff-1").claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk());
    }

    @Test
    void partyDocumentsAreScopedToTheRegisteringAgentToo() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String partyId = registerAs(tenantId, "agent-docs", "Document Scope Fixture", "+255712345825");

        // The owning agent may ask. No documents exist yet, so this is an empty array -- the
        // assertion that matters here is the 403 below; a real document is exercised in
        // PartyKycEvidenceUploadTest, which has the MinIO container this class does not.
        mockMvc.perform(get("/parties/" + partyId + "/documents")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.subject("agent-docs").claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));

        mockMvc.perform(get("/parties/" + partyId + "/documents")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.subject("agent-other").claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isForbidden());
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

    // --- GET /parties?q=... -----------------------------------------------------------------

    @Test
    void searchPartiesByQMatchesACaseInsensitiveSubstringOfDisplayName() throws Exception {
        UUID tenantId = UUID.randomUUID();
        mockMvc.perform(post("/parties/individuals")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Zawadi Search Fixture","dateOfBirth":"1990-05-12","contactInfo":{"phoneNumber":"+255712345695"}}
                    """))
            .andExpect(status().isCreated());

        // Deliberately the WRONG case from what was registered ("Zawadi" vs "zawadi") --
        // this is the falsifiable half of "ILIKE is inherently case-insensitive": a
        // case-SENSITIVE match would find zero rows here.
        mockMvc.perform(get("/parties")
                .queryParam("q", "zawadi")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].displayName").value("Zawadi Search Fixture"));
    }

    @Test
    void searchPartiesByQCombinesWithKycStatus() throws Exception {
        UUID tenantId = UUID.randomUUID();
        // Two parties sharing a name fragment, only one VERIFIED -- proves q and
        // kycStatus are genuinely ANDed together, not either alone.
        mockMvc.perform(post("/parties/individuals")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Baraka Combo Fixture Pending","dateOfBirth":"1990-05-12","contactInfo":{"phoneNumber":"+255712345696"}}
                    """))
            .andExpect(status().isCreated());
        String verifiedResponse = mockMvc.perform(post("/parties/individuals")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Baraka Combo Fixture Verified","dateOfBirth":"1990-05-12","contactInfo":{"phoneNumber":"+255712345697"}}
                    """))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        UUID verifiedPartyId = UUID.fromString(objectMapper.readValue(verifiedResponse, PartyView.class).partyId().toString());
        mockMvc.perform(post("/parties/" + verifiedPartyId + "/kyc")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"status":"VERIFIED","evidenceDocumentRef":"doc-ref-combo-fixture"}
                    """))
            .andExpect(status().isOk());

        mockMvc.perform(get("/parties")
                .queryParam("q", "combo fixture")
                .queryParam("kycStatus", "VERIFIED")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].displayName").value("Baraka Combo Fixture Verified"));
    }

    @Test
    void searchPartiesByQReturnsEmptyForNoMatches() throws Exception {
        UUID tenantId = UUID.randomUUID();
        mockMvc.perform(get("/parties")
                .queryParam("q", "NoPartyAnywhereHasThisExactNonsenseName12345")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void searchPartiesByQStaysForceScopedToTheCallingAgentsOwnCreatedByParties() throws Exception {
        // The new 3-way `search` query carries the SAME agents-realm force-scoping
        // as the original derived-query branches -- proven here the same way, not
        // just assumed from reading the code: two agents (distinct JWT subjects)
        // each register a party sharing a `q`-matchable name fragment, and agent A's
        // own `q` search must find only its own party, never agent B's.
        UUID tenantId = UUID.randomUUID();
        String agentASubject = "agent-a-q-scoping-test";
        String agentBSubject = "agent-b-q-scoping-test";

        mockMvc.perform(post("/parties/individuals")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.subject(agentASubject).claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Scoping Query Fixture Agent A","dateOfBirth":"1990-05-12","contactInfo":{"phoneNumber":"+255712345698"}}
                    """))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/parties/individuals")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.subject(agentBSubject).claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Scoping Query Fixture Agent B","dateOfBirth":"1990-05-12","contactInfo":{"phoneNumber":"+255712345699"}}
                    """))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/parties")
                .queryParam("q", "Scoping Query Fixture")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.subject(agentASubject).claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].displayName").value("Scoping Query Fixture Agent A"));
    }

    // --- GET /parties ordering --------------------------------------------------------------
    //
    // This endpoint shipped with a bare PageRequest.of(page, pageSize) and therefore no ORDER BY
    // at all. It was found by an e2e test: a party registered through the real agents flow could
    // not be found in the staff KYC queue, because with no ordering it came back somewhere past
    // the first page. The deeper defect is that paginating an unordered query is unsound -- rows
    // may be assigned to pages differently on each query -- so a reviewer could be shown one
    // party twice and never shown another.

    private String registerOrderedFixture(UUID tenantId, String name, String phone) throws Exception {
        MvcResult result = mockMvc.perform(post("/parties/individuals")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"" + name + "\",\"dateOfBirth\":\"1990-05-12\","
                    + "\"contactInfo\":{\"phoneNumber\":\"" + phone + "\"}}"))
            .andExpect(status().isCreated())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("partyId").asText();
    }

    @Test
    void searchPartiesReturnsNewestFirst() throws Exception {
        UUID tenantId = UUID.randomUUID();
        // Registered oldest -> newest, so a correct queue returns them reversed. An
        // unordered query would return insertion order and fail this.
        registerOrderedFixture(tenantId, "Ordering Fixture One", "+255712345801");
        registerOrderedFixture(tenantId, "Ordering Fixture Two", "+255712345802");
        registerOrderedFixture(tenantId, "Ordering Fixture Three", "+255712345803");

        mockMvc.perform(get("/parties")
                .queryParam("kycStatus", "PENDING")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.items.length()").value(3))
            .andExpect(jsonPath("$.items[0].displayName").value("Ordering Fixture Three"))
            .andExpect(jsonPath("$.items[1].displayName").value("Ordering Fixture Two"))
            .andExpect(jsonPath("$.items[2].displayName").value("Ordering Fixture One"));
    }

    @Test
    void searchPartiesPagesCoverEveryRowExactlyOnce() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Set<String> registered = new HashSet<>();
        for (int i = 1; i <= 5; i++) {
            registered.add(registerOrderedFixture(tenantId, "Paging Fixture " + i, "+25571234581" + i));
        }

        // Walk the pages the way a reviewer would. The union must be exactly the five
        // parties -- no duplicate across a page boundary, and nothing skipped.
        //
        // HONEST LIMIT, verified rather than assumed: this test was run against the
        // unsorted controller and PASSED. Five rows in a fresh table come back in a
        // consistent scan order, so it cannot catch the unstable-pagination defect it
        // was written for -- `searchPartiesReturnsNewestFirst` is the one that actually
        // fails without the sort. Reproducing instability on demand would need a table
        // large enough to change plan, plus updates and a vacuum, and would still be
        // probabilistic; a slow flaky test is worse than a documented gap. What this
        // does still earn its keep on is page-boundary arithmetic: an off-by-one in
        // page/offset handling drops or repeats a row here regardless of ordering.
        Set<String> seen = new HashSet<>();
        int duplicates = 0;
        for (int page = 0; page < 3; page++) {
            MvcResult result = mockMvc.perform(get("/parties")
                    .queryParam("kycStatus", "PENDING")
                    .queryParam("page", String.valueOf(page))
                    .queryParam("pageSize", "2")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                        .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
                .andExpect(status().isOk())
                .andReturn();
            for (JsonNode item : objectMapper.readTree(result.getResponse().getContentAsString()).get("items")) {
                if (!seen.add(item.get("partyId").asText())) duplicates++;
            }
        }

        assertThat(duplicates).as("a party shown on two different pages").isZero();
        assertThat(seen).as("every registered party reachable by paging").isEqualTo(registered);
    }
}
