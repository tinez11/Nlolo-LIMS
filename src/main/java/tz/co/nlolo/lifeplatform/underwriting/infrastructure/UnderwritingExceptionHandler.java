package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseNotFoundException;
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
}
