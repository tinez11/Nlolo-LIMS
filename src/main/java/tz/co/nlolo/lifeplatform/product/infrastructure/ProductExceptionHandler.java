package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.api.DuplicateProductCodeException;
import tz.co.nlolo.lifeplatform.product.api.InvalidProductVersionException;
import tz.co.nlolo.lifeplatform.product.api.ProductNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

/**
 * @Order(HIGHEST_PRECEDENCE) is required -- Spring resolves @ExceptionHandler methods
 * by first-matching-ADVICE-BEAN-wins, not merged-by-specificity across beans. Without
 * this, GlobalExceptionHandler's catch-all could shadow these domain-specific mappings
 * depending on classpath-scan order (the exact regression M1's final review found and
 * fixed for PartyExceptionHandler -- do not repeat it here).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProductExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    public ProblemDetail handleNotFound(ProductNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "PRODUCT_NOT_FOUND");
    }

    @ExceptionHandler(InvalidProductVersionException.class)
    public ProblemDetail handleInvalidVersion(InvalidProductVersionException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), "INVALID_PRODUCT_VERSION");
    }

    @ExceptionHandler(DuplicateProductCodeException.class)
    public ProblemDetail handleDuplicateProductCode(DuplicateProductCodeException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), "DUPLICATE_PRODUCT_CODE");
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String errorCode) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("errorCode", errorCode);
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }
}
