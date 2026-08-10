package tz.co.nlolo.lifeplatform.policyloan;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import com.jayway.jsonpath.JsonPath;
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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Module-Architecture-B1 (docs/02-module-architecture.md §3.4/§3.5) end-to-end.
 *
 * <p>{@code policy.ModuleArchitectureB1ConcurrencyTest} proves the reserve/confirm/release
 * MECHANISM by calling {@code PolicyApi.reserveLoanValue} directly as a Java object. This test
 * proves the WIRING AROUND IT survives the real HTTP boundary: two concurrent
 * {@code POST /policies/{policyNumber}/loans} requests, each individually affordable and jointly
 * an overdraw, going through the security filter chain, {@code TenantContextFilter},
 * {@code PolicyLoanController}, {@code PolicyLoanApiImpl.originateLoan}, and only then into
 * {@code PolicyApi.reserveLoanValue}'s {@code PESSIMISTIC_WRITE} lock on {@code policy_account}.
 *
 * <p><b>What is deliberately NOT tested here.</b> The "TTL sweep expires the reservation
 * mid-{@code originateLoan}" scenario is UNREACHABLE through {@code originateLoan} by
 * construction: per a standing ruling, {@code originateLoan} runs {@code policy} and
 * {@code policyloan} in ONE physical transaction (see its own Javadoc), so the reservation it
 * creates is never visible to another transaction before that same transaction confirms it. A
 * test claiming to prove that scenario could not fail for the reason it would claim. It becomes
 * reachable, and must then be written, only when M5 splits the two transactions apart.
 *
 * <p><b>TenantContext is a ThreadLocal and does not propagate to child threads.</b> On the
 * MockMvc path {@code TenantContextFilter} sets it per-request from the JWT's {@code tenant_id}
 * claim, on whichever thread that request is dispatched on -- and {@code MockMvc.perform} runs
 * the whole filter chain synchronously on the calling thread, so each worker gets its own. The
 * explicit {@code TenantContext.set}/{@code clear} inside the {@code Callable} below is belt and
 * braces for anything in the task that runs OUTSIDE a dispatched request. Getting this wrong is
 * how a concurrency test passes for the wrong reason: RLS fails closed, so a connection with no
 * tenant sees zero rows and the "race" never happens at all.
 *
 * <p><b>Negative control performed (see task-8-report.md for the captured output).</b> With the
 * {@code @Lock(LockModeType.PESSIMISTIC_WRITE)} removed from
 * {@code PolicyAccountRepository.lockByPolicyNumber} -- the exact mechanism this test claims to
 * protect -- this test FAILS; with it restored it passes. A concurrency test never observed
 * failing is decoration.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ModuleArchitectureB1EndToEndRaceTest {

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
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policyloan/V1__create_policyloan_schema.sql",
            // Required, and absent from the task brief's list -- see PolicyLoanContractTest's
            // note: audit.DomainEventAuditListener consumes every published domain event
            // application-wide and swallows its own persistence failures.
            "db-migrations/audit/V1__create_audit_schema.sql");
    }

    @Autowired private MockMvc mockMvc;

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    private static final BigDecimal CASH_VALUE = new BigDecimal("1000000.00");
    /** Two of these (1,400,000.00) exceed CASH_VALUE; one alone (700,000.00) fits. */
    private static final BigDecimal EACH_REQUEST = new BigDecimal("700000.00");

    private record Attempt(int status, String body) {}

    @Test
    void concurrentLoanOriginationsAgainstTheSamePolicyNeverJointlyOverdrawOverHttp() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String policyNumber = issuePolicyWithCashValueViaHttp(tenantId, "E2E-B1-RACE-01");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Callable<Attempt>> tasks = List.of(
            () -> attemptOriginateOverHttp(tenantId, policyNumber, barrier),
            () -> attemptOriginateOverHttp(tenantId, policyNumber, barrier));
        List<Future<Attempt>> futures = executor.invokeAll(tasks, 60, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        List<Attempt> attempts = futures.stream().map(ModuleArchitectureB1EndToEndRaceTest::resultOf).toList();
        List<Integer> statuses = attempts.stream().map(Attempt::status).toList();

        // THE invariant, stated over HTTP status codes: exactly one 202 and exactly one 409.
        // Two 202s would be the 400,000.00 joint overdraw the reserve/confirm protocol exists to
        // prevent; a 500 would mean the protocol degraded into an unhandled failure instead of a
        // clean, client-actionable rejection.
        assertThat(statuses)
            .as("both attempts' bodies: %s", attempts)
            .containsExactlyInAnyOrder(202, 409);

        // The 409 must be the loan-value rejection specifically -- not some other conflict (e.g.
        // an InvalidPolicyStateException from confirmReservation, or an optimistic-locking
        // failure) that would produce the same status for a different, non-proving reason.
        Attempt rejected = attempts.stream().filter(a -> a.status() == 409).findFirst().orElseThrow();
        assertThat(JsonPath.<String>read(rejected.body(), "$.errorCode")).isEqualTo("INSUFFICIENT_LOAN_VALUE");

        // Durable DB-level cross-checks, not just the returned status codes. The loser's whole
        // transaction rolls back (reserveLoanValue throws before inserting anything), so exactly
        // one loan, exactly one reservation -- CONFIRMED, for exactly the winning amount -- and an
        // encumbrance of exactly one loan's principal must exist. These are what catch a break
        // that still happens to yield one 202 and one 409.
        assertThat(countLoans(policyNumber)).isEqualTo(1);
        assertThat(countAllReservations(policyNumber)).isEqualTo(1);
        assertThat(countConfirmedReservations(policyNumber)).isEqualTo(1);
        assertThat(sumConfirmedReservations(policyNumber)).isEqualByComparingTo(EACH_REQUEST);
        assertThat(readEncumbranceAmount(policyNumber)).isEqualByComparingTo(EACH_REQUEST);

        // ... and the same conclusion read back through the public HTTP surface, which is the
        // boundary this test exists to cover.
        mockMvc.perform(get("/policies/" + policyNumber + "/loans").with(agentOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()").value(1));
    }

    /**
     * TenantContext.set is inside the Callable, on the ExecutorService's own worker thread --
     * TenantContext is a ThreadLocal and never propagates from the test's main thread. (The
     * dispatched request below would also get it from TenantContextFilter; this makes the task
     * correct regardless.) The barrier maximizes the real overlap between the two dispatches.
     */
    private Attempt attemptOriginateOverHttp(UUID tenantId, String policyNumber, CyclicBarrier barrier) throws Exception {
        TenantContext.set(tenantId);
        try {
            barrier.await(10, TimeUnit.SECONDS);
            MvcResult result = mockMvc.perform(post("/policies/" + policyNumber + "/loans")
                    .with(agentOf(tenantId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"requestedAmount":{"amount":"%s","currencyCode":"TZS"},"payeeRef":"MPESA-0712345678"}
                        """.formatted(EACH_REQUEST.toPlainString())))
                .andReturn();
            return new Attempt(result.getResponse().getStatus(), result.getResponse().getContentAsString());
        } finally {
            TenantContext.clear();
        }
    }

    private static Attempt resultOf(Future<Attempt> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            // An exception escaping the dispatch itself (rather than being turned into a status
            // code) is a genuine failure of this test, not something to swallow into a status.
            throw new IllegalStateException("loan origination attempt did not complete", e);
        }
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor agentOf(UUID tenantId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
            .jwt(builder -> builder.claim("tenant_id", tenantId.toString()));
    }

    /**
     * Full-HTTP fixture chain, mirroring {@code PolicyLoanContractTest}'s: the point of this test
     * is that the whole stack in front of the lock is real, so the policy it races against is
     * built over real HTTP too. Stops at "underwriting case opened" deliberately -- submitting an
     * assessment would make {@code UnderwritingDecisionEventListener} auto-issue a policy
     * synchronously and the manual-issue below would then double-issue.
     *
     * <p>The trailing JDBC bump is mandatory: {@code policy.policy_account.cash_value_amount} is
     * hardcoded to ZERO at issuance, so without it there is no loan value to race for and BOTH
     * attempts would 409 -- a test that passes only in the sense that nothing succeeded.
     */
    private String issuePolicyWithCashValueViaHttp(UUID tenantId, String productCode) throws Exception {
        String applicantResponse = mockMvc.perform(post("/parties/individuals")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"E2E Race Applicant","dateOfBirth":"1990-01-01","contactInfo":{"phoneNumber":"+255714%06d"}}
                    """.formatted(Math.abs(productCode.hashCode() % 1000000))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String applicantId = JsonPath.read(applicantResponse, "$.partyId");

        String productResponse = mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"%s","productName":"E2E Race Product","category":"TERM_LIFE","defaultCurrency":"TZS"}
                    """.formatted(productCode)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String productId = JsonPath.read(productResponse, "$.productId");

        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ifrsMeasurementModel":"PAA","effectiveDate":"2026-01-01",
                     "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0},{"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
                     "benefitSchedule":[{"benefitType":"DEATH","calculationMethod":"SUM_ASSURED"}]}
                    """))
            .andExpect(status().isCreated());

        String snapshotResponse = mockMvc.perform(get("/products/" + productId + "/active-snapshot")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String productVersionId = JsonPath.read(snapshotResponse, "$.productVersionId");

        String caseResponse = mockMvc.perform(post("/underwriting/cases")
                .with(agentOf(tenantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"applicantPartyId":"%s","productId":"%s","productVersionId":"%s","sumAssured":{"amount":"1000000.00","currencyCode":"TZS"}}
                    """.formatted(applicantId, productId, productVersionId)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String caseId = JsonPath.read(caseResponse, "$.caseId");

        String policyResponse = mockMvc.perform(post("/policies/manual-issue")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"underwritingCaseId":"%s","policyholderPartyId":"%s","productVersionId":"%s",
                     "sumAssured":{"amount":"%s","currencyCode":"TZS"},"agentOfRecordId":"%s",
                     "reasonForManualIssue":"E2E race test issuance"}
                    """.formatted(caseId, applicantId, productVersionId, CASH_VALUE.toPlainString(), UUID.randomUUID())))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String policyNumber = JsonPath.read(policyResponse, "$.policyNumber");

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement(
                 "UPDATE policy.policy_account SET cash_value_amount = ? WHERE policy_number = ?")) {
            statement.setBigDecimal(1, CASH_VALUE);
            statement.setString(2, policyNumber);
            assertThat(statement.executeUpdate()).isEqualTo(1); // fail loudly if the seed never landed
        }
        return policyNumber;
    }

    private static int countLoans(String policyNumber) throws SQLException {
        return queryFirstColumn("SELECT COUNT(*) FROM policyloan.policy_loan WHERE policy_number = ?", policyNumber).intValue();
    }

    private static int countAllReservations(String policyNumber) throws SQLException {
        return queryFirstColumn("SELECT COUNT(*) FROM policy.loan_value_reservation WHERE policy_number = ?", policyNumber).intValue();
    }

    private static int countConfirmedReservations(String policyNumber) throws SQLException {
        return queryFirstColumn("SELECT COUNT(*) FROM policy.loan_value_reservation "
            + "WHERE policy_number = ? AND status = 'CONFIRMED'", policyNumber).intValue();
    }

    private static BigDecimal sumConfirmedReservations(String policyNumber) throws SQLException {
        return queryFirstColumn("SELECT COALESCE(SUM(amount), 0) FROM policy.loan_value_reservation "
            + "WHERE policy_number = ? AND status = 'CONFIRMED'", policyNumber);
    }

    private static BigDecimal readEncumbranceAmount(String policyNumber) throws SQLException {
        return queryFirstColumn("SELECT loan_encumbrance_amount FROM policy.policy_account WHERE policy_number = ?", policyNumber);
    }

    /** Every assertion query here is single-row, single-numeric-column, so one helper covers all
     * of them -- and everything is closed on the way out rather than handing a live ResultSet
     * back to the caller. */
    private static BigDecimal queryFirstColumn(String sql, String policyNumber) throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, policyNumber);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).as("query returned no row: %s", sql).isTrue();
                return resultSet.getBigDecimal(1);
            }
        }
    }
}
