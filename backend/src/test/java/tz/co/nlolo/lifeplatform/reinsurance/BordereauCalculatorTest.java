package tz.co.nlolo.lifeplatform.reinsurance;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.reinsurance.domain.BordereauCalculator;
import tz.co.nlolo.lifeplatform.reinsurance.domain.BordereauCalculator.CededPolicy;
import tz.co.nlolo.lifeplatform.reinsurance.domain.BordereauCalculator.Cover;
import tz.co.nlolo.lifeplatform.reinsurance.domain.BordereauCalculator.LineType;
import tz.co.nlolo.lifeplatform.reinsurance.domain.BordereauCalculator.Recovery;
import tz.co.nlolo.lifeplatform.reinsurance.domain.BordereauCalculator.Result;
import tz.co.nlolo.lifeplatform.reinsurance.domain.BordereauCalculator.Treaty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** IFRS 17 I3c: one treaty's month on the bordereau -- the user's answers Q1, Q2, Q3 and Q5 as arithmetic. */
class BordereauCalculatorTest {

    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);
    private static final Treaty QUOTA_20 = new Treaty("TZS", new BigDecimal("20"), null);

    private static CededPolicy policy(String number, String frequency, String premium, LocalDate premiumsEnd,
                                      Cover... cover) {
        return new CededPolicy(number, new BigDecimal("0.5"), new BigDecimal(premium), "TZS", frequency, premiumsEnd,
            List.of(cover));
    }

    private static Cover open(String from) {
        return new Cover(LocalDate.parse(from), null);
    }

    private static Cover closed(String from, String to) {
        return new Cover(LocalDate.parse(from), LocalDate.parse(to));
    }

    @Test
    void aMonthlyPolicyCedesItsShareOfOneInstalmentAndTheCommissionReducesIt() {
        Result r = BordereauCalculator.calculate(OCTOBER, QUOTA_20,
            List.of(policy("POL-1", "MONTHLY", "100000.00", null, open("2026-09-15"))), List.of());

        assertThat(r.premium()).isEqualByComparingTo("50000.00");
        assertThat(r.commission()).isEqualByComparingTo("10000.00");
        assertThat(r.policyCount()).isEqualTo(1);
    }

    @Test
    void quarterlyAndAnnualPremiumsAreTurnedIntoAMonthlyAmount() {
        Result r = BordereauCalculator.calculate(OCTOBER, new Treaty("TZS", BigDecimal.ZERO, null), List.of(
            policy("POL-Q", "QUARTERLY", "300000.00", null, open("2026-01-01")),
            policy("POL-A", "ANNUALLY", "1200000.00", null, open("2026-01-01"))), List.of());

        // 300,000 x 4 / 12 x 0.5 = 50,000; 1,200,000 x 1 / 12 x 0.5 = 50,000.
        assertThat(r.lines()).extracting(BordereauCalculator.Line::premium)
            .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
            .containsExactly(new BigDecimal("50000.00"), new BigDecimal("50000.00"));
        assertThat(r.commission()).isEqualByComparingTo("0");
    }

    @Test
    void aFullMonthIsChargedForCoverThatStartedOrEndedInsideIt() {
        Result r = BordereauCalculator.calculate(OCTOBER, QUOTA_20, List.of(
            policy("POL-START", "MONTHLY", "100000.00", null, open("2026-10-31")),
            policy("POL-END", "MONTHLY", "100000.00", null, closed("2026-01-01", "2026-10-01"))), List.of());

        assertThat(r.policyCount()).isEqualTo(2);
        assertThat(r.premium()).isEqualByComparingTo("100000.00");
    }

    @Test
    void coverThatEndedBeforeTheMonthOrStartsAfterItIsNotCharged() {
        Result r = BordereauCalculator.calculate(OCTOBER, QUOTA_20, List.of(
            policy("POL-LAPSED", "MONTHLY", "100000.00", null, closed("2026-01-01", "2026-09-30")),
            policy("POL-LATER", "MONTHLY", "100000.00", null, open("2026-11-01"))), List.of());

        assertThat(r.lines()).isEmpty();
        assertThat(r.premium()).isEqualByComparingTo("0");
    }

    @Test
    void aReinstatedPolicyIsChargedAgainOnlyFromItsNewPeriod() {
        CededPolicy reinstated = policy("POL-R", "MONTHLY", "100000.00", null,
            closed("2026-01-01", "2026-08-10"), open("2026-11-05"));

        assertThat(BordereauCalculator.calculate(OCTOBER, QUOTA_20, List.of(reinstated), List.of()).lines()).isEmpty();
        assertThat(BordereauCalculator.calculate(YearMonth.of(2026, 11), QUOTA_20, List.of(reinstated), List.of())
            .premium()).isEqualByComparingTo("50000.00");
    }

    @Test
    void premiumsThatStoppedStopTheChargeFromTheFollowingMonth() {
        CededPolicy paidUp = policy("POL-PU", "MONTHLY", "100000.00", LocalDate.parse("2026-10-12"), open("2026-01-01"));

        assertThat(BordereauCalculator.calculate(OCTOBER, QUOTA_20, List.of(paidUp), List.of()).premium())
            .as("premiums stopped during October: October is still charged")
            .isEqualByComparingTo("50000.00");
        assertThat(BordereauCalculator.calculate(YearMonth.of(2026, 11), QUOTA_20, List.of(paidUp), List.of()).lines())
            .as("in force but paying no premium: nothing on original terms")
            .isEmpty();
    }

    @Test
    void aSinglePremiumIsChargedOnceInTheMonthCoverBegan() {
        CededPolicy single = policy("POL-S", "SINGLE", "2400000.00", null, open("2026-10-03"));

        assertThat(BordereauCalculator.calculate(OCTOBER, QUOTA_20, List.of(single), List.of()).premium())
            .isEqualByComparingTo("1200000.00");
        assertThat(BordereauCalculator.calculate(YearMonth.of(2026, 11), QUOTA_20, List.of(single), List.of()).lines())
            .isEmpty();
    }

    @Test
    void anXolTreatysAnnualPremiumIsChargedOneTwelfthAMonth() {
        Result r = BordereauCalculator.calculate(OCTOBER, new Treaty("TZS", new BigDecimal("10"),
            new BigDecimal("1200000.00")), List.of(), List.of());

        assertThat(r.lines()).singleElement().satisfies(l -> {
            assertThat(l.type()).isEqualTo(LineType.XOL_PREMIUM);
            assertThat(l.premium()).isEqualByComparingTo("100000.00");
            assertThat(l.commission()).isEqualByComparingTo("10000.00");
        });
        assertThat(r.policyCount()).isZero();
    }

    @Test
    void recoveriesAreListedToBeMatchedButAddNothingToThePremium() {
        UUID claim = UUID.randomUUID();
        Result r = BordereauCalculator.calculate(OCTOBER, QUOTA_20,
            List.of(policy("POL-1", "MONTHLY", "100000.00", null, open("2026-01-01"))),
            List.of(new Recovery(claim, "POL-1", new BigDecimal("1000000.00"), "TZS")));

        assertThat(r.recoveries()).isEqualByComparingTo("1000000.00");
        assertThat(r.premium()).isEqualByComparingTo("50000.00");
        assertThat(r.lines()).filteredOn(l -> l.type() == LineType.RECOVERY).singleElement()
            .satisfies(l -> assertThat(l.claimId()).isEqualTo(claim));
    }

    @Test
    void aPremiumInAnotherCurrencyIsLeftOffRatherThanConverted() {
        CededPolicy usd = new CededPolicy("POL-USD", new BigDecimal("0.5"), new BigDecimal("100.00"), "USD", "MONTHLY",
            null, List.of(open("2026-01-01")));

        assertThat(BordereauCalculator.calculate(OCTOBER, QUOTA_20, List.of(usd), List.of()).lines()).isEmpty();
    }
}
