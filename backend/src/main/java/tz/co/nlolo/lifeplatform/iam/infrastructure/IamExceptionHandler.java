package tz.co.nlolo.lifeplatform.iam.infrastructure;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tz.co.nlolo.lifeplatform.iam.api.AccessChangeRefusedException;

import java.util.UUID;

/** The iam module's own refusals, in the shape every module uses: a status, the reason, and an errorCode. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IamExceptionHandler {

    @ExceptionHandler(AccessChangeRefusedException.class)
    public ProblemDetail handle(AccessChangeRefusedException ex) {
        HttpStatus status = switch (ex.reason()) {
            case SELF_CHANGE, SAME_PERSON_APPROVES -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ROLE_CODE_TAKEN, USERNAME_TAKEN, NOT_PENDING, LAST_ADMINISTRATOR -> HttpStatus.CONFLICT;
            case LOGIN_PROVIDER -> HttpStatus.BAD_GATEWAY;
            case UNKNOWN_ROLE, UNKNOWN_PERMISSION -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setProperty("errorCode", "ACCESS_" + ex.reason().name());
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }
}
