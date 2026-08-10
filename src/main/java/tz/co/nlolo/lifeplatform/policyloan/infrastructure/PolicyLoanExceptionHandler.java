package tz.co.nlolo.lifeplatform.policyloan.infrastructure;

import tz.co.nlolo.lifeplatform.policyloan.api.LoanNotEligibleException;
import tz.co.nlolo.lifeplatform.policyloan.api.LoanNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

/**
 * @Order(HIGHEST_PRECEDENCE) is required -- see PolicyExceptionHandler's identical note (Task
 * 4): @ExceptionHandler resolution across @ControllerAdvice beans is first-matching-advice-bean-
 * wins, not merged-by-specificity, so an unordered advice can be silently shadowed by
 * GlobalExceptionHandler's catch-all depending on classpath-scan order. This class maps ONLY
 * policyloan-owned exception types. InsufficientLoanValueException and
 * InvalidPolicyStateException are policy-owned and already mapped to 409 application-wide by
 * policy.infrastructure.PolicyExceptionHandler (also HIGHEST_PRECEDENCE) -- they are
 * deliberately NOT re-mapped here.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PolicyLoanExceptionHandler {

    @ExceptionHandler(LoanNotFoundException.class)
    public ProblemDetail handleNotFound(LoanNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "LOAN_NOT_FOUND");
    }

    @ExceptionHandler(LoanNotEligibleException.class)
    public ProblemDetail handleNotEligible(LoanNotEligibleException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), "LOAN_NOT_ELIGIBLE");
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String errorCode) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("errorCode", errorCode);
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }
}
