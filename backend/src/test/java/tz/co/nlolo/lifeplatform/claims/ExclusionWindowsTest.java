package tz.co.nlolo.lifeplatform.claims;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.claims.api.ClaimDeclineReason;
import tz.co.nlolo.lifeplatform.claims.domain.ExclusionPeriods;
import tz.co.nlolo.lifeplatform.claims.domain.ExclusionWindows;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which exclusion windows are OPEN on the date something happened.
 *
 * <p>Open means <b>an assessor may cite this reason</b>. It does not mean the claim is
 * declined, and it does not mean the platform believes anything about how the borrower died.
 *
 * <p>That distinction is the whole design, and it exists because the obvious alternative
 * cannot work. {@code DeathClaimDetails.causeOfDeath} is a free-text String, and a credit-life
 * borrower has no health record anywhere on this platform — they were never underwritten, and
 * the enrolment file carries a name, a date of birth, a principal, a term and a disbursement
 * date. Nothing else. A rule that read the details and returned a decline would be either dead
 * code or a substring match on prose deciding a multi-million-shilling payout, where a
 * narrative <em>ruling out</em> suicide contains the word and the claim is refused.
 *
 * <p>So: the platform owns the dates, the assessor owns the finding. This class is the dates.
 * Pure, with no Spring and no database, because a disputed decline is argued in terms of
 * exactly these numbers.
 */
class ExclusionWindowsTest {

    /** Credit-life cover starts at DISBURSEMENT, not when the enrolment file reached us. */
    private static final LocalDate COVER_START = LocalDate.of(2026, 8, 3);

    /** What the client confirmed on 2026-09-22: twelve months each, no general waiting period. */
    private static final ExclusionPeriods TWELVE_AND_TWELVE = new ExclusionPeriods(12, 12);

    @Test
    void aDeathSixMonthsInLeavesBothWindowsOpen() {
        assertThat(ExclusionWindows.openAt(COVER_START, COVER_START.plusMonths(6), TWELVE_AND_TWELVE))
            .containsExactlyInAnyOrder(ClaimDeclineReason.SUICIDE_WITHIN_EXCLUSION,
                                       ClaimDeclineReason.PRE_EXISTING_WITHIN_EXCLUSION);
    }

    @Test
    void aDeathAfterThirteenMonthsLeavesNoWindowOpen() {
        assertThat(ExclusionWindows.openAt(COVER_START, COVER_START.plusMonths(13), TWELVE_AND_TWELVE))
            .isEmpty();
    }

    @Test
    void theAnniversaryItselfIsOutsideTheWindow() {
        // A boundary somebody will argue in writing one day. Twelve months means twelve
        // months; the first day of the thirteenth is not "within twelve".
        assertThat(ExclusionWindows.openAt(COVER_START, COVER_START.plusMonths(12), TWELVE_AND_TWELVE))
            .isEmpty();
    }

    @Test
    void theDayBeforeTheAnniversaryIsStillInside() {
        // The other half of the boundary, so the off-by-one cannot hide on either side.
        assertThat(ExclusionWindows.openAt(COVER_START, COVER_START.plusMonths(12).minusDays(1),
                TWELVE_AND_TWELVE))
            .containsExactlyInAnyOrder(ClaimDeclineReason.SUICIDE_WITHIN_EXCLUSION,
                                       ClaimDeclineReason.PRE_EXISTING_WITHIN_EXCLUSION);
    }

    @Test
    void anOpenWindowIsPermissionToDeclineAndNothingMore() {
        // On day one BOTH windows are open, and that must never be read as "nothing is payable
        // yet". The client confirmed there is NO general waiting period (answer 3.4); the test
        // that proves an ordinary day-one death is PAID lives end to end, because "is it paid?"
        // is a question about settlement rather than about dates.
        assertThat(ExclusionWindows.openAt(COVER_START, COVER_START, TWELVE_AND_TWELVE))
            .containsExactlyInAnyOrder(ClaimDeclineReason.SUICIDE_WITHIN_EXCLUSION,
                                       ClaimDeclineReason.PRE_EXISTING_WITHIN_EXCLUSION);
    }

