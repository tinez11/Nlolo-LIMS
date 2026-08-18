package tz.co.nlolo.lifeplatform.reinsurance;

import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyType;
import tz.co.nlolo.lifeplatform.reinsurance.domain.Cession;
import tz.co.nlolo.lifeplatform.reinsurance.domain.RecoveryCalculator;
import tz.co.nlolo.lifeplatform.reinsurance.domain.ReinsuranceTreaty;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryCalculatorTest {

    private static Cession cession(String cededRisk, String currency) {
        return new Cession(UUID.randomUUID(), "POL-REC-01", UUID.randomUUID(),
            new BigDecimal(cededRisk), currency, new BigDecimal("1000.00"), currency);
    }

    private static ReinsuranceTreaty xolTreaty(String retention) {
        return new ReinsuranceTreaty(UUID.randomUUID(), "Test Re", TreatyType.XOL,
            new BigDecimal(retention), "TZS", null, LocalDate.now().minusYears(1), null, "actuary");
    }

    @Test
    void proportionalRecoveryFollowsTheCededFractionOfSumAssured() {
        // 1,500,000 of a 2,000,000 sum assured was ceded = 75%, so 75% of a 2,000,000 settlement.
        assertThat(RecoveryCalculator.proportional(cession("1500000.00", "TZS"),
            new BigDecimal("2000000.00"), new BigDecimal("2000000.00"), "TZS"))
            .contains(new BigDecimal("1500000.00"));
    }

    @Test
    void proportionalRecoveryRoundsHalfUpToTwoDecimalPlaces() {
        // 1/3 ceded of 1,000.00 sum assured, settled at 100.00 -> 33.333... -> 33.33
        assertThat(RecoveryCalculator.proportional(cession("333.33", "TZS"),
            new BigDecimal("1000.00"), new BigDecimal("100.00"), "TZS"))
            .contains(new BigDecimal("33.33"));
    }

    /** Final review (I3, safe half). {@code claims.Claim.approve} does not cap {@code
     * approvedAmount} at the policy's sum assured, so a settled amount CAN legitimately exceed the
     * sum assured this formula divides by -- without a cap, {@code settledAmount x cededAmount /
     * sumAssured} then returns MORE than what was actually ceded, which would book the reinsurer
     * for more risk than it agreed to cover under this cession. Here: 1,500,000 of a 2,000,000 sum
     * assured was ceded (75%), but the claim settled at 3,000,000 (150% of sum assured) -- an
     * uncapped formula would compute 4,500,000, strictly greater than the 1,500,000 actually ceded.
     * The recovery must be capped at cededAmount instead. */
    @Test
    void proportionalRecoveryIsCappedAtTheCededAmountWhenSettledExceedsSumAssured() {
        assertThat(RecoveryCalculator.proportional(cession("1500000.00", "TZS"),
            new BigDecimal("2000000.00"), new BigDecimal("3000000.00"), "TZS"))
            .as("the reinsurer can never owe more than what it agreed to cover")
            .contains(new BigDecimal("1500000.00"));
    }

    @Test
    void proportionalRecoveryIsEmptyOnACurrencyMismatch() {
        assertThat(RecoveryCalculator.proportional(cession("1500000.00", "TZS"),
            new BigDecimal("2000000.00"), new BigDecimal("2000000.00"), "USD")).isEmpty();
    }

    /** A cession's ceded fraction can be small enough relative to the settlement that the
     * reinsurer's share rounds to exactly 0.00 -- distinct from the currency-mismatch case above,
     * this exercises the zero-result guard itself (1.00 / 10,000,000.00 of a 1.00 settlement). Not
     * an error: "recovered nothing" is not a financial record, mirroring excessOfLoss's own
     * zero-boundary tests below. */
    @Test
    void proportionalRecoveryIsEmptyWhenTheShareRoundsToZero() {
        assertThat(RecoveryCalculator.proportional(cession("1.00", "TZS"),
            new BigDecimal("10000000.00"), new BigDecimal("1.00"), "TZS")).isEmpty();
    }

    @Test
    void excessOfLossRecoversOnlyTheAmountAboveRetention() {
        // A 2,000,000 loss against a 500,000 retention -> the reinsurer covers 1,500,000.
        assertThat(RecoveryCalculator.excessOfLoss(xolTreaty("500000.00"),
            new BigDecimal("2000000.00"), "TZS")).contains(new BigDecimal("1500000.00"));
    }

    /** The ordinary case for an XOL treaty: most losses fall entirely within retention and the
     * reinsurer owes nothing. Not an error. */
    @Test
    void excessOfLossRecoversNothingForALossAtOrBelowRetention() {
        assertThat(RecoveryCalculator.excessOfLoss(xolTreaty("500000.00"),
            new BigDecimal("500000.00"), "TZS")).isEmpty();
        assertThat(RecoveryCalculator.excessOfLoss(xolTreaty("500000.00"),
            new BigDecimal("100000.00"), "TZS")).isEmpty();
    }

    @Test
    void excessOfLossIsEmptyOnACurrencyMismatch() {
        assertThat(RecoveryCalculator.excessOfLoss(xolTreaty("500000.00"),
            new BigDecimal("2000000.00"), "USD")).isEmpty();
    }
}
