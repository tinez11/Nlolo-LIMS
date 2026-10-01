package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;

/**
 * The charges for a run of policy years (decision Q4). {@code toPolicyYear} null means "and every
 * year after", which only the last row may be.
 *
 * @param contributionAllocationPercent held back from each regular contribution and top-up
 * @param transferAllocationPercent held back from a transfer in -- usually 0, because no commission
 *     is paid on transferred money
 * @param monthlyPolicyFee deducted at each month-end run
 */
public record AccumulationChargeRow(int fromPolicyYear, Integer toPolicyYear,
                                    BigDecimal contributionAllocationPercent,
                                    BigDecimal transferAllocationPercent,
                                    BigDecimal monthlyPolicyFee) {

    public boolean covers(int policyYear) {
        return policyYear >= fromPolicyYear && (toPolicyYear == null || policyYear <= toPolicyYear);
    }
}
