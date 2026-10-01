package tz.co.nlolo.lifeplatform.regreporting;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.SpecTypeConformance;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.regreporting.api.RegreportingApi;
import tz.co.nlolo.lifeplatform.regreporting.api.RegulatoryReturnView;
import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 8: HTTP-level contract coverage for {@code RegulatoryReturnController} against
 * {@code api/openapi/openapi-regreporting.yaml}. Follows {@code FinaccountingContractTest}'s
 * structure: {@code @Testcontainers} + {@code @AutoConfigureMockMvc} + {@code @SpringBootTest} +
 * {@code jwt()} post-processors.
 *
 * <p><b>MEASURED DEFECT in the test tooling itself -- not in {@code RegulatoryReturnController} or
 * {@code RegreportingApiImpl}, which this class confirms serialize the wire body exactly per spec.
 * {@code openApi().isValid(SPEC_PATH)} CANNOT be applied to any success response of this module.</b>
 * {@code ReturnLineView.value}'s {@code oneOf} ({@code Money} vs a plain decimal string) makes
 * swagger-request-validator 2.46.0 report every single line -- regardless of whether its actual
 * JSON value is a string or an object -- as {@code "Instance failed to match exactly one schema
 * (matched 2 out of 2)"}. Isolated with a standalone reproduction directly against {@code
 * OpenApiInteractionValidator} (bypassing MockMvc/Spring entirely, feeding a byte-for-byte correct
 * response body): the ambiguity persists even with the {@code Money} branch fully inlined (so it is
 * NOT a cross-file {@code $ref} resolution problem), and disappears completely when the same
 * document's {@code openapi:} header is changed from {@code 3.1.0} to {@code 3.0.3} with the schema
 * otherwise byte-identical. So this is specifically a swagger-request-validator 2.46.0 OpenAPI-3.1
 * limitation validating a {@code oneOf} between an object-typed and a string-typed branch --
 * every module spec on this platform declares {@code openapi: 3.1.0}, so this is not a fixable
 * property of this class's own test code, and downgrading the shared spec version is out of this
 * task's scope. {@code isValid(SPEC_PATH)} is therefore applied ONLY to the {@code ProblemDetails}
 * error bodies below (422/404), which never contain a {@code ReturnLineView}.
 *
 * <p><b>{@link SpecTypeConformance#matchesDeclaredTypes} does NOT close this gap either, and this
 * was independently verified, not assumed.</b> Its {@code oneOf}/{@code anyOf} branch selection
 * (see that class's javadoc) requires a branch to carry a single-value {@code enum} property to
 * disambiguate -- the shape {@code ClaimDetails}' four branches use via {@code claimType}. Neither
 * of {@code ReturnLineView.value}'s two branches has one ({@code Money}'s {@code amount}/{@code
 * currencyCode} are pattern-constrained, not enums; the string branch has no properties at all), so
 * {@code selectBranch} always returns {@code null} and the walker never recurses into {@code value}
 * at all -- confirmed with a standalone probe: feeding {@code matchesDeclaredTypes} a synthetic
 * {@code ReturnLineView} body whose {@code value.amount} was a raw JSON NUMBER (12345, not a
 * string) -- exactly the float-in-the-wire-format defect class this matcher exists to catch --
 * passed cleanly, with zero assertion. So for THIS specific schema, {@code value}'s primitive
 * typing is a genuine blind spot for BOTH of the platform's two contract-test tools, not merely one
 * of them. To close it anyway, every test below that reads a generated return additionally asserts
 * the wire shape of one count line and one money line directly via {@code jsonPath(...).isString()}
 * /{@code .isMap()} -- an assertion that CAN tell a string from a raw number or an object, and is
 * therefore a strictly more precise proof of the never-a-raw-number invariant than either generic
 * tool provides for this one field. {@code matchesDeclaredTypes} is still applied where it remains
 * meaningful (it does walk and check the response's other primitive fields -- {@code returnId},
 * {@code lineNo}, {@code lineCode}, {@code label}, {@code metricName}, etc. -- even though it skips
 * {@code value}).
 *
 * <p>This class runs against the Testcontainers Postgres <b>superuser</b>, not {@code app_role} --
 * RLS/grant coverage is {@code RegreportingApiIntegrationTest}'s job, not this one's. Migration
 * list is short on purpose: this module has no partitioned table, so unlike {@code
 * FinaccountingContractTest} there is no {@code policyloan/V2}-equivalent partition-control
 * migration to include.
 *
 * <p><b>{@code REALM_REGULATORS}' first use in any HTTP-level test on this platform.</b>
 * Regulators carry no fine-grained role claims -- {@code SecurityConfig} only synthesises
 * {@code ROLE_REALM_<REALM>} for them -- so {@link #regulatorOf} grants only that one authority,
 * plus the mandatory {@code tenant_id} claim {@code TenantContextFilter} requires of every
 * authenticated request before it ever reaches this controller.
 *
 * <p><b>Fixture-seeding technique.</b> {@code QUARTERLY_PRUDENTIAL} is seeded
 * (regreporting/V2 section 9) for exactly ONE tenant, {@code SEEDED_TENANT}
 * ({@code 11111111-1111-1111-1111-111111111111}) -- {@code return_definition}'s primary key is
 * {@code (tenant_id, return_type)}, so {@code generateReturn} only succeeds for that tenant; every
 * successful-generation test below therefore authenticates as {@code SEEDED_TENANT}. Fixtures are
 * seeded by autowiring {@link RegreportingApi} directly and calling {@code generateReturn} in the
 * test body (never through the HTTP endpoint under test), exactly as the task brief specifies: this
 * way every 403 test has a real target to have been denied access to, and a broken
 * {@code @PreAuthorize} would show up as an incidental 200/201, not be masked by an incidental 404
 * from missing seed data. No movement-fact rows are seeded at all -- {@code
 * RegreportingApiIntegrationTest.everyLineReadsAsZeroWhenNoProjectionsExist} already establishes
 * that a return generates ten genuine-zero lines with no projections present, so this class does
 * not need to duplicate that projection setup merely to get a non-empty return to read back.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class RegreportingContractTest {

    private static final String SPEC_PATH = "api/openapi/openapi-regreporting.yaml";
    private static final String RETURN_TYPE = "QUARTERLY_PRUDENTIAL";
    private static final UUID SEEDED_TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void applyMigrations() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/regreporting/V1__create_regreporting_schema.sql",
            "db-migrations/regreporting/V2__grants_rls_dimensions_movements_and_return_lines.sql",
            "db-migrations/regreporting/V3__optimistic_locking_on_movement_tables.sql",
            "db-migrations/regreporting/V5__member_movement_columns.sql");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private RegreportingApi regreportingApi;

    @AfterEach
    void resetAfterEach() {
        TenantContext.clear();
    }

    // --- token shapes -------------------------------------------------------------------------

    /** Copied verbatim from {@code FinaccountingContractTest:120-124}: FINANCE_OFFICER/ADMIN is
     * the project decision {@code RegulatoryReturnController}'s own javadoc records for the
     * staff-triggered surface -- there is no regreporting-specific staff role. */
    private static RequestPostProcessor financeStaffOf(UUID tenantId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"),
                                 new SimpleGrantedAuthority("ROLE_FINANCE_OFFICER"))
            .jwt(builder -> builder.subject("finance-officer").claim("tenant_id", tenantId.toString()));
    }

    /** Copied verbatim from {@code FinaccountingContractTest:128-132}: staff, but the WRONG
     * fine-grained role -- proves the gate is on FINANCE_OFFICER/ADMIN and not merely on being
     * staff. */
    private static RequestPostProcessor underwriterStaffOf(UUID tenantId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"),
                                 new SimpleGrantedAuthority("ROLE_UNDERWRITER"))
            .jwt(builder -> builder.subject("underwriter").claim("tenant_id", tenantId.toString()));
    }

    /** Copied verbatim from {@code FinaccountingContractTest:134-138}. */
    private static RequestPostProcessor agentOf(UUID tenantId, UUID partyId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
            .jwt(builder -> builder.subject("agent").claim("tenant_id", tenantId.toString())
                .claim("party_id", partyId.toString()));
    }

    /** REALM_REGULATORS' first use in any test. Regulators carry NO fine-grained role claims --
     * SecurityConfig only synthesises ROLE_REALM_<REALM> for them, because docs/04-api-contracts.md
     * defines role names for staff alone. The tenant_id claim is mandatory even here:
     * TenantContextFilter 403s any token without one, which is exactly what makes a regulator
     * tenant-scoped rather than cross-tenant. */
    private static RequestPostProcessor regulatorOf(UUID tenantId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_REGULATORS"))
            .jwt(builder -> builder.subject("tira-regulator").claim("tenant_id", tenantId.toString()));
    }

    // --- fixtures -----------------------------------------------------------------------------

    /** Seeds through {@link RegreportingApi#generateReturn} directly, per the brief's own
     * rationale -- see the class javadoc. Only {@code SEEDED_TENANT} carries a seeded {@code
     * QUARTERLY_PRUDENTIAL} definition, so every real target this class seeds is generated as
     * that tenant. */
    private RegulatoryReturnView seedReturn(String period) {
        TenantContext.set(SEEDED_TENANT);
        RegulatoryReturnView view = regreportingApi.generateReturn(RETURN_TYPE, period, "system:test");
        TenantContext.clear();
        return view;
    }

    private static String generateReturnBody(String returnType, String period) {
        return """
            {"returnType":"%s","period":"%s"}
            """.formatted(returnType, period);
    }

    // ============================================================================================
    // POST /regulatory-returns
    // ============================================================================================

    @Test
    void generateReturnReturns201WithTenLinesForFinanceStaff() throws Exception {
        mockMvc.perform(post("/regulatory-returns").with(financeStaffOf(SEEDED_TENANT))
                .contentType(MediaType.APPLICATION_JSON)
                .content(generateReturnBody(RETURN_TYPE, "2026-Q1")))
            .andExpect(status().isCreated())
            // openApi().isValid(SPEC_PATH) is deliberately NOT applied here -- see the class
            // javadoc's MEASURED DEFECT section: it always reports lines[].value as an ambiguous
            // oneOf regardless of the body's actual (correct) shape.
            .andExpect(SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "RegulatoryReturnView"))
            .andExpect(jsonPath("$.returnType").value(RETURN_TYPE))
            .andExpect(jsonPath("$.period").value("2026-Q1"))
            .andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.lines.length()").value(10))
            // PL-01 (POLICIES_IN_FORCE) is a count metric -- never a raw JSON number.
            .andExpect(jsonPath("$.lines[0].lineCode").value("PL-01"))
            .andExpect(jsonPath("$.lines[0].value").isString())
            .andExpect(jsonPath("$.lines[0].value").value("0"))
            // PL-02 (SUM_ASSURED_IN_FORCE) is monetary -- a Money object, never a bare number either.
            .andExpect(jsonPath("$.lines[1].lineCode").value("PL-02"))
            .andExpect(jsonPath("$.lines[1].value").isMap())
            .andExpect(jsonPath("$.lines[1].value.amount").isString())
            .andExpect(jsonPath("$.lines[1].value.amount").value("0.00"))
            .andExpect(jsonPath("$.lines[1].value.currencyCode").value("TZS"));
    }

    /** Uses a plainly made-up {@code "NO_SUCH_RETURN_TYPE"}, which is now the honest way to reach
     * this branch. It used to send {@code "ANNUAL_AUDITED"} instead, precisely BECAUSE that was an
     * advertised {@code GenerateReturnRequest.returnType} enum member with no seeded definition
     * behind it -- so a made-up string was intercepted by request-schema validation as a 400 before
     * ever reaching the service. The M10 final review (C2/I4) removed that enum: it advertised
     * {@code ANNUAL_AUDITED}/{@code STATISTICAL} as supported when neither has a definition or an
     * implementation, and the service layer's own "no definition found" check is the real gate. With
     * the enum gone, request-schema validation no longer intercepts an unrecognised string and this
     * test exercises the genuine 422 branch with a genuinely unknown value. */
    @Test
    void generateReturnReturns422ForAnUnknownReturnType() throws Exception {
        mockMvc.perform(post("/regulatory-returns").with(financeStaffOf(SEEDED_TENANT))
                .contentType(MediaType.APPLICATION_JSON)
                .content(generateReturnBody("NO_SUCH_RETURN_TYPE", "2026-Q1")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("REGREPORTING_VALIDATION_FAILED"));
    }

    /** QUARTERLY_PRUDENTIAL's period_kind is QUARTERLY (regreporting/V2 section 9) -- an
     * annual-shaped period ("YYYY") contradicts it, exactly the case
     * {@code ReturnGeneratorTest.anAnnualPeriodIsRejectedForAQuarterlyDefinitionBeforeAnyMetricIsRead}
     * and {@code RegreportingApiIntegrationTest.anAnnualPeriodIsRejectedForTheQuarterlySeededDefinition}
     * already cover below the HTTP layer; this is that same rejection proven over the wire. */
    @Test
    void generateReturnReturns422ForAPeriodFormatContradictingTheDefinitionsPeriodKind() throws Exception {
        mockMvc.perform(post("/regulatory-returns").with(financeStaffOf(SEEDED_TENANT))
                .contentType(MediaType.APPLICATION_JSON)
                .content(generateReturnBody(RETURN_TYPE, "2026")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("REGREPORTING_VALIDATION_FAILED"));
    }

    @Test
    void generateReturnReturns403ForStaffWithoutTheFinanceRole() throws Exception {
        mockMvc.perform(post("/regulatory-returns").with(underwriterStaffOf(SEEDED_TENANT))
                .contentType(MediaType.APPLICATION_JSON)
                .content(generateReturnBody(RETURN_TYPE, "2026-Q1")))
            .andExpect(status().isForbidden());
    }

    @Test
    void generateReturnReturns403ForAnAgentToken() throws Exception {
        mockMvc.perform(post("/regulatory-returns").with(agentOf(SEEDED_TENANT, UUID.randomUUID()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(generateReturnBody(RETURN_TYPE, "2026-Q1")))
            .andExpect(status().isForbidden());
    }

    /** THE most security-sensitive single assertion in this task: a regulator is read-only
     * (openapi-regreporting.yaml's {@code /regulatory-returns} POST declares only {@code
     * staffAuth}, never {@code regulatorAuth}) and must never be able to generate a return.
     * {@code RegulatoryReturnController.generateReturn}'s {@code @PreAuthorize} names only
     * {@code REALM_STAFF} plus FINANCE_OFFICER/ADMIN -- this proves {@code ROLE_REALM_REGULATORS}
     * alone, with no staff role at all, is rejected rather than incidentally satisfying that
     * expression. */
    @Test
    void generateReturnReturns403ForARegulatorToken() throws Exception {
        mockMvc.perform(post("/regulatory-returns").with(regulatorOf(SEEDED_TENANT))
                .contentType(MediaType.APPLICATION_JSON)
                .content(generateReturnBody(RETURN_TYPE, "2026-Q1")))
            .andExpect(status().isForbidden());
    }

    // ============================================================================================
    // GET /regulatory-returns
    // ============================================================================================

    @Test
    void listReturnsReturns200ForFinanceStaffAndRegulatorWithThePeriodFilterGenuinelyNarrowing() throws Exception {
        // A real target must exist first (per the class javadoc), so a broken @PreAuthorize would
        // return 200 rather than an incidental empty-array 200 that would pass for the wrong reason.
        seedReturn("2026-Q2");

        mockMvc.perform(get("/regulatory-returns").param("period", "2026-Q2").with(financeStaffOf(SEEDED_TENANT)))
            .andExpect(status().isOk())
            .andExpect(SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "RegulatoryReturnView"))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].period").value("2026-Q2"))
            .andExpect(jsonPath("$[0].lines[0].value").isString())
            .andExpect(jsonPath("$[0].lines[1].value").isMap());

        // REALM_REGULATORS is genuinely permitted on this read path (openapi-regreporting.yaml
        // names both staffAuth and regulatorAuth for GET /regulatory-returns), unlike POST above.
        mockMvc.perform(get("/regulatory-returns").param("period", "2026-Q2").with(regulatorOf(SEEDED_TENANT)))
            .andExpect(status().isOk())
            .andExpect(SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "RegulatoryReturnView"))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].period").value("2026-Q2"))
            .andExpect(jsonPath("$[0].lines[0].value").isString())
            .andExpect(jsonPath("$[0].lines[1].value").isMap());

        // The falsifiable half -- a filter that ignored its argument would still return the row
        // seeded above for 2026-Q2. No other test in this class ever generates this period.
        mockMvc.perform(get("/regulatory-returns").param("period", "2031-Q1").with(financeStaffOf(SEEDED_TENANT)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listReturnsReturns403ForAnAgentToken() throws Exception {
        // A real target must exist first, so a broken @PreAuthorize would return 200 rather than
        // an incidental empty-array 200 that would pass for the wrong reason.
        seedReturn("2026-Q1");

        mockMvc.perform(get("/regulatory-returns").with(agentOf(SEEDED_TENANT, UUID.randomUUID())))
            .andExpect(status().isForbidden());
    }

    // ============================================================================================
    // GET /regulatory-returns/{returnId}
    // ============================================================================================

    @Test
    void getReturnReturns200WithLinesInLineNoOrderForBothFinanceStaffAndRegulator() throws Exception {
        RegulatoryReturnView seeded = seedReturn("2026-Q3");

        mockMvc.perform(get("/regulatory-returns/{returnId}", seeded.returnId()).with(financeStaffOf(SEEDED_TENANT)))
            .andExpect(status().isOk())
            .andExpect(SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "RegulatoryReturnView"))
            .andExpect(jsonPath("$.returnId").value(seeded.returnId().toString()))
            .andExpect(jsonPath("$.lines.length()").value(10))
            .andExpect(jsonPath("$.lines[0].lineNo").value(1))
            .andExpect(jsonPath("$.lines[9].lineNo").value(10))
            .andExpect(jsonPath("$.lines[0].value").isString())
            .andExpect(jsonPath("$.lines[1].value").isMap())
            .andExpect(jsonPath("$.lines[1].value.amount").isString());

        mockMvc.perform(get("/regulatory-returns/{returnId}", seeded.returnId()).with(regulatorOf(SEEDED_TENANT)))
            .andExpect(status().isOk())
            .andExpect(SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "RegulatoryReturnView"))
            .andExpect(jsonPath("$.returnId").value(seeded.returnId().toString()))
            .andExpect(jsonPath("$.lines.length()").value(10))
            .andExpect(jsonPath("$.lines[0].lineNo").value(1))
            .andExpect(jsonPath("$.lines[9].lineNo").value(10))
            .andExpect(jsonPath("$.lines[0].value").isString())
            .andExpect(jsonPath("$.lines[1].value").isMap())
            .andExpect(jsonPath("$.lines[1].value.amount").isString());
    }

    @Test
    void getReturnReturns404ForAnUnknownId() throws Exception {
        mockMvc.perform(get("/regulatory-returns/{returnId}", UUID.randomUUID()).with(financeStaffOf(SEEDED_TENANT)))
            .andExpect(status().isNotFound())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("RETURN_NOT_FOUND"));
    }

    /** 404, NEVER 403, for a return belonging to a different tenant: {@code RegreportingApiImpl
     * .getReturn} scopes its query by {@code (returnId, tenantId)} directly rather than relying
     * on RLS alone (see that method's own javadoc), specifically so that a return's existence in
     * another tenant is never distinguishable from an unknown id. A 403 here would itself BE the
     * cross-tenant information leak RLS/tenant-scoping exists to prevent -- confirming to tenant B
     * that this id exists somewhere, just not for them. */
    @Test
    void getReturnReturns404NotForbiddenForAReturnBelongingToADifferentTenant() throws Exception {
        RegulatoryReturnView seeded = seedReturn("2026-Q4");
        UUID otherTenant = UUID.randomUUID();

        mockMvc.perform(get("/regulatory-returns/{returnId}", seeded.returnId()).with(financeStaffOf(otherTenant)))
            .andExpect(status().isNotFound())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("RETURN_NOT_FOUND"));
    }
}
