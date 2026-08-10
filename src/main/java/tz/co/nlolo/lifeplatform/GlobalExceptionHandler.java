package tz.co.nlolo.lifeplatform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Application-global RFC 7807 (application/problem+json) mapping (docs/04-api-contracts.md §2)
 * for cross-cutting exceptions that aren't specific to any one module. Lives in the shared-kernel
 * root package (alongside TenantContext, DomainEventEnvelope) with NO basePackages/assignableTypes
 * restriction, so it applies to every controller in the app.
 *
 * <p>Deliberately does NOT reference any single business module's domain exception types (e.g.
 * party's PartyNotFoundException) -- that would make this root-package "shared kernel" type depend
 * back on a specific module, which Spring Modulith's module-dependency-graph verification
 * (ModularityTests.verifiesModularStructure / docs/02-module-architecture.md §4's DAG requirement)
 * correctly rejects as a cycle: business modules already depend on this root package (TenantContext,
 * DomainEventEnvelope), so this package must never depend back on them. Module-specific domain
 * exceptions (PartyNotFoundException, DuplicateRegistrationNumberException, ...) are mapped by a
 * small advice living in that module instead (see party/infrastructure/PartyExceptionHandler) --
 * genuinely scoped to that module's own exception types, unlike the old PartyExceptionHandler this
 * class replaces, which bundled an unrestricted catch-all in with them.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so that standard Spring MVC exceptions
 * (malformed JSON, wrong HTTP verb, unsupported media type, unresolvable path, bad
 * @RequestBody validation, etc.) get Spring's own correct status codes via that base class's
 * handleXxx overrides, instead of being swallowed by a bare {@code @ExceptionHandler(Exception.class)}
 * catch-all -- the {@code ExceptionHandlerExceptionResolver} that resolves {@code @ExceptionHandler}
 * methods runs before {@code DefaultHandlerExceptionResolver}, so a catch-all matching the broadest
 * possible {@code Exception} type would otherwise intercept these before Spring's defaults ever got
 * a chance, turning them all into generic 500s. This is verified by PartyContractTest's
 * malformed-JSON/unsupported-method/unsupported-media-type/path-type-mismatch tests, not just assumed.
 *
 * <p>Not implementing the full dereferenceable type-URI scheme (".../problems/...") here -- no
 * module has a semantically rich 422 case yet that would need one; a simplification, not a silent gap.
 *
 * <p><b>{@code @Order(LOWEST_PRECEDENCE)} is required, not decorative.</b> {@code @ExceptionHandler}
 * resolution across multiple {@code @ControllerAdvice} beans is NOT "most specific exception type
 * wins app-wide" -- {@code ExceptionHandlerExceptionResolver.getExceptionHandlerMethod} iterates
 * advice beans in precedence order and returns the first bean with ANY matching handler, full stop.
 * Left unordered, this class's unrestricted {@code @ExceptionHandler(Exception.class)} catch-all was
 * silently winning over module-local advices like {@code party.infrastructure.PartyExceptionHandler}
 * for exceptions this class has no business handling (PartyNotFoundException, 404, was coming back as
 * 500) purely because of classpath-scan bean-registration order (final-review fix round 4, Critical).
 * {@code LOWEST_PRECEDENCE} here, paired with an explicit higher precedence on every module-local
 * advice, makes this class the deliberate last resort it was always meant to be.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleValidation(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, ex.getMessage(), "VALIDATION_ERROR");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, ex.getMessage(), "FORBIDDEN");
    }

    /**
     * Optimistic-lock loss -> 409, the status the API contract has always promised for it:
     * api/openapi/openapi-common.yaml's shared {@code Conflict} response is described,
     * verbatim, as "Optimistic-locking conflict or state-machine violation", and every path
     * that can lose a version race declares it (e.g. openapi-policyloan.yaml's
     * {@code POST /loans/{loanId}/repayments}, which declares 403/404/409 and does NOT declare
     * 500). Until M3's final review this mapping did not exist anywhere in the application, so
     * the loser of an ordinary double-submit fell through to {@link #handleGenericException}
     * and got a bare 500 {@code INTERNAL_ERROR} plus an "Unhandled exception" stack trace --
     * an undeclared status for a completely expected outcome.
     *
     * <p>Reachable today, not theoretical: two concurrent repayments against the same
     * {@code DISBURSED} loan both pass {@code PolicyLoanApiImpl.recordRepayment}'s eligibility
     * gate, both dirty-check {@code DISBURSED -> REPAYING}, and both issue
     * {@code UPDATE ... WHERE version = N}; {@code PolicyLoan} carries {@code @Version} with no
     * pessimistic lock anywhere in that path. The same shape exists on {@code Policy},
     * {@code UnderwritingCase}, {@code Party} and {@code ProductDefinition} -- i.e. every
     * aggregate on the platform -- which is why this lives in the root advice rather than in
     * any one module's.
     *
     * <p>This class is {@code @Order(LOWEST_PRECEDENCE)} and every module advice is
     * {@code @Order(HIGHEST_PRECEDENCE)}, and resolution is first-matching-ADVICE-BEAN-wins
     * (see this class's Javadoc). That is safe here: {@code OptimisticLockingFailureException}
     * is Spring's {@code org.springframework.dao} type, and all five module advices
     * (party/product/underwriting/policy/policyloan) declare handlers only for their own
     * module-owned domain exceptions -- none for this type or any supertype of it -- so
     * resolution reaches this bean. Within this bean the most specific handler wins, so this
     * beats the {@code Exception.class} catch-all below. Both halves are asserted, not
     * assumed, by OptimisticLockingConflictContractTest.
     *
     * <p>Deliberately NOT logged at error level: a lost version race is an expected outcome of
     * concurrent access with a correct client remedy (retry), not an application fault.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLockingFailure(OptimisticLockingFailureException ex) {
        String traceId = UUID.randomUUID().toString();
        log.warn("Optimistic-locking conflict, traceId={}: {}", traceId, ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
            "The resource was modified concurrently by another request -- re-read it and retry.");
        problem.setProperty("errorCode", "CONCURRENT_MODIFICATION");
        problem.setProperty("traceId", traceId);
        return problem;
    }

    /**
     * Overrides the base class's hook (rather than replacing @Valid @RequestBody handling
     * entirely) to attach the same errorCode/traceId extensions docs/04-api-contracts.md §2
     * requires on every ProblemDetails, plus the per-field errors[] array
     * api/openapi/openapi-common.yaml's ProblemDetails schema declares for 400 responses.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setProperty("errorCode", "VALIDATION_ERROR");
        problem.setProperty("traceId", UUID.randomUUID().toString());
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
            .map(GlobalExceptionHandler::toFieldError)
            .toList();
        problem.setProperty("errors", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    /**
     * Overrides the base class's hook for Spring Framework 6.1's newer, consolidated
     * method-validation path ({@link HandlerMethodValidationException}) -- a second, separate
     * validation mechanism from the classic {@code WebDataBinder}-based path
     * {@link #handleMethodArgumentNotValid} above covers. Framework 6.1 activates this path
     * whenever it finds a constraint annotation reachable anywhere in a handler method's
     * parameter list, including a type-use {@code @Valid} on a generic type argument (e.g.
     * {@code List<@Valid Foo>}) or a constraint directly on a bare-collection
     * {@code @RequestBody} parameter -- both annotation placements route here identically, since
     * Jakarta Bean Validation's {@code ExecutableValidator} natively cascades into container
     * elements regardless of exactly where {@code @Valid} sits (discovered empirically while
     * verifying the Task 4 review's C1 fix on {@code PolicyController.replaceBeneficiaries}: the
     * review's predicted 500-via-uncaught-NPE did not reproduce on this codebase's actual Spring
     * Boot 3.3.5 / Framework 6.1 version, because this path already caught the malformed input --
     * just via a generic, undifferentiated shape). Without this override, that generic shape
     * (bare {@code errorCode: BAD_REQUEST}, no per-field detail) fell through to
     * {@link #handleExceptionInternal}'s funnel, inconsistent with
     * {@link #handleMethodArgumentNotValid}'s {@code VALIDATION_ERROR} + {@code errors[]} shape
     * used everywhere else on this platform. Mirrors that same shape here so the two validation
     * paths are indistinguishable to API clients.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setProperty("errorCode", "VALIDATION_ERROR");
        problem.setProperty("traceId", UUID.randomUUID().toString());
        List<Map<String, String>> errors = new ArrayList<>();
        for (ParameterValidationResult result : ex.getAllValidationResults()) {
            String field = result.getMethodParameter().getParameterName();
            if (result.getContainerIndex() != null) {
                field = field + "[" + result.getContainerIndex() + "]";
            }
            if (result instanceof ParameterErrors parameterErrors) {
                for (FieldError fieldError : parameterErrors.getFieldErrors()) {
                    errors.add(fieldErrorEntry(field + "." + fieldError.getField(), fieldError.getDefaultMessage()));
                }
            } else {
                for (MessageSourceResolvable resolvable : result.getResolvableErrors()) {
                    errors.add(fieldErrorEntry(field, resolvable.getDefaultMessage()));
                }
            }
        }
        problem.setProperty("errors", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    /**
     * Central hook every base-class {@code handleXxx} override (malformed JSON, wrong HTTP verb,
     * unsupported media type, unresolvable path, etc.) funnels its response through. Without this
     * override, only {@link #handleMethodArgumentNotValid} attached errorCode/traceId -- every other
     * standard MVC error (400/404/405/415/...) went out as a bare {@link ProblemDetail} with neither,
     * even though api/openapi/openapi-common.yaml's ProblemDetails schema marks traceId required on
     * every error response (docs/04-api-contracts.md §2). Stamping it here, once, covers all of them
     * uniformly instead of requiring an override per exception type.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problemDetail) {
            if (problemDetail.getProperties() == null || !problemDetail.getProperties().containsKey("traceId")) {
                problemDetail.setProperty("traceId", UUID.randomUUID().toString());
            }
            if (problemDetail.getProperties() == null || !problemDetail.getProperties().containsKey("errorCode")) {
                problemDetail.setProperty("errorCode", errorCodeFor(statusCode));
            }
        }
        return response;
    }

    private static String errorCodeFor(HttpStatusCode statusCode) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        return status != null ? status.name() : "ERROR_" + statusCode.value();
    }

    private static Map<String, String> toFieldError(FieldError fieldError) {
        return fieldErrorEntry(fieldError.getField(), fieldError.getDefaultMessage());
    }

    private static Map<String, String> fieldErrorEntry(String field, String message) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("field", field);
        entry.put("message", message);
        return entry;
    }

    /**
     * Genuinely unexpected errors only -- every exception with a known cause and status is
     * handled by a more-specific @ExceptionHandler above (here or in a module-local advice) or by
     * the base class's own handleXxx overrides. Logs the exception itself (not just its message)
     * so the stack trace is captured, using the SAME traceId emitted in the response so a
     * customer-facing error and its log line are one lookup apart
     * (docs/07-infrastructure-architecture.md §5).
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGenericException(Exception ex) {
        String traceId = UUID.randomUUID().toString();
        log.error("Unhandled exception, traceId={}", traceId, ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        problem.setProperty("errorCode", "INTERNAL_ERROR");
        problem.setProperty("traceId", traceId);
        return problem;
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String errorCode) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("errorCode", errorCode);
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }
}
