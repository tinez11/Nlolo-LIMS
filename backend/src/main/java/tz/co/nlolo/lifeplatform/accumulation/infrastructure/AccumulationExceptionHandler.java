package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tz.co.nlolo.lifeplatform.accumulation.api.AccountNotFoundException;
import tz.co.nlolo.lifeplatform.accumulation.api.AccumulationStateException;

import java.util.UUID;

/** Same ProblemDetail shape as every other module: an errorCode and a copyable traceId. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AccumulationExceptionHandler {

    @ExceptionHandler(AccumulationStateException.class)
    public ProblemDetail handleRefused(AccumulationStateException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), "ACCUMULATION_REFUSED");
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ProblemDetail handleNotFound(AccountNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "ACCOUNT_NOT_FOUND");
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String errorCode) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("errorCode", errorCode);
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }
}
