package tz.co.nlolo.lifeplatform.product.api;

/**
 * How a benefit's payable amount is derived from the policy's sum assured.
 *
 * <p>Replaces an unconstrained {@code VARCHAR(50)} that held, across 146 versions, three rows
 * carrying {@code SUM_ASSURED} and the typed prose {@code untill death}. The column was displayed
 * on one screen and read by no computation, which is why the typo survived.
 *
 * <p>{@code SUM_ASSURED} keeps its name deliberately: it is already correct in 105 Java call
 * sites, 12 JSON bodies and the real database rows, so renaming it would be churn for no reader.
 *
 * <p>There is no {@code SUM_ASSURED_PLUS_BONUS}. It appeared in one contract test and nowhere in
 * real data, and this platform has no bonus mechanism to compute it — no reversionary bonus and no
 * cash value. Admitting a method nothing can calculate would recreate the defect being removed:
 * a value that looks authoritative and resolves to nothing.
 */
public enum BenefitCalculationMethod {
    SUM_ASSURED,
    PERCENTAGE_OF_SUM_ASSURED,
    FLAT_AMOUNT
}
