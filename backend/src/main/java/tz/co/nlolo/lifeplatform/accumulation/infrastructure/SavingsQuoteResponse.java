package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import tz.co.nlolo.lifeplatform.accumulation.api.SavingsQuoteView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A savings product priced before any policy exists, for the console's quotation panel.
 *
 * <p>Money as a decimal string and a currency, as everything else on this controller does. A rate is a percent and
 * not money, so it keeps its own plain string -- stripped of trailing zeros, since the column is NUMERIC and the
 * same 6.5% would otherwise read "6.5" from one path and "6.5000" from another.
 */
public record SavingsQuoteResponse(String kind, MoneyResponse amount, String ratePercent,
                                   List<Integer> offeredTermMonths, Integer termMonths, MoneyResponse interest,
                                   MoneyResponse maturityValue, String maturityDate, MoneyResponse minimumBalance,
                                   MoneyResponse smallestDeposit, String refusal, TermAccount termAccount) {

    /** A term account's projection (2026-10-21); null on a deposit or an ordinary account. */
    public record TermAccount(String frequency, int contributions, MoneyResponse paidIn, MoneyResponse charges,
                              MoneyResponse targetAmount, String minRatePercent, String maxRatePercent) {
        static TermAccount from(SavingsQuoteView.TermAccountQuote t, String currency) {
            return t == null ? null : new TermAccount(t.frequency(), t.contributions(),
                t.paidIn() == null ? null : MoneyResponse.of(t.paidIn(), currency),
                t.charges() == null ? null : MoneyResponse.of(t.charges(), currency),
                t.targetAmount() == null ? null : MoneyResponse.of(t.targetAmount(), currency),
                t.minRatePercent().stripTrailingZeros().toPlainString(), t.maxRatePercent().stripTrailingZeros().toPlainString());
        }
    }

    static SavingsQuoteResponse from(SavingsQuoteView v, String currency) {
        return new SavingsQuoteResponse(
            v.kind().name(),
            v.amount() == null ? null : MoneyResponse.of(v.amount(), currency),
            v.ratePercent() == null ? null : v.ratePercent().stripTrailingZeros().toPlainString(),
            v.offeredTermMonths(),
            v.termMonths(),
            v.interest() == null ? null : MoneyResponse.of(v.interest(), currency),
            v.maturityValue() == null ? null : MoneyResponse.of(v.maturityValue(), currency),
            v.maturityDate() == null ? null : v.maturityDate().toString(),
            v.minimumBalance() == null ? null : MoneyResponse.of(v.minimumBalance(), currency),
            v.smallestDeposit() == null ? null : MoneyResponse.of(v.smallestDeposit(), currency),
            v.refusal(),
            TermAccount.from(v.termAccount(), currency));
    }

    /**
     * What to price.
     *
     * @param amount the deposit, or an account's opening balance, or a term account's contribution per payment.
     *     Required except on a term account quoted for a {@code targetAmount}.
     * @param termMonths a deposit's or a term account's term; null asks which terms are offered without pricing one
     * @param asOf the proposed commencement. Defaults to today in Dar es Salaam.
     * @param ratePercent a term account's rate, inside its band
     * @param frequency how often a term account's contribution is paid
     * @param targetAmount what the customer wants a term account to pay at maturity; the contribution is solved for it
     */
    public record Body(@Positive BigDecimal amount, String currency, Integer termMonths, LocalDate asOf,
                       BigDecimal ratePercent, String frequency, @Positive BigDecimal targetAmount) {}
}
