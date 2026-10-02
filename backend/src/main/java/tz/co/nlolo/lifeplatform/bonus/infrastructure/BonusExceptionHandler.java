package tz.co.nlolo.lifeplatform.bonus.infrastructure;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tz.co.nlolo.lifeplatform.bonus.api.BonusStateException;

import java.util.UUID;

/**
 * Same ProblemDetail shape as every other module. Handles this module's own exception only, so its
 * highest precedence takes nothing from any other module's handling.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BonusExceptionHandler {

    @ExceptionHandler(BonusStateException.class)
    public ProblemDetail handleRefused(BonusStateException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setProperty("errorCode", "BONUS_REFUSED");
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }

    @ExceptionHandler(NotWithProfitsException.class)
    public ProblemDetail handleNotWithProfits(NotWithProfitsException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setProperty("errorCode", "NOT_WITH_PROFITS");
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }
}
