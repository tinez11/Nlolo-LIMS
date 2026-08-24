package tz.co.nlolo.lifeplatform.finaccounting.api;

/**
 * Matches {@code gl_posting.direction}'s CHECK (finaccounting/V2 section 7).
 *
 * <p>The direction carries the SIGN of a posting: {@code gl_posting.amount} is always a positive
 * magnitude (enforced by {@code gl_posting_amount_positive}), and a reversal is a new journal
 * entry with the two directions swapped -- never a negative amount on the original.
 */
public enum PostingDirection { DR, CR }
