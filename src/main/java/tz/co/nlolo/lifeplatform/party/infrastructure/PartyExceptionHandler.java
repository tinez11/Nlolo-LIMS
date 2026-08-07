package tz.co.nlolo.lifeplatform.party.infrastructure;

import tz.co.nlolo.lifeplatform.party.api.DuplicateRegistrationNumberException;
import tz.co.nlolo.lifeplatform.party.api.PartyNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

/**
 * Maps party's own domain exceptions to RFC 7807 application/problem+json responses
 * (docs/04-api-contracts.md §2). Genuinely scoped to this module -- unlike the class this replaced
 * (also named PartyExceptionHandler, also living here), which additionally carried an unrestricted
 * catch-all @ExceptionHandler(Exception.class) that intercepted every standard Spring MVC exception
 * app-wide before Spring's own defaults ever ran. That cross-cutting concern now lives in
 * tz.co.nlolo.lifeplatform.GlobalExceptionHandler (the shared-kernel root package), extending
 * ResponseEntityExceptionHandler; it is intentionally NOT merged into this class because doing so
 * would make the root package depend back on the party module's exception types, which
 * ModularityTests.verifiesModularStructure (Spring Modulith's module-dependency-graph check,
 * docs/02-module-architecture.md §4) correctly rejects as a cycle -- other modules already depend on
 * the root package (TenantContext, DomainEventEnvelope), so the root package must never depend back
 * on them. Spring merges @ExceptionHandler resolution across every discovered @ControllerAdvice bean
 * by exception-type specificity, so this narrower, party-specific advice and the root generic one
 * co-exist correctly: a PartyNotFoundException is matched here (more specific) even though
 * GlobalExceptionHandler's catch-all could also technically apply.
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

    private static ProblemDetail problem(HttpStatus status, String detail, String errorCode) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("errorCode", errorCode);
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }
}
