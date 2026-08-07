package tz.co.nlolo.lifeplatform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

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
 */
@RestControllerAdvice
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

    private static Map<String, String> toFieldError(FieldError fieldError) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("field", fieldError.getField());
        entry.put("message", fieldError.getDefaultMessage());
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
