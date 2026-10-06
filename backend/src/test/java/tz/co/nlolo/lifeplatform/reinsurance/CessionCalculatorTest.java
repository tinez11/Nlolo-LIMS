package tz.co.nlolo.lifeplatform.reinsurance;

import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyType;
import tz.co.nlolo.lifeplatform.reinsurance.domain.CessionCalculator;
import tz.co.nlolo.lifeplatform.reinsurance.domain.ReinsuranceTreaty;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CessionCalculatorTest {

    private static ReinsuranceTreaty treaty(TreatyType type, String retention, String percent) {
        return new ReinsuranceTreaty(UUID.randomUUID(), "Test Re", type,
            new BigDecimal(retention), "TZS",
            percent == null ? null : new BigDecimal(percent),
            LocalDate.now().minusYears(1), null, "actuary");
    }

    @Test
    void quotaShareCedesTheSamePercentOfRiskAndPremium() {
        // 30% of a 2,000,000 sum assured and of a 100,000 premium.
        Optional<CessionCalculator.CededAmounts> result = CessionCalculator.calculate(
            treaty(TreatyType.QUOTA_SHARE, "0", "30.00"),
            new BigDecimal("2000000.00"), "TZS", new BigDecimal("100000.00"), "TZS");

        assertThat(result).isPresent();
        assertThat(result.get().cededRisk()).isEqualByComparingTo("600000.00");
        assertThat(result.get().cededPremium()).isEqualByComparingTo("30000.00");
        assertThat(result.get().riskCurrency()).isEqualTo("TZS");
        assertThat(result.get().premiumCurrency()).isEqualTo("TZS");
        // IFRS 17 I3c: each monthly bordereau charges the treaty's percent of the policy's premium.
        assertThat(result.get().premiumShare()).isEqualByComparingTo("0.30");
    }

    @Test
    void surplusCedesOnlyTheExcessOverRetentionAndPremiumInProportion() {
        // Retention 500,000 against a 2,000,000 sum assured -> cede 1,500,000, i.e. 75% of the
        // risk, so 75% of the 100,000 premium follows it.
        Optional<CessionCalculator.CededAmounts> result = CessionCalculator.calculate(
            treaty(TreatyType.SURPLUS, "500000.00", null),
            new BigDecimal("2000000.00"), "TZS", new BigDecimal("100000.00"), "TZS");

        assertThat(result).isPresent();
        assertThat(result.get().cededRisk()).isEqualByComparingTo("1500000.00");
        assertThat(result.get().cededPremium()).isEqualByComparingTo("75000.00");
        // IFRS 17 I3c: the share of the premium that travels with the ceded risk.
        assertThat(result.get().premiumShare()).isEqualByComparingTo("0.75");
    }

    @Test
    void surplusCedesNothingWhenTheSumAssuredIsAtOrBelowRetention() {
        // The ordinary case for a small policy under a surplus treaty -- not an error.
        assertThat(CessionCalculator.calculate(treaty(TreatyType.SURPLUS, "2000000.00", null),
            new BigDecimal("2000000.00"), "TZS", new BigDecimal("100000.00"), "TZS")).isEmpty();
        assertThat(CessionCalculator.calculate(treaty(TreatyType.SURPLUS, "2000000.00", null),
            new BigDecimal("500000.00"), "TZS", new BigDecimal("100000.00"), "TZS")).isEmpty();
    }

    /** XOL is a CLAIM-level treaty: it cedes nothing at issuance and instead recovers the excess
     * of a loss over retention (RecoveryCalculator, Task 5). Giving it an issuance interpretation
     * would encode an actuarially wrong model. */
    @Test
    void excessOfLossCedesNothingAtIssuance() {
        assertThat(CessionCalculator.calculate(treaty(TreatyType.XOL, "1000000.00", null),
            new BigDecimal("2000000.00"), "TZS", new BigDecimal("100000.00"), "TZS")).isEmpty();
    }

    /** No FX table exists anywhere on this platform, so converting would mean inventing a rate.
     * Ceding nothing is the honest outcome. */
    @Test
    void aCurrencyMismatchBetweenTreatyAndPolicyCedesNothing() {
        ReinsuranceTreaty tzsTreaty = treaty(TreatyType.QUOTA_SHARE, "0", "30.00");
        assertThat(CessionCalculator.calculate(tzsTreaty,
            new BigDecimal("2000000.00"), "USD", new BigDecimal("100000.00"), "USD")).isEmpty();
    }

    /** A premium in a different currency from the sum assured is legitimate (PolicyIssued carries
     * the two independently), and only the RISK currency must match the treaty. The ceded premium
     * keeps the policy's own premium currency. */
    @Test
    void thePremiumKeepsItsOwnCurrency() {
        Optional<CessionCalculator.CededAmounts> result = CessionCalculator.calculate(
            treaty(TreatyType.QUOTA_SHARE, "0", "50.00"),
            new BigDecimal("1000000.00"), "TZS", new BigDecimal("400.00"), "USD");

        assertThat(result).isPresent();
        assertThat(result.get().riskCurrency()).isEqualTo("TZS");
        assertThat(result.get().premiumCurrency()).isEqualTo("USD");
        assertThat(result.get().cededPremium()).isEqualByComparingTo("200.00");
    }

    @Test
    void amountsAreRoundedHalfUpToTwoDecimalPlaces() {
        // 33.33% of 1,000.00 = 333.30; of a 10.00 premium = 3.333 -> 3.33
        Optional<CessionCalculator.CededAmounts> result = CessionCalculator.calculate(
            treaty(TreatyType.QUOTA_SHARE, "0", "33.33"),
            new BigDecimal("1000.00"), "TZS", new BigDecimal("10.00"), "TZS");

        assertThat(result).isPresent();
        assertThat(result.get().cededRisk()).isEqualByComparingTo("333.30");
        assertThat(result.get().cededPremium()).isEqualByComparingTo("3.33");
        assertThat(result.get().cededPremium().scale()).isEqualTo(2);
    }

    /** cededRisk is meaningfully positive (1,000.00) while cededPremium rounds to exactly 0.00
     * (0.01% of 40.00 = 0.004 -> 0.00 HALF_UP). V2's cession_ceded_premium_positive CHECK forbids
     * persisting a zero premium row, so the calculator must substitute null rather than
     * BigDecimal.ZERO here -- this test guards that substitution against regressing to a literal
     * zero. */
    @Test
    void cededPremiumIsNullRatherThanZeroWhenItRoundsToZeroButRiskIsStillCeded() {
        Optional<CessionCalculator.CededAmounts> result = CessionCalculator.calculate(
            treaty(TreatyType.QUOTA_SHARE, "0", "0.01"),
            new BigDecimal("10000000.00"), "TZS", new BigDecimal("40.00"), "TZS");

        assertThat(result).isPresent();
        assertThat(result.get().cededRisk()).isEqualByComparingTo("1000.00");
        assertThat(result.get().cededPremium()).isNull();
        assertThat(result.get().premiumCurrency()).isNull();
    }
}
