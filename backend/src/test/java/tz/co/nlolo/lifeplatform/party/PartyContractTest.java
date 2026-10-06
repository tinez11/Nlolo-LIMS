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
            "db-migrations/party/V2__individual_person_record.sql",
            "db-migrations/party/V4__registered_by_agent.sql",
            "db-migrations/party/V5__registered_by_name.sql",
            "db-migrations/party/V6__client_reference.sql",
            // GET /parties/{id}/documents reads document.document_record through DocumentApi, so
            // this class now needs the document schema too -- without it the endpoint 500s on a
            // missing relation, which is exactly how it first failed.
            "db-migrations/document/V1__create_document_schema.sql",
            "db-migrations/document/V2__add_content_type_and_file_name.sql",
            "db-migrations/document/V7__journal_support_document_type.sql",
            "db-migrations/document/V8__reinsurance_statement_document_type.sql",
            "db-migrations/document/V9__ifrs17_engine_document_types.sql",
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql");
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
                    {"fullName":"Amina Hassan","dateOfBirth":"1990-05-12","sex":"FEMALE","idType":"NATIONAL_ID","idNumber":"19900512-45678-00001-11","contactInfo":{"phoneNumber":"+255712345678","email":"amina@example.tz"}}
                    """))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    /**
     * Amending a client over real HTTP, against the spec.
     *
     * <p>Two things only this level can prove: that the PUT is reachable with a staff token and
     * that its response really is a {@code PartyDetailView} — the spec declares
     * {@code additionalProperties: false}, so the new {@code registeredByPartyId} field had to be
     * declared before this could pass, which is exactly the check that caught the last set of
     * fields added to a view without a spec change.
     */
    @Test
    void amendIndividualMatchesOpenApiContract() throws Exception {
        UUID tenantId = UUID.randomUUID();
        var staff = jwt()
            .authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
            .jwt(builder -> builder.claim("tenant_id", tenantId.toString()));

        String created = mockMvc.perform(post("/parties/individuals")
                .with(staff)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Amend Me","dateOfBirth":"1990-05-12","sex":"FEMALE","idType":"NATIONAL_ID","idNumber":"19900512-45699-00001-11","contactInfo":{"phoneNumber":"+255712345699"}}
                    """))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String partyId = created.replaceAll(".*\"partyId\"\\s*:\\s*\"([0-9a-f-]{36})\".*", "$1");

        mockMvc.perform(put("/parties/individuals/" + partyId)
                .with(staff)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Amended Name","dateOfBirth":"1990-05-12",
                     "contactInfo":{"phoneNumber":"+255712345600","email":"amended@example.tz"},
                     "occupation":"Nurse"}
                    """))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    /**
     * The client record names who registered it, from their token, instead of a login id.
     *
     * <p>{@code createdBy} is a Keycloak subject nothing on the platform can name, and the console
     * printed it raw. The name is taken at registration -- {@code name}, else
     * {@code preferred_username} -- and the subject is kept beside it, because agent scoping still
     * compares the subject. Both halves are asserted, against the spec.
     */
    @Test
    void theRegistrarIsNamedFromTheirTokenAndTheSubjectIsKept() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String created = mockMvc.perform(post("/parties/corporates")
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.subject("staff-subject-1").claim("name", "Asha Admin")
                        .claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"registeredName":"Named Registrar Ltd","registrationNumber":"REG-NAMED-01","contactInfo":{}}
                    """))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String partyId = created.replaceAll(".*\"partyId\"\\s*:\\s*\"([0-9a-f-]{36})\".*", "$1");

        mockMvc.perform(get("/parties/" + partyId)
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.createdByName").value("Asha Admin"))
            .andExpect(jsonPath("$.createdBy").value("staff-subject-1"));
    }

    /**
     * A CUSTOMER REGISTERING THEMSELVES INTRODUCED NOBODY.
     *
     * <p>The customers realm mints a {@code party_id} claim too — it is how a customer reads
     * their own record — so a claim-only check would have recorded the customer as their own
     * introducing agent. That costs no commission, because they resolve to no agent profile, but
     * the client record would have said "introduced by" somebody who introduced nobody. A wrong
     * answer on screen is worse than a blank one, and this is the test that keeps the realm
     * check from being simplified away as redundant.
     */
    @Test
    void aSelfRegisteringCustomerIsNotRecordedAsTheirOwnIntroducingAgent() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID someExistingPartyId = UUID.randomUUID();

        String created = mockMvc.perform(post("/parties/individuals")
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder
                        .claim("tenant_id", tenantId.toString())
                        .claim("party_id", someExistingPartyId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Self Signup","dateOfBirth":"1990-05-12","contactInfo":{}}
                    """))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String partyId = created.replaceAll(".*\"partyId\"\\s*:\\s*\"([0-9a-f-]{36})\".*", "$1");

        mockMvc.perform(get("/parties/" + partyId)
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.registeredByPartyId").doesNotExist());
    }

    /**
     * An agent may REGISTER a client and may not REWRITE one.
     *
     * <p>The asymmetry is deliberate and is the reason the endpoint is gated differently from the
     * one beside it: creating a record is not the same act as changing one. An agent able to
     * amend a client afterwards could alter the identity a policy was underwritten against.
     */
    @Test
    void anAgentMayNotAmendAClient() throws Exception {
        UUID tenantId = UUID.randomUUID();
        mockMvc.perform(put("/parties/individuals/" + UUID.randomUUID())
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Nice Try","dateOfBirth":"1990-05-12","contactInfo":{}}
                    """))
            .andExpect(status().isForbidden());
    }

    /**
     * The person record over real HTTP, not through {@code PartyApi}.
     *
     * <p>This exists because the controller hand-maps twelve fields from {@code
     * RegisterIndividualRequest} into {@code IndividualRegistration}, and every other
     * test of those fields calls the API directly -- so a transposed pair (occupation
     * into occupationClass, ward into district) would have passed everything and only
     * shown up as wrong data on a client record. Unknown request keys are silently
     * dropped platform-wide, so a misspelled property here would 201 and discard the
     * value; reading the record back is what makes that detectable.
     */
    @Test
    void registeringWithThePersonRecordRoundTripsOverHttp() throws Exception {
        UUID tenantId = UUID.randomUUID();
        var jwtCustomizer = jwt()
            .authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
            .jwt(builder -> builder.claim("tenant_id", tenantId.toString()));

        String body = mockMvc.perform(post("/parties/individuals")
                .with(jwtCustomizer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Neema Mushi","dateOfBirth":"1988-02-09",
                     "contactInfo":{"phoneNumber":"+255713111222"},
                     "sex":"FEMALE","smokerStatus":"NON_SMOKER",
                     "idType":"NATIONAL_ID","idNumber":"HTTP-ROUNDTRIP-0001",
                     "occupation":"Secondary school teacher","occupationClass":"PROF_1",
                     "employerName":"Ilala Secondary School","nationality":"tz",
                     "address":{"line":"Plot 44, Uhuru Road","ward":"Upanga",
                                "district":"Ilala","region":"Dar es Salaam","postalCode":"11101"}}
                    """))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andReturn().getResponse().getContentAsString();

        String partyId = com.jayway.jsonpath.JsonPath.read(body, "$.partyId");

        mockMvc.perform(get("/parties/{partyId}", partyId).with(jwtCustomizer))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.sex").value("FEMALE"))
            .andExpect(jsonPath("$.smokerStatus").value("NON_SMOKER"))
            .andExpect(jsonPath("$.identityDocument.type").value("NATIONAL_ID"))
            .andExpect(jsonPath("$.identityDocument.number").value("HTTP-ROUNDTRIP-0001"))
            .andExpect(jsonPath("$.occupation").value("Secondary school teacher"))
            .andExpect(jsonPath("$.occupationClass").value("PROF_1"))
            .andExpect(jsonPath("$.employerName").value("Ilala Secondary School"))
            // Upper-cased on the way in, so a return or a quote never case-folds it.
            .andExpect(jsonPath("$.nationality").value("TZ"))
            // Each address field asserted separately: a swap between ward and district
            // is exactly the mapping slip this test is here to catch.
            .andExpect(jsonPath("$.address.line").value("Plot 44, Uhuru Road"))
            .andExpect(jsonPath("$.address.ward").value("Upanga"))
            .andExpect(jsonPath("$.address.district").value("Ilala"))
            .andExpect(jsonPath("$.address.region").value("Dar es Salaam"))
            .andExpect(jsonPath("$.address.postalCode").value("11101"));
    }

    /** The 409 the partial unique index produces, as a real status and errorCode. */
    @Test
    void aDuplicateIdentityDocumentIsRejectedWith409() throws Exception {
        UUID tenantId = UUID.randomUUID();
        var jwtCustomizer = jwt()
            .authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
            .jwt(builder -> builder.claim("tenant_id", tenantId.toString()));
        String payload = """
            {"fullName":"%s","dateOfBirth":"1990-01-01","contactInfo":{},
             "idType":"NATIONAL_ID","idNumber":"HTTP-DUPLICATE-0001"}
            """;

        mockMvc.perform(post("/parties/individuals").with(jwtCustomizer)
                .contentType(MediaType.APPLICATION_JSON).content(payload.formatted("First Holder")))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/parties/individuals").with(jwtCustomizer)
                .contentType(MediaType.APPLICATION_JSON).content(payload.formatted("Second Holder")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("DUPLICATE_IDENTITY_DOCUMENT"))
            // The number is a government identifier and error text reaches logs and
            // screens, so the message names the document type and never echoes it back.
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("HTTP-DUPLICATE-0001"))))
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
                    {"fullName":"Staff Assisted Walkin","dateOfBirth":"1990-05-12","sex":"FEMALE","idType":"NATIONAL_ID","idNumber":"19900512-45694-00001-11","contactInfo":{"phoneNumber":"+255712345694","email":"walkin@example.tz"}}
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
                    {"fullName":"Get Party Contract Test","dateOfBirth":"1990-05-12","sex":"FEMALE","idType":"NATIONAL_ID","idNumber":"19900512-45690-00001-11","contactInfo":{"phoneNumber":"+255712345690","email":"getparty@example.tz"}}
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
    /**
     * An agent must record a sex, because nothing can price a life without one.
     *
     * <p>Enforced on the server and not only in the form, so the rule survives a direct call.
     * The refusal is a 422 rather than a 400: the request was understood and rejected on a rule
     * about people, not malformed.
     */
    @Test
    void anAgentRegisteringWithoutASexIsRefused() throws Exception {
        UUID tenantId = UUID.randomUUID();
        mockMvc.perform(post("/parties/individuals")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.subject("agent-no-sex").claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"No Sex Recorded","dateOfBirth":"1990-05-12",
                     "idType":"NATIONAL_ID","idNumber":"19900512-77777-00001-11",
                     "contactInfo":{"phoneNumber":"+255712347777"}}
                    """))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errorCode").value("PARTY_VALIDATION_FAILED"));
    }

    @Test
    void anAgentRegisteringWithoutAnIdentityDocumentIsRefused() throws Exception {
        UUID tenantId = UUID.randomUUID();
        mockMvc.perform(post("/parties/individuals")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.subject("agent-no-id").claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"No Id Recorded","dateOfBirth":"1990-05-12","sex":"MALE",
                     "contactInfo":{"phoneNumber":"+255712347778"}}
                    """))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errorCode").value("PARTY_VALIDATION_FAILED"));
    }

    /**
     * And the limit of the rule, stated so nobody widens it by accident: it is about the AGENT
     * path, where new business is written. A customer registering themselves is not selling
     * anything yet, and staff are often correcting a record rather than opening one.
     */
    @Test
    void aCustomerRegisteringThemselvesIsNotHeldToTheAgentsRule() throws Exception {
        UUID tenantId = UUID.randomUUID();
        mockMvc.perform(post("/parties/individuals")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Self Registered","dateOfBirth":"1990-05-12",
                     "contactInfo":{"phoneNumber":"+255712347779"}}
                    """))
            .andExpect(status().isCreated());
    }

    private String registerAs(UUID tenantId, String subject, String fullName, String phone) throws Exception {
        MvcResult result = mockMvc.perform(post("/parties/individuals")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.subject(subject).claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                // Sex and an identity document are REQUIRED of an agent as of 2026-09-30: nothing
                // can price a life whose sex is unrecorded, and a client who cannot be identified
                // cannot be KYC-verified. The ID number is derived from the phone so each fixture
                // gets its own -- ux_party_individual_identity refuses two clients sharing one.
                .content("{\"fullName\":\"" + fullName + "\",\"dateOfBirth\":\"1990-05-12\","
                    + "\"sex\":\"FEMALE\",\"idType\":\"NATIONAL_ID\","
                    + "\"idNumber\":\"19900512-" + phone.substring(phone.length() - 5) + "-00001-11\","
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
                    {"fullName":"Kyc Contract Test","dateOfBirth":"1990-05-12","sex":"FEMALE","idType":"NATIONAL_ID","idNumber":"19900512-45691-00001-11","contactInfo":{"phoneNumber":"+255712345691","email":"kyc@example.tz"}}
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
                    {"fullName":"Tenant A Party","dateOfBirth":"1990-05-12","sex":"FEMALE","idType":"NATIONAL_ID","idNumber":"19900512-45678-00001-11","contactInfo":{"phoneNumber":"+255712345678","email":"a@example.tz"}}
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
                    {"fullName":"Request 1 Party","dateOfBirth":"1990-05-12","sex":"FEMALE","idType":"NATIONAL_ID","idNumber":"19900512-45678-00001-11","contactInfo":{"phoneNumber":"+255712345678","email":"r1@example.tz"}}
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
                    {"fullName":"Zawadi Search Fixture","dateOfBirth":"1990-05-12","sex":"FEMALE","idType":"NATIONAL_ID","idNumber":"19900512-45695-00001-11","contactInfo":{"phoneNumber":"+255712345695"}}
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
                    {"fullName":"Baraka Combo Fixture Pending","dateOfBirth":"1990-05-12","sex":"FEMALE","idType":"NATIONAL_ID","idNumber":"19900512-45696-00001-11","contactInfo":{"phoneNumber":"+255712345696"}}
                    """))
            .andExpect(status().isCreated());
        String verifiedResponse = mockMvc.perform(post("/parties/individuals")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Baraka Combo Fixture Verified","dateOfBirth":"1990-05-12","sex":"FEMALE","idType":"NATIONAL_ID","idNumber":"19900512-45697-00001-11","contactInfo":{"phoneNumber":"+255712345697"}}
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

    // --- GET /parties?partyType=... ---------------------------------------------------------

    /**
     * The register is two working areas -- individuals, and corporates/groups -- and that
     * separation is only true if the server does the filtering. These tests are the
     * falsifiable half of that: each asserts a type that must be ABSENT, because a filter
     * that quietly ignored its parameter would still return the row the caller wanted and
     * pass any assertion that only checked for presence.
     */
    @Test
    void searchPartiesByPartyTypeReturnsOnlyIndividuals() throws Exception {
        UUID tenantId = UUID.randomUUID();
        registerIndividual(tenantId, "Neema Type Fixture", "+255712345710");
        registerCorporate(tenantId, "Type Fixture Holdings Ltd", "TYPE-FIX-001", "+255712345720");

        mockMvc.perform(get("/parties")
                .queryParam("partyType", "INDIVIDUAL")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].displayName").value("Neema Type Fixture"))
            .andExpect(jsonPath("$.items[0].partyType").value("INDIVIDUAL"))
            .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    /**
     * The repeatable form, which is the whole reason the parameter is a list: the register's
     * second area is corporates AND groups, and as two requests it could not be paged or
     * totalled as one list.
     */
    @Test
    void searchPartiesByPartyTypeAcceptsSeveralTypesAsOneList() throws Exception {
        UUID tenantId = UUID.randomUUID();
        registerIndividual(tenantId, "Excluded Individual Fixture", "+255712345711");
        registerCorporate(tenantId, "Included Corporate Fixture Ltd", "TYPE-FIX-002", "+255712345721");

        mockMvc.perform(get("/parties")
                .queryParam("partyType", "CORPORATE,GROUP")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].displayName").value("Included Corporate Fixture Ltd"))
            .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void searchPartiesByPartyTypeCombinesWithQ() throws Exception {
        UUID tenantId = UUID.randomUUID();
        // Same name fragment across both types, so neither filter alone would isolate one row.
        registerIndividual(tenantId, "Mwangaza Shared Fragment", "+255712345712");
        registerCorporate(tenantId, "Mwangaza Shared Fragment Ltd", "TYPE-FIX-003", "+255712345722");

        mockMvc.perform(get("/parties")
                .queryParam("q", "mwangaza shared")
                .queryParam("partyType", "CORPORATE")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].partyType").value("CORPORATE"));
    }

    /**
     * The regression guard for the whole change. Adding the type dimension routed the
     * filtered cases through the null-safe `search` query; a caller that passes NO type must
     * still take the original derived-query branches and see every type, exactly as before.
     */
    @Test
    void searchPartiesWithoutPartyTypeStillReturnsEveryType() throws Exception {
        UUID tenantId = UUID.randomUUID();
        registerIndividual(tenantId, "Untyped Search Individual", "+255712345713");
        registerCorporate(tenantId, "Untyped Search Corporate Ltd", "TYPE-FIX-004", "+255712345723");

        mockMvc.perform(get("/parties")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page.totalElements").value(2));
    }

    /**
     * The type filter must not become a way around the agents-realm scoping. Same shape as
     * the `q` scoping test above: the new dimension carries the same force-scoping, proven
     * rather than assumed.
     */
    @Test
    void searchPartiesByPartyTypeStaysForceScopedToTheCallingAgent() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String agentA = "agent-a-type-scoping-test";
        String agentB = "agent-b-type-scoping-test";

        mockMvc.perform(post("/parties/corporates")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.subject(agentA).claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"registeredName":"Agent A Corporate Fixture Ltd","registrationNumber":"TYPE-SCOPE-A","contactInfo":{"phoneNumber":"+255712345724"}}
                    """))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/parties/corporates")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.subject(agentB).claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"registeredName":"Agent B Corporate Fixture Ltd","registrationNumber":"TYPE-SCOPE-B","contactInfo":{"phoneNumber":"+255712345725"}}
                    """))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/parties")
                .queryParam("partyType", "CORPORATE,GROUP")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.subject(agentA).claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].displayName").value("Agent A Corporate Fixture Ltd"));
    }

    /** Registration helpers, so the type tests above read as the assertion they are making. */
    private void registerIndividual(UUID tenantId, String fullName, String phone) throws Exception {
        mockMvc.perform(post("/parties/individuals")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"" + fullName
                    + "\",\"dateOfBirth\":\"1990-05-12\",\"contactInfo\":{\"phoneNumber\":\"" + phone + "\"}}"))
            .andExpect(status().isCreated());
    }

    private void registerCorporate(UUID tenantId, String registeredName, String registrationNumber,
                                    String phone) throws Exception {
        // contactInfo is @NotNull on RegisterCorporateRequest -- omitting it is a 400, not a
        // corporate with no phone number.
        mockMvc.perform(post("/parties/corporates")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"registeredName\":\"" + registeredName
                    + "\",\"registrationNumber\":\"" + registrationNumber
                    + "\",\"contactInfo\":{\"phoneNumber\":\"" + phone + "\"}}"))
            .andExpect(status().isCreated());
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
                    {"fullName":"Scoping Query Fixture Agent A","dateOfBirth":"1990-05-12","sex":"FEMALE","idType":"NATIONAL_ID","idNumber":"19900512-45698-00001-11","contactInfo":{"phoneNumber":"+255712345698"}}
                    """))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/parties/individuals")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.subject(agentBSubject).claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Scoping Query Fixture Agent B","dateOfBirth":"1990-05-12","sex":"FEMALE","idType":"NATIONAL_ID","idNumber":"19900512-45699-00001-11","contactInfo":{"phoneNumber":"+255712345699"}}
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
