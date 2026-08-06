package tz.co.nlolo.lifeplatform.party.infrastructure;

import tz.co.nlolo.lifeplatform.party.api.DuplicateRegistrationNumberException;
import tz.co.nlolo.lifeplatform.party.api.PartyNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

/**
 * Maps domain exceptions to RFC 7807 application/problem+json responses
 * (docs/04-api-contracts.md §2). Spring's ProblemDetail already produces
 * type/title/status/detail/instance; errorCode and traceId are the two
 * platform-specific extensions doc04 §2 describes. Not implementing the full
 * dereferenceable type-URI scheme (".../problems/...") here -- party has no
 * semantically rich 422 cases yet that would need one; a simplification, not
 * a silent gap.
 */
@RestControllerAdvice
public class PartyExceptionHandler {

    @ExceptionHandler(PartyNotFoundException.class)
    public ProblemDetail handleNotFound(PartyNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "PARTY_NOT_FOUND");
    }

    @ExceptionHandler(DuplicateRegistrationNumberException.class)
    public ProblemDetail handleDuplicate(DuplicateRegistrationNumberException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), "DUPLICATE_REGISTRATION_NUMBER");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleValidation(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, ex.getMessage(), "VALIDATION_ERROR");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, ex.getMessage(), "FORBIDDEN");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGenericException(Exception ex) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", "INTERNAL_ERROR");
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String errorCode) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("errorCode", errorCode);
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }
}
