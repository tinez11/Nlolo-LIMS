package tz.co.nlolo.lifeplatform.document.infrastructure;

import tz.co.nlolo.lifeplatform.document.api.DocumentNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

/**
 * Genuinely scoped to {@code document}'s own domain exception types -- mirrors
 * {@code ClaimExceptionHandler}/{@code PolicyExceptionHandler}/{@code PartyExceptionHandler}'s
 * exact shape and their shared {@code @Order(HIGHEST_PRECEDENCE)} requirement (see
 * {@code PartyExceptionHandler}'s javadoc for the full reasoning: {@code @ExceptionHandler}
 * resolution is first-matching-ADVICE-BEAN-wins across {@code @ControllerAdvice} beans, not
 * merged-by-specificity, so an unordered advice here could be silently shadowed by
 * {@code GlobalExceptionHandler}'s {@code @Order(LOWEST_PRECEDENCE)} catch-all depending on
 * classpath-scan order).
 *
 * <p>Deliberately does NOT map {@code IllegalArgumentException} or {@code AccessDeniedException}:
 * {@code GlobalExceptionHandler} already owns both, and a second advice competing for the same
 * exception type is the shadowing bug {@code @Order} exists to prevent, not a fix for one.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DocumentExceptionHandler {

    @ExceptionHandler(DocumentNotFoundException.class)
    public ProblemDetail handleNotFound(DocumentNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "DOCUMENT_NOT_FOUND");
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String errorCode) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("errorCode", errorCode);
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }
}
