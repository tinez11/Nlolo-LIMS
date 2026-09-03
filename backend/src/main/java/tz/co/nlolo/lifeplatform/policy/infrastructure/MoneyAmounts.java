package tz.co.nlolo.lifeplatform.policy.infrastructure;

/**
 * The pattern a bare decimal amount must match on the wire.
 *
 * <p>{@link MoneyDto} carries the shared {@code Money} regex plus a
 * {@code @DecimalMin("0.01")} floor. Where a scheme already declares one currency for
 * everything on it, its amounts travel as bare strings instead -- a currency code per
 * field could only agree with the scheme's or contradict it -- and those fields need the
 * same guarantee in one expression: two decimal places at most, and greater than zero.
 *
 * <p>Unsigned by construction. MoneyDto's regex keeps a leading {@code -?} because it is
 * shared with signed ledger contexts and narrows with {@code @DecimalMin}; nothing here
 * is ever a ledger amount, so the sign is simply not admitted. Zero is excluded by the
 * alternation rather than by a second annotation, because a zero benefit and a zero free
 * cover limit are both meaningful-looking and both wrong.
 */
final class MoneyAmounts {

    /** Positive, at most two decimal places: {@code 1}, {@code 0.01}, {@code 5000000.00}. */
    static final String POSITIVE_AMOUNT = "^(?!0+(\\.0{1,2})?$)\\d+(\\.\\d{1,2})?$";

    private MoneyAmounts() {}
}
