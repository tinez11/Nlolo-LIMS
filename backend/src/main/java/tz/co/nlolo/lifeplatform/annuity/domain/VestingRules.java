package tz.co.nlolo.lifeplatform.annuity.domain;

import tz.co.nlolo.lifeplatform.annuity.api.VestingInstructionInput;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Everything an instruction must satisfy before it is recorded (product step 5 D2), in the words the
 * console mirrors. Pure: the window and cap are passed in, so the vesting sweep re-checks a standing
 * instruction against a window a re-confirmed date of birth has moved.
 */
public final class VestingRules {

    private VestingRules() {}

    /** @param vestingDate the instruction's date, the target when it named none */
    public static void check(VestingInstructionInput in, LocalDate vestingDate, LocalDate today, LocalDate target,
                             LocalDate earliest, LocalDate latest, BigDecimal cap) {
        checkDate(vestingDate, today, earliest, latest);
        boolean deferral = vestingDate.isAfter(target);
        if (deferral && in.contributions() == null) {
            throw new IllegalArgumentException("A deferral must say whether contributions continue to the new date or stop at "
                + target);
        }
        if (!deferral && in.contributions() != null) {
            throw new IllegalArgumentException("Only a deferral says what happens to contributions");
        }
        BigDecimal pct = in.lumpSumPercent();
        if (pct == null || pct.signum() < 0 || pct.compareTo(cap) > 0) {
            throw new IllegalArgumentException("The lump sum can be from 0% to " + cap.stripTrailingZeros().toPlainString()
                + "% of the balance");
        }
    }

    /** The date alone: what the sweep re-checks before it vests. */
    public static void checkDate(LocalDate vestingDate, LocalDate today, LocalDate earliest, LocalDate latest) {
        if (vestingDate.isBefore(today)) {
            throw new IllegalArgumentException("A vesting date cannot be in the past");
        }
        checkWindow(vestingDate, earliest, latest);
    }

    /** The window alone: a held contract past its date may still vest on the day the sweep reaches it. */
    public static void checkWindow(LocalDate vestingDate, LocalDate earliest, LocalDate latest) {
        if (vestingDate.isBefore(earliest)) {
            throw new IllegalArgumentException("The earliest this pension can vest is " + earliest + ", at the minimum vesting age");
        }
        if (vestingDate.isAfter(latest)) {
            throw new IllegalArgumentException("The latest this pension can be deferred to is " + latest
                + ", at the maximum vesting age");
        }
    }
}
