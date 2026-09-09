package tz.co.nlolo.lifeplatform.communication.infrastructure;

import tz.co.nlolo.lifeplatform.communication.api.NotificationTemplateNotFoundException;
import tz.co.nlolo.lifeplatform.communication.api.TemplatePlaceholderException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * HIGHEST_PRECEDENCE for the reason PolicyExceptionHandler records: Spring resolves
 * @ExceptionHandler methods by first-matching-advice-bean-wins, not by specificity across beans,
 * so without this GlobalExceptionHandler's IllegalArgumentException catch-all could shadow the
 * 422 below depending on classpath-scan order.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CommunicationExceptionHandler {

    @ExceptionHandler(NotificationTemplateNotFoundException.class)
    public ProblemDetail handleNotFound(NotificationTemplateNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "TEMPLATE_NOT_FOUND");
    }

    @ExceptionHandler(TemplatePlaceholderException.class)
    public ProblemDetail handleInventedPlaceholder(TemplatePlaceholderException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), "TEMPLATE_PLACEHOLDER_UNKNOWN");
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String errorCode) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("errorCode", errorCode);
        // Required by openapi-common's shared error schema, and every other module's handler sets
        // it. Omitting it fails strict contract validation, which is how this was caught.
        problem.setProperty("traceId", java.util.UUID.randomUUID().toString());
        return problem;
    }
}
