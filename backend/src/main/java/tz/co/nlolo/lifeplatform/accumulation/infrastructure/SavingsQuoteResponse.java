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
                                   MoneyResponse smallestDeposit, String refusal) {

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
            v.refusal());
    }

    /**
     * What to price.
     *
     * @param amount the deposit, or an account's opening balance
     * @param termMonths a deposit's term; null asks which terms are offered without pricing one
     * @param asOf the proposed commencement. Defaults to today in Dar es Salaam.
     */
    public record Body(@NotNull @Positive BigDecimal amount, String currency, Integer termMonths, LocalDate asOf) {}
}
