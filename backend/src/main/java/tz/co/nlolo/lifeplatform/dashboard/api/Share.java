package tz.co.nlolo.lifeplatform.dashboard.api;

/**
 * One slice of a whole -- a product line's collections, a claim type's payments, an outcome's cases (2026-10-23).
 *
 * <p>The percent is worked out HERE, against the whole of the same list, so the console never divides. Exactly one of
 * {@code amount} and {@code count} carries the figure the slice is measured in; the other may be null.
 *
 * @param key a code the console words (a product category, a claim type, an outcome)
 * @param percent of the whole, one decimal place, as a decimal string
 */
public record Share(String key, Money amount, Long count, String percent) {}
