package tz.co.nlolo.lifeplatform.unitlinked.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;

/**
 * The surrender charge (U2, spec Q6/Q7): the version's percent for the policy year of the sale's valuation date, on the
 * value sold. Taken on a full surrender, a partial withdrawal and a non-payment lapse with value -- never when the fund
 * ran out, and never on a death, a maturity or a free-look. A version published without bands charges nothing.
 */
@Component
class SurrenderCharges {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final ProductApi productApi;

    SurrenderCharges(ProductApi productApi) {
        this.productApi = productApi;
    }

    /** The percent for a sale valued on {@code valuationDate}; policy year 1 is the first year from commencement. */
    BigDecimal percentFor(PolicyView policy, LocalDate valuationDate) {
        LocalDate start = policy.commencementDate() != null ? policy.commencementDate() : policy.issueDate();
        int policyYear = Math.max(Period.between(start, valuationDate).getYears() + 1, 1);
        return productApi.resolveUnitLinkedPlan(policy.productVersionId()).options().surrenderChargePercent(policyYear);
    }

    /** {@code percent}% of {@code proceeds}, rounded once to the cent. */
    static BigDecimal charge(BigDecimal proceeds, BigDecimal percent) {
        if (proceeds.signum() <= 0 || percent.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return proceeds.multiply(percent).divide(HUNDRED, 2, RoundingMode.HALF_EVEN);
    }
}
