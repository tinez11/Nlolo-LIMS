package tz.co.nlolo.lifeplatform;

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policyloan.api.LoanStatus;
import tz.co.nlolo.lifeplatform.policyloan.api.LoanView;
import tz.co.nlolo.lifeplatform.policyloan.api.PolicyLoanApi;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M3 final whole-branch review, Important I1: there was NO {@code @ExceptionHandler} for
 * {@link org.springframework.dao.OptimisticLockingFailureException} anywhere in {@code src/},
 * so an optimistic-lock loss fell through {@code GlobalExceptionHandler}'s
 * {@code Exception.class} catch-all and returned a bare 500 {@code INTERNAL_ERROR} -- while
 * {@code api/openapi/openapi-common.yaml}'s shared {@code Conflict} response is described
 * verbatim as "Optimistic-locking conflict or state-machine violation" and 500 is not declared
 * on any of these paths.
 *
 * <p><b>What each half of the proof covers, and why it takes two classes.</b> This class proves
 * the MAPPING over real HTTP through the real advice chain, deterministically, by making a real
 * endpoint's collaborator raise the exact exception type Spring Data JPA raises
 * ({@link ObjectOptimisticLockingFailureException}, a subclass of
 * {@code OptimisticLockingFailureException}). That the real code genuinely PRODUCES that
 * exception is proved separately, against a real database and with no mocking at all, by
 * {@code policyloan.PolicyLoanApiIntegrationTest
 * .concurrentRepaymentLosesTheVersionRaceAndRaisesAnOptimisticLockingFailure}. Neither test is
 * meaningful without the other: this one would pass against an exception no code path ever
 * throws, and that one would pass against a 500.
 *
 * <p><b>The advice-precedence trap this project has been bitten by twice.</b>
 * {@code GlobalExceptionHandler} is {@code @Order(LOWEST_PRECEDENCE)} and all five module
 * advices are {@code @Order(HIGHEST_PRECEDENCE)}, and Spring resolves
 * {@code @ExceptionHandler} by first-matching-ADVICE-BEAN-wins, not by merged specificity. The
 * two tests below deliberately hit endpoints owned by the two modules whose advices could
 * plausibly interfere -- {@code PolicyLoanExceptionHandler} and {@code PolicyExceptionHandler},
 * both of which already map a different exception to 409 -- so a future advice that starts
 * matching a supertype of this exception (a {@code DataAccessException} or
 * {@code RuntimeException} handler, say) breaks these tests instead of silently restoring the
 * 500.
 *
 * <p>Collaborators are mocked rather than driven through a fixture chain because an optimistic
 * lock race is by definition nondeterministic over HTTP; a two-thread barrier test could pass
 * without either request ever losing a race. Mocking makes the assertion about the advice, and
 * only the advice, and makes it deterministic. No database rows are read or written here, which
 * is why this class applies no migrations.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class OptimisticLockingConflictContractTest {

    private static final String POLICYLOAN_SPEC = "api/openapi/openapi-policyloan.yaml";
    private static final String POLICY_SPEC = "api/openapi/openapi-policy.yaml";

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private MockMvc mockMvc;

    @MockBean private PolicyLoanApi policyLoanApi;
    @MockBean private PolicyApi policyApi;

    private static RequestPostProcessor staff() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
            .jwt(builder -> builder.claim("tenant_id", UUID.randomUUID().toString()).subject("test-staff"));
    }

    @Test
    void concurrentRepaymentThatLosesTheVersionRaceIsMappedTo409NotBare500() throws Exception {
        UUID loanId = UUID.randomUUID();
        // The controller reads the loan first (for the object-level ownership check) before
        // calling recordRepayment; a staff caller short-circuits that check, so this stub only
        // has to get the request past the controller body and into the service call.
        when(policyLoanApi.getLoan(loanId)).thenReturn(new LoanView(loanId, "POL-TEST-0001",
            new BigDecimal("500000.00"), "TZS", new BigDecimal("500000.00"), "TZS",
            new BigDecimal("12.0"), LoanStatus.DISBURSED));
        // Exactly what Spring Data JPA raises when `UPDATE ... WHERE version = N` matches zero
        // rows: the loser of two concurrent repayments against the same DISBURSED loan.
        when(policyLoanApi.recordRepayment(any(), any(), anyString(), anyString(), anyString()))
            .thenThrow(new ObjectOptimisticLockingFailureException("policyloan.domain.PolicyLoan", loanId));

        mockMvc.perform(post("/loans/{loanId}/repayments", loanId)
                .with(staff())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"amount":{"amount":"25000.00","currencyCode":"TZS"},"paymentReference":"MPESA-TEST-1"}"""))
            .andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.errorCode").value("CONCURRENT_MODIFICATION"))
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.traceId").exists())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(POLICYLOAN_SPEC));
    }

    @Test
    void concurrentEndorsementThatLosesTheVersionRaceIsMappedTo409NotBare500() throws Exception {
        // policy.Policy carries @Version too, and PolicyExceptionHandler is @Order(HIGHEST_PRECEDENCE)
        // with its own 409 mappings -- this asserts the root advice is still reached for a type
        // that advice does not declare.
        when(policyApi.applyEndorsement(anyString(), any(), anyString()))
            .thenThrow(new ObjectOptimisticLockingFailureException("policy.domain.Policy", "POL-TEST-0002"));

        mockMvc.perform(post("/policies/{policyNumber}/endorsements", "POL-TEST-0002")
                .with(staff())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"endorsementType":"ADDRESS_CHANGE","effectiveDate":"2026-09-01","changes":{"address":"new"}}"""))
            .andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.errorCode").value("CONCURRENT_MODIFICATION"))
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.traceId").exists())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(POLICY_SPEC));
    }

    @Test
    void aGenuinelyUnexpectedFailureOnTheSamePathStillReturns500() throws Exception {
        // Control for the two tests above: proves the 409 comes from the new
        // OptimisticLockingFailureException mapping specifically, and not from some blanket
        // "any exception on this path is a 409" behaviour. Same endpoint, same caller, same
        // request body -- only the exception type differs.
        UUID loanId = UUID.randomUUID();
        when(policyLoanApi.getLoan(loanId)).thenReturn(new LoanView(loanId, "POL-TEST-0003",
            new BigDecimal("500000.00"), "TZS", new BigDecimal("500000.00"), "TZS",
            new BigDecimal("12.0"), LoanStatus.DISBURSED));
        when(policyLoanApi.recordRepayment(any(), any(), anyString(), anyString(), anyString()))
            .thenThrow(new IllegalStateException("something genuinely unexpected"));

        mockMvc.perform(post("/loans/{loanId}/repayments", loanId)
                .with(staff())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"amount":{"amount":"25000.00","currencyCode":"TZS"},"paymentReference":"MPESA-TEST-2"}"""))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"));
    }
}
