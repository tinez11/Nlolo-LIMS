package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.api.AnnuityPricingRefusedException;
import tz.co.nlolo.lifeplatform.product.api.DuplicateProductCodeException;
import tz.co.nlolo.lifeplatform.product.api.InvalidProductVersionException;
import tz.co.nlolo.lifeplatform.product.api.NoActiveProductVersionException;
import tz.co.nlolo.lifeplatform.product.api.PremiumNotQuotableException;
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

    /**
     * 404, like a missing product, but with its OWN errorCode — which is the entire point.
     *
     * <p>The status has to stay 404: `premium-quote` already documents 404 for this same
     * condition, and the resource asked for (the version in force on a date) genuinely is
     * not there. What was wrong was that the code said {@code PRODUCT_NOT_FOUND}, so a
     * client could not tell "no such product" from "its version starts tomorrow" and the
     * console rendered the generic 404 copy — which on this platform deliberately hedges
     * about whether the caller's role is at fault, because a 404 may be a disguised
     * denial. Here it is not: the product exists and the caller can see it. A distinct
     * code is what lets the console say the true thing instead of the safe thing.
     */
    @ExceptionHandler(NoActiveProductVersionException.class)
    public ProblemDetail handleNoActiveVersion(NoActiveProductVersionException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "NO_ACTIVE_PRODUCT_VERSION");
    }

    @ExceptionHandler(InvalidProductVersionException.class)
    public ProblemDetail handleInvalidVersion(InvalidProductVersionException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), "INVALID_PRODUCT_VERSION");
    }

    /**
     * 422, not 400: the request is well-formed, the product simply cannot price it.
     * The message names the dimension that failed, because every one of these is a
     * case where a fallback would have produced a plausible premium instead.
     */
    @ExceptionHandler(PremiumNotQuotableException.class)
    public ProblemDetail handleNotQuotable(PremiumNotQuotableException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), "PREMIUM_NOT_QUOTABLE");
    }

    /**
     * A purchase the version cannot price (product step 5), in the pricer's words. Global like the
     * rest of this advice, so the annuity module's quote endpoint answers it the same way.
     */
    @ExceptionHandler(AnnuityPricingRefusedException.class)
    public ProblemDetail handleAnnuityNotPriceable(AnnuityPricingRefusedException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), "ANNUITY_NOT_PRICEABLE");
    }

    @ExceptionHandler(NotAnAnnuityException.class)
    public ProblemDetail handleNotAnAnnuity(NotAnAnnuityException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "NOT_AN_ANNUITY");
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
