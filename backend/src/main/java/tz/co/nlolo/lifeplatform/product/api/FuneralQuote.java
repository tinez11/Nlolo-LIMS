package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * A family priced on one plan: a line per life, the yearly total, and the instalment -- the total, loaded
 * for the frequency and divided ONCE, rounded once. A family of five rounded five times would disagree
 * with its own first invoice.
 *
 * @param mainMemberBenefit the plan's benefit for the main member: the policy's sum assured (plan R3)
 */
public record FuneralQuote(String planCode, PremiumFrequency frequency, List<FuneralQuoteLine> lines,
                           BigDecimal totalYearlyPremium, BigDecimal instalment, BigDecimal mainMemberBenefit) {

    public FuneralQuote {
        lines = lines != null ? List.copyOf(lines) : List.of();
    }
}
