package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import tz.co.nlolo.lifeplatform.distribution.api.AgentNotFoundException;
import tz.co.nlolo.lifeplatform.distribution.api.CommissionPlanNotFoundException;
import tz.co.nlolo.lifeplatform.distribution.api.CommissionStatementNotFoundException;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionValidationException;
import tz.co.nlolo.lifeplatform.distribution.api.InvalidAgentStateException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

/**
 * Scoped to {@code distribution}'s own domain exception types, mirroring
 * {@code ClaimExceptionHandler}/{@code PaymentExceptionHandler}/{@code PolicyExceptionHandler}'s
 * shape and their shared {@code @Order(HIGHEST_PRECEDENCE)} requirement. That annotation is not
 * decoration: {@code @ExceptionHandler} resolution is first-matching-ADVICE-BEAN-wins across
 * {@code @ControllerAdvice} beans rather than merged-by-specificity, so an unordered advice here
 * could be silently shadowed by {@code GlobalExceptionHandler}'s {@code @Order(LOWEST_PRECEDENCE)}
 * catch-all depending on classpath-scan order -- the M1 regression this discipline prevents.
 *
 * <p>Deliberately does NOT map {@code PartyNotFoundException} or {@code ProductNotFoundException},
 * both of which {@code DistributionApiImpl} can trigger via {@code PartyApi.getParty} (onboarding's
 * KYC check) and {@code ProductApi.getActiveSnapshot} (plan authoring). Verified rather than
 * assumed: {@code PartyExceptionHandler:51} already maps the first to 404 {@code PARTY_NOT_FOUND}
 * and {@code ProductExceptionHandler:28} the second to 404 {@code PRODUCT_NOT_FOUND}, both at
 * HIGHEST_PRECEDENCE. Adding duplicates here would itself be the two-advices-shadowing-each-other
 * bug, not a fix for one.
 *
 * <p>{@code CommissionStatementNotFoundException} is mapped even though the plan's table omits it:
 * it is a real {@code distribution.api} type that both {@code listAccruals} and {@code
 * requestStatementPayout} throw, and leaving it unmapped would surface a 500 from the catch-all
 * for an ordinary unknown-id read.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DistributionExceptionHandler {

    @ExceptionHandler(AgentNotFoundException.class)
    public ProblemDetail handleAgentNotFound(AgentNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "AGENT_NOT_FOUND");
    }

    @ExceptionHandler(CommissionPlanNotFoundException.class)
    public ProblemDetail handlePlanNotFound(CommissionPlanNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "COMMISSION_PLAN_NOT_FOUND");
    }

    @ExceptionHandler(CommissionStatementNotFoundException.class)
    public ProblemDetail handleStatementNotFound(CommissionStatementNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "COMMISSION_STATEMENT_NOT_FOUND");
    }

    @ExceptionHandler(DistributionValidationException.class)
    public ProblemDetail handleValidation(DistributionValidationException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), "DISTRIBUTION_VALIDATION_FAILED");
    }

    @ExceptionHandler(InvalidAgentStateException.class)
    public ProblemDetail handleInvalidState(InvalidAgentStateException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), "DISTRIBUTION_INVALID_STATE");
    }

    /** {@code traceId} is REQUIRED by openapi-common.yaml's ProblemDetails schema -- an M6 contract
     * test caught a filter omitting it, so every branch here sets it. */
    private static ProblemDetail problem(HttpStatus status, String detail, String errorCode) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("errorCode", errorCode);
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }
}
