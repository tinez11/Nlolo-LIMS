package tz.co.nlolo.lifeplatform.unitlinked.infrastructure;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tz.co.nlolo.lifeplatform.unitlinked.api.FundNotFoundException;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedStateException;

import java.util.UUID;

/**
 * Same ProblemDetail shape as every module. Scoped to this module's controllers, so a refusal from a funds
 * screen reads as the register's own words rather than the global handler's.
 */
@RestControllerAdvice(basePackageClasses = FundController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UnitLinkedExceptionHandler {

    @ExceptionHandler(FundNotFoundException.class)
    public ProblemDetail handleNotFound(FundNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "FUND_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(UnitLinkedStateException.class)
    public ProblemDetail handleState(UnitLinkedStateException ex) {
        return problem(HttpStatus.CONFLICT, "UNIT_LINKED_STATE", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleInvalid(IllegalArgumentException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "UNIT_LINKED_INVALID", ex.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String code, String message) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, message);
        problem.setProperty("errorCode", code);
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }
}
