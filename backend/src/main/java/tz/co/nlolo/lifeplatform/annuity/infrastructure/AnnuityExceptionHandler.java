package tz.co.nlolo.lifeplatform.annuity.infrastructure;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

/**
 * Same ProblemDetail shape as every module. Only this module's own exception: the pricer's refusal is
 * mapped once, globally, by ProductExceptionHandler (422 ANNUITY_NOT_PRICEABLE).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AnnuityExceptionHandler {

    @ExceptionHandler(NotAnAnnuityPolicyException.class)
    public ProblemDetail handleNotAnAnnuity(NotAnAnnuityPolicyException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setProperty("errorCode", "NOT_AN_ANNUITY");
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }
}
