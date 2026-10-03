package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * One annuity form a version offers (product step 5, Q2): a combination of four independent
 * settings -- guaranteed period, joint life with a survivor percentage, escalation, capital
 * protection -- with its own rate grid. No combination is special-cased anywhere; "life only" is
 * (0, single life, 0%, unprotected).
 *
 * @param survivorPercent   what a joint form pays on after the first death; null on a single-life form
 * @param escalationPercent fixed annual increase, applied on each anniversary of the first payment
 */
public record AnnuityForm(String formCode, int guaranteeYears, boolean joint, BigDecimal survivorPercent,
                          BigDecimal escalationPercent, boolean capitalProtected, AnnuityRateBasis rateBasis,
                          List<AnnuityRateRow> rates) {

    public AnnuityForm {
        rates = rates != null ? List.copyOf(rates) : List.of();
    }
}
