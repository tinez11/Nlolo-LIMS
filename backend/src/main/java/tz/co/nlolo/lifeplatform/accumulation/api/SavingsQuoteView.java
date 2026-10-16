package tz.co.nlolo.lifeplatform.accumulation.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What a savings product would pay, quoted BEFORE any policy exists (2026-10-16).
 *
 * <p><b>Why this exists.</b> The console's quotation panel could only ever ask
 * {@code POST /products/{id}/premium-quote}, which prices a sum assured off a base rate table. A savings account and
 * a fixed-term deposit have no sum assured and no premium rate, so every one of them answered 422 "carries no base
 * rate table, so it cannot be priced" -- a red refusal on a product that is working exactly as designed. The figure
 * a customer actually asks for (what will my deposit be worth at maturity, what rate am I earning) existed nowhere
 * before the policy was issued.
 *
 * <p><b>It is the same arithmetic the policy will do.</b> A deposit's rate is read from the version's own grid
 * through {@code DepositPlan.rateFor} and its interest from {@code DepositInterest.full} -- the functions the running
 * deposit uses -- and an account's rate from the same {@code RateSchedule} the valuer credits on. Nothing here
 * re-derives a rate, which is how a quote stays honest when the grid changes.
 *
 * <p><b>A quote creates nothing:</b> no account, no deposit period, no interest, no ledger entry.
 *
 * @param kind which of the two this version is -- they answer different questions and fill different rows
 * @param amount the deposit or opening balance the quote was asked about
 * @param ratePercent for a DEPOSIT, the rate FOR THE TERM from the band the amount falls in (not a yearly rate);
 *     for an ACCOUNT, the effective annual rate today -- max(latest approved declaration, the version's guarantee).
 *     Null where the amount or term is not one the grid prices, which {@code refusal} then names.
 * @param offeredTermMonths every term the deposit grid offers, so the console can say what IS on sale rather than
 *     only that this term is not. Empty for an account, which has no term.
 * @param termMonths the term quoted, echoed back
 * @param interest the term's interest, once. Null when no rate applied.
 * @param maturityValue amount + interest. Null when no rate applied.
 * @param maturityDate the day the term ends, counted from the proposed commencement
 * @param minimumBalance for an ACCOUNT, the least a partial withdrawal may leave behind; null where none is set
 * @param smallestDeposit for a DEPOSIT, the lowest band start -- the least this product takes
 * @param refusal why no rate applied, in a sentence a staff member can act on. Null when the quote priced.
 */
public record SavingsQuoteView(Kind kind, BigDecimal amount, BigDecimal ratePercent, List<Integer> offeredTermMonths,
                               Integer termMonths, BigDecimal interest, BigDecimal maturityValue,
                               LocalDate maturityDate, BigDecimal minimumBalance, BigDecimal smallestDeposit,
                               String refusal) {

    /** A fixed-term deposit priced from a grid, or an interest-bearing account priced from a declared rate. */
    public enum Kind { DEPOSIT, ACCOUNT }

    public SavingsQuoteView {
        offeredTermMonths = offeredTermMonths != null ? List.copyOf(offeredTermMonths) : List.of();
    }
}
