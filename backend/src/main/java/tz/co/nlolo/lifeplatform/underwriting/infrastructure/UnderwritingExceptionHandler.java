package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.underwriting.api.SeniorUnderwriterApprovalRequiredException;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseAlreadyDecidedException;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseNotFoundException;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingValidationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

/**
 * @Order(HIGHEST_PRECEDENCE) is required -- Spring resolves @ExceptionHandler methods
 * by first-matching-ADVICE-BEAN-wins, not merged-by-specificity across beans. Without
 * this, GlobalExceptionHandler's catch-all could shadow these domain-specific mappings
 * depending on classpath-scan order (the exact regression M1's final review found and
 * fixed for PartyExceptionHandler -- do not repeat it here).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UnderwritingExceptionHandler {

    @ExceptionHandler(UnderwritingCaseNotFoundException.class)
    public ProblemDetail handleNotFound(UnderwritingCaseNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setProperty("errorCode", "UNDERWRITING_CASE_NOT_FOUND");
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }

    @ExceptionHandler(UnderwritingCaseAlreadyDecidedException.class)
    public ProblemDetail handleAlreadyDecided(UnderwritingCaseAlreadyDecidedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setProperty("errorCode", "UNDERWRITING_CASE_ALREADY_DECIDED");
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }

    /**
     * 403, not 422: the request is well formed and the decision may be perfectly correct --
     * this caller is simply not senior enough to make it. A 422 would read as "fix your input",
     * and the fix is to fetch a senior underwriter.
     */
    @ExceptionHandler(SeniorUnderwriterApprovalRequiredException.class)
    public ProblemDetail handleSeniorApprovalRequired(SeniorUnderwriterApprovalRequiredException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setProperty("errorCode", "SENIOR_UNDERWRITER_APPROVAL_REQUIRED");
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }

    @ExceptionHandler(UnderwritingValidationException.class)
    public ProblemDetail handleValidation(UnderwritingValidationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setProperty("errorCode", "UNDERWRITING_VALIDATION_FAILED");
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }
}
