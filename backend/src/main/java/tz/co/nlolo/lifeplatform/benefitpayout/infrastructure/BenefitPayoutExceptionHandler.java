package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutNotFoundException;
import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutStateException;

import java.util.UUID;

/** Same ProblemDetail shape as every other module: an errorCode and a copyable traceId. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BenefitPayoutExceptionHandler {

    /** Understood and refused on a rule, not malformed -- 422, like policy's and party's own. */
    @ExceptionHandler(PayoutStateException.class)
    public ProblemDetail handleRefused(PayoutStateException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), "PAYOUT_REFUSED");
    }

    @ExceptionHandler(PayoutNotFoundException.class)
    public ProblemDetail handleNotFound(PayoutNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "PAYOUT_NOT_FOUND");
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String errorCode) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("errorCode", errorCode);
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }
}
