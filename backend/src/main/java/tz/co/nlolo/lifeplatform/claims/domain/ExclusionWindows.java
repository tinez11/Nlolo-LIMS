package tz.co.nlolo.lifeplatform.claims.domain;

import tz.co.nlolo.lifeplatform.claims.api.ClaimDeclineReason;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

/**
 * Which exclusion windows are OPEN on the date something happened.
 *
 * <p><b>Open means an assessor may cite that reason. It is not a decision, and it is not a
 * belief about how the borrower died.</b>
 *
 * <p>That distinction is the design, and the alternative genuinely cannot work.
 * {@code DeathClaimDetails.causeOfDeath} is a free-text String, and a credit-life borrower has
 * no health record anywhere on this platform: they were never underwritten, and the lender's
 * file carries a name, a date of birth, a principal, a term and a disbursement date. A rule
 * that read the details and returned a decline would be dead code, or — far worse — a
 * substring match on prose deciding a multi-million-shilling payout, where a narrative
 * <em>ruling out</em> suicide contains the word and the claim is refused.
 *
 * <p>So the platform owns the dates and the assessor owns the finding. This class is the
 * dates, and it deliberately does not import {@code ClaimDetails}: it is structurally
 * incapable of deciding anything about the claim.
 */
public final class ExclusionWindows {

    private ExclusionWindows() {}

    /**
     * @param coverStart when this life went on risk. On credit life the DISBURSEMENT date, not
     *     the date the enrolment file arrived — files land weeks later, and using the arrival
     *     date would silently shorten every window in the lender's favour.
     * @param dateOfEvent when the thing being claimed for happened
     * @param periods the product's window lengths; {@link ExclusionPeriods#none()} opens nothing
     * @return the reasons an assessor may cite, never a decision
     */
    public static Set<ClaimDeclineReason> openAt(LocalDate coverStart, LocalDate dateOfEvent,
                                                  ExclusionPeriods periods) {
        if (dateOfEvent.isBefore(coverStart)) {
            // Arithmetic on a negative elapsed period would report every window open, which
            // reads as "decline this" for a claim that should never have been registered.
            throw new IllegalArgumentException("Date of event " + dateOfEvent
                + " is before cover started on " + coverStart);
        }
        Set<ClaimDeclineReason> open = EnumSet.noneOf(ClaimDeclineReason.class);
        if (insideWindow(coverStart, dateOfEvent, periods.suicideMonths())) {
            open.add(ClaimDeclineReason.SUICIDE_WITHIN_EXCLUSION);
        }
        if (insideWindow(coverStart, dateOfEvent, periods.preExistingMonths())) {
            open.add(ClaimDeclineReason.PRE_EXISTING_WITHIN_EXCLUSION);
        }
        return open;
    }

    /**
     * The anniversary itself is OUTSIDE. Twelve months means twelve months, and the first day
     * of the thirteenth is not "within twelve" — a boundary somebody will argue in writing one
     * day, so it is decided here rather than left to whoever reads the code next.
     */
    private static boolean insideWindow(LocalDate coverStart, LocalDate dateOfEvent, Integer months) {
        return months != null && dateOfEvent.isBefore(coverStart.plusMonths(months));
    }
}
