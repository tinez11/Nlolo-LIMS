package tz.co.nlolo.lifeplatform.reinsurance.infrastructure;

import tz.co.nlolo.lifeplatform.reinsurance.api.BordereauNotFoundException;
import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceValidationException;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

/**
 * Scoped to {@code reinsurance}'s own domain exception types, mirroring
 * {@code DistributionExceptionHandler}'s shape and its shared {@code @Order(HIGHEST_PRECEDENCE)}
 * requirement. That annotation is not decoration: {@code @ExceptionHandler} resolution is
 * first-matching-ADVICE-BEAN-wins across {@code @ControllerAdvice} beans rather than
 * merged-by-specificity, so an unordered advice here could be silently shadowed by
 * {@code GlobalExceptionHandler}'s {@code @Order(LOWEST_PRECEDENCE)} catch-all depending on
 * classpath-scan order.
 *
 * <p>Deliberately does NOT map {@code IllegalArgumentException} or {@code AccessDeniedException}:
 * {@code GlobalExceptionHandler:78} and {@code :83} already map them to 400 {@code VALIDATION_ERROR}
 * and 403 {@code FORBIDDEN}. A duplicate advice here would itself be the two-advices-shadowing-each-
 * other bug this discipline exists to prevent.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ReinsuranceExceptionHandler {

    @ExceptionHandler(TreatyNotFoundException.class)
    public ProblemDetail handleTreatyNotFound(TreatyNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "TREATY_NOT_FOUND");
    }

    @ExceptionHandler(BordereauNotFoundException.class)
    public ProblemDetail handleBordereauNotFound(BordereauNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "BORDEREAU_NOT_FOUND");
    }

    @ExceptionHandler(tz.co.nlolo.lifeplatform.reinsurance.api.StatementNotFoundException.class)
    public ProblemDetail handleStatementNotFound(tz.co.nlolo.lifeplatform.reinsurance.api.StatementNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "STATEMENT_NOT_FOUND");
    }

    /** IFRS 17 I3d: a quarter not settleable yet, a statement already there, or a step its state or people refuse. */
    @ExceptionHandler(tz.co.nlolo.lifeplatform.reinsurance.api.StatementStateException.class)
    public ProblemDetail handleStatementState(tz.co.nlolo.lifeplatform.reinsurance.api.StatementStateException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), "STATEMENT_STATE");
    }

    @ExceptionHandler(ReinsuranceValidationException.class)
    public ProblemDetail handleValidation(ReinsuranceValidationException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), "REINSURANCE_VALIDATION_FAILED");
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
