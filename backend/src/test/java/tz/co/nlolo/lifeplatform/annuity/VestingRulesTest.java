package tz.co.nlolo.lifeplatform.annuity;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.annuity.api.VestingInstructionInput;
import tz.co.nlolo.lifeplatform.annuity.domain.VestingRules;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A vesting instruction's rules, in the exact words the console mirrors (product step 5 D2). */
class VestingRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2036, 3, 1);
    private static final LocalDate TARGET = LocalDate.of(2040, 6, 15);
    private static final LocalDate EARLIEST = LocalDate.of(2035, 6, 15);
    private static final LocalDate LATEST = LocalDate.of(2050, 6, 15);
    private static final BigDecimal CAP = new BigDecimal("25.0000");

    private static VestingInstructionInput in(String pct, String contributions) {
        return new VestingInstructionInput(null, "LIFE-0G", "MONTHLY", null, pct == null ? null : new BigDecimal(pct), contributions);
    }

    private static void check(VestingInstructionInput in, LocalDate date) {
        VestingRules.check(in, date, TODAY, TARGET, EARLIEST, LATEST, CAP);
    }

    private static void refused(VestingInstructionInput in, LocalDate date, String message) {
        assertThatThrownBy(() -> check(in, date)).isInstanceOf(IllegalArgumentException.class).hasMessage(message);
    }

    @Test
    void anEarlyVestingWithALumpSumAtTheCapPasses() {
        assertThatCode(() -> check(in("25", null), TODAY)).doesNotThrowAnyException();
    }

    @Test
    void aDeferralSayingWhatContributionsDoPasses() {
        assertThatCode(() -> check(in("0", "STOP"), TARGET.plusYears(2))).doesNotThrowAnyException();
        assertThatCode(() -> check(in("0", "CONTINUE"), TARGET.plusYears(2))).doesNotThrowAnyException();
    }

    @Test
    void notInThePast() {
        refused(in("0", null), TODAY.minusDays(1), "A vesting date cannot be in the past");
    }

    @Test
    void notBeforeTheMinimumVestingAge() {
        LocalDate young = LocalDate.of(2034, 1, 1);
        assertThatThrownBy(() -> VestingRules.check(in("0", null), young, LocalDate.of(2033, 1, 1), TARGET, EARLIEST, LATEST, CAP))
            .hasMessage("The earliest this pension can vest is 2035-06-15, at the minimum vesting age");
    }

    @Test
    void notAfterTheMaximumVestingAge() {
        refused(in("0", "STOP"), LATEST.plusDays(1), "The latest this pension can be deferred to is 2050-06-15, at the maximum vesting age");
    }

    @Test
    void aDeferralMustSayWhatContributionsDo() {
        refused(in("0", null), TARGET.plusDays(1),
            "A deferral must say whether contributions continue to the new date or stop at 2040-06-15");
    }

    @Test
    void onlyADeferralSaysWhatContributionsDo() {
        refused(in("0", "STOP"), TARGET, "Only a deferral says what happens to contributions");
    }

    @Test
    void theLumpSumIsWithinTheCap() {
        String message = "The lump sum can be from 0% to 25% of the balance";
        refused(in("25.01", null), TARGET, message);
        refused(in("-1", null), TARGET, message);
        refused(in(null, null), TARGET, message);
    }
}
