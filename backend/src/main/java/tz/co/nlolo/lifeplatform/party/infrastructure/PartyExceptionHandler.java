package tz.co.nlolo.lifeplatform.party.infrastructure;

import tz.co.nlolo.lifeplatform.party.api.DuplicateIdentityDocumentException;
import tz.co.nlolo.lifeplatform.party.api.DuplicateRegistrationNumberException;
import tz.co.nlolo.lifeplatform.party.api.PartyNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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
 * on them.
 *
 * <p><b>{@code @Order} is required, not optional.</b> An earlier version of this Javadoc claimed
 * Spring resolves {@code @ExceptionHandler} methods by exception-type specificity across ALL
 * {@code @ControllerAdvice} beans combined -- that is incorrect, and was a live bug (final-review
 * fix round 4, Critical): {@code ExceptionHandlerExceptionResolver.getExceptionHandlerMethod}
 * iterates advice beans in precedence order and returns the mapping from the FIRST advice bean
 * that has ANY matching handler; specificity is only resolved <i>within</i> a single bean, never
 * across beans. With both advices left at default (unordered) precedence, bean-registration order
 * decided the winner -- which on this classpath put {@code GlobalExceptionHandler} (root package)
 * ahead of this class ({@code party.infrastructure}), so its catch-all
 * {@code @ExceptionHandler(Exception.class)} silently intercepted {@link PartyNotFoundException}
 * and {@link DuplicateRegistrationNumberException} before this class ever ran, turning 404/409 into
 * 500. {@code @Order(HIGHEST_PRECEDENCE)} here plus {@code @Order(LOWEST_PRECEDENCE)} on
 * {@code GlobalExceptionHandler} makes this class always consulted first, deterministically rather
 * than incidentally, for the exceptions it specifically maps -- while still falling through to
 * {@code GlobalExceptionHandler}'s catch-all for everything else, since this class declares no
 * {@code Exception.class} handler of its own.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PartyExceptionHandler {

    @ExceptionHandler(PartyNotFoundException.class)
    public ProblemDetail handleNotFound(PartyNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "PARTY_NOT_FOUND");
    }

    @ExceptionHandler(DuplicateIdentityDocumentException.class)
    public ProblemDetail handleDuplicateIdentity(DuplicateIdentityDocumentException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), "DUPLICATE_IDENTITY_DOCUMENT");
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