    @Test
    void twoWindowsOfDifferentLengthsCloseIndependently() {
        // The client's answer sets both to twelve, but nothing may assume that -- a different
        // lender or a later product will not.
        ExclusionPeriods sixAndTwentyFour = new ExclusionPeriods(6, 24);

        assertThat(ExclusionWindows.openAt(COVER_START, COVER_START.plusMonths(9), sixAndTwentyFour))
            .containsExactly(ClaimDeclineReason.PRE_EXISTING_WITHIN_EXCLUSION);
    }

    @Test
    void oneWindowMayBeConfiguredAndTheOtherAbsent() {
        // A product with a suicide clause and no pre-existing exclusion is ordinary.
        assertThat(ExclusionWindows.openAt(COVER_START, COVER_START.plusMonths(3),
                new ExclusionPeriods(12, null)))
            .containsExactly(ClaimDeclineReason.SUICIDE_WITHIN_EXCLUSION);
    }

    @Test
    void aProductWithNoExclusionPeriodsOpensNoWindows() {
        // Every product on the platform today. Employer group life and individual business keep
        // exactly their current behaviour, and no exclusion reason becomes available on them.
        assertThat(ExclusionWindows.openAt(COVER_START, COVER_START.plusDays(1), ExclusionPeriods.none()))
            .isEmpty();
    }

    @Test
    void anEventBeforeCoverStartedIsRefusedRatherThanSilentlyTreatedAsInsideEveryWindow() {
        // Arithmetic on a negative elapsed period would report every window open, which reads
        // as "decline this" for a claim that should never have been registered at all. It is a
        // caller error, and the caller is told.
        assertThatThrownBy(() -> ExclusionWindows.openAt(COVER_START, COVER_START.minusDays(1),
                TWELVE_AND_TWELVE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("before cover started");
    }

    // ---- Family funeral cover: the waiting period (plan R7) ----

    /** Six months' waiting, accidents waived; no suicide or pre-existing window. */
    private static final ExclusionPeriods SIX_MONTHS_WAITING = new ExclusionPeriods(null, null, 6, true);

    @Test
    void aNaturalDeathInsideTheWaitingPeriodOpensIt() {
        assertThat(ExclusionWindows.openAt(COVER_START, COVER_START.plusMonths(5), SIX_MONTHS_WAITING, false))
            .containsExactly(ClaimDeclineReason.WITHIN_WAITING_PERIOD);
    }

    @Test
    void theWaitingPeriodClosesOnItsSixMonthAnniversary() {
        assertThat(ExclusionWindows.openAt(COVER_START, COVER_START.plusMonths(6).minusDays(1), SIX_MONTHS_WAITING, false))
            .containsExactly(ClaimDeclineReason.WITHIN_WAITING_PERIOD);
        assertThat(ExclusionWindows.openAt(COVER_START, COVER_START.plusMonths(6), SIX_MONTHS_WAITING, false)).isEmpty();
    }

    @Test
    void anAccidentHasNoWaitingPeriodWhenTheProductWaivesIt() {
        assertThat(ExclusionWindows.openAt(COVER_START, COVER_START.plusMonths(1), SIX_MONTHS_WAITING, true)).isEmpty();
    }

    @Test
    void anAccidentStillWaitsWhenTheProductDoesNotWaiveIt() {
        ExclusionPeriods noWaiver = new ExclusionPeriods(null, null, 6, false);
        assertThat(ExclusionWindows.openAt(COVER_START, COVER_START.plusMonths(1), noWaiver, true))
            .containsExactly(ClaimDeclineReason.WITHIN_WAITING_PERIOD);
    }

    @Test
    void noWaitingPeriodIsNeverOpen() {
        assertThat(ExclusionWindows.openAt(COVER_START, COVER_START.plusDays(1), TWELVE_AND_TWELVE, false))
            .doesNotContain(ClaimDeclineReason.WITHIN_WAITING_PERIOD);
    }
}
