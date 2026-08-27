package tz.co.nlolo.lifeplatform.product.api;

/**
 * A premium could not be computed, and the message names the dimension that
 * failed.
 *
 * Every path that raises this is a case where a fallback would have produced a
 * plausible number instead: no base rate table on the version, no rate cell for
 * the applicant's (age, sex, smoker status), or no multiplier for a declared
 * occupation class or sum-assured band. A quote that silently substitutes a
 * neutral 1.0 for a missing factor, or a base rate for a missing band, prices a
 * real contract wrongly and nothing fails -- which is why this exists rather
 * than a default.
 *
 * Maps to 422: the request is well-formed, the product simply cannot price it.
 */
public class PremiumNotQuotableException extends RuntimeException {
    public PremiumNotQuotableException(String message) {
        super(message);
    }
}
