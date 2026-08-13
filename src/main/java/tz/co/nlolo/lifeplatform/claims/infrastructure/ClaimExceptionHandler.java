package tz.co.nlolo.lifeplatform.claims.infrastructure;

import tz.co.nlolo.lifeplatform.claims.api.ClaimNotFoundException;
import tz.co.nlolo.lifeplatform.claims.api.ClaimValidationException;
import tz.co.nlolo.lifeplatform.claims.api.InvalidClaimStateException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

/**
 * Genuinely scoped to {@code claims}' own domain exception types -- mirrors
 * {@code PaymentExceptionHandler}/{@code BillingExceptionHandler}/{@code PolicyExceptionHandler}'s
 * exact shape and their shared {@code @Order(HIGHEST_PRECEDENCE)} requirement (see
 * {@code PartyExceptionHandler}'s javadoc for the full reasoning: {@code @ExceptionHandler}
 * resolution is first-matching-ADVICE-BEAN-wins across {@code @ControllerAdvice} beans, not
 * merged-by-specificity, so an unordered advice here could be silently shadowed by
 * {@code GlobalExceptionHandler}'s {@code @Order(LOWEST_PRECEDENCE)} catch-all depending on
 * classpath-scan order -- the exact M1 regression this annotation exists to prevent).
 *
 * <p>Deliberately does NOT map {@code PolicyNotFoundException} or {@code PartyNotFoundException}:
 * both already propagate to their own module's existing {@code @Order(HIGHEST_PRECEDENCE)} advice
 * ({@code PolicyExceptionHandler} -> 404 {@code POLICY_NOT_FOUND}, {@code PartyExceptionHandler} ->
 * 404 {@code PARTY_NOT_FOUND}) when {@code ClaimsApiImpl.registerClaim} calls {@code policyApi
 * .getPolicy}/{@code partyApi.getParty} and either throws. Adding a duplicate handler for either
 * type here would itself be the two-unordered-advices-shadowing-each-other bug this whole
 * {@code @Order} discipline exists to avoid, not a fix for one.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ClaimExceptionHandler {

    @ExceptionHandler(ClaimNotFoundException.class)
    public ProblemDetail handleNotFound(ClaimNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "CLAIM_NOT_FOUND");
    }

    @ExceptionHandler(ClaimValidationException.class)
    public ProblemDetail handleValidation(ClaimValidationException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), "CLAIM_VALIDATION_FAILED");
    }

    @ExceptionHandler(InvalidClaimStateException.class)
    public ProblemDetail handleInvalidState(InvalidClaimStateException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), "CLAIM_INVALID_STATE");
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String errorCode) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("errorCode", errorCode);
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }
}
