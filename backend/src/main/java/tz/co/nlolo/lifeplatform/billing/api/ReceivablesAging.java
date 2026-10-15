package tz.co.nlolo.lifeplatform.billing.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * What is owed in one currency, and how old it is (2026-10-15).
 *
 * <p>Per currency, never added across: a total of TZS and USD is a number in no currency.
 *
 * <p>Every bucket is present even when empty. Unlike a monthly series, where an absent month means "no data", an
 * empty bucket here means "nothing is this overdue" -- which is the answer the reader came for, not a gap.
 *
 * <p>Outstanding is the invoice amount MINUS what has been paid: a part-paid invoice owes the remainder, and showing
 * its full value would overstate the book.
 */
public record ReceivablesAging(String currency, List<Bucket> buckets) {

    /** One age band. {@code outstanding} is scaled to 2dp, including the zero of an empty bucket. */
    public record Bucket(String name, long cases, BigDecimal outstanding) {}

    /** The five bands, in the order a statement reads them: youngest first. */
    public static final List<String> BUCKETS = List.of("NOT_DUE", "D1_30", "D31_60", "D61_90", "OVER_90");
}
