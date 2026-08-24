package tz.co.nlolo.lifeplatform.regreporting.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Shared by all four Task 6 event listeners ({@link PolicyEventListener}, {@link
 * ClaimsEventListener}, {@link BillingEventListener}, {@link ReinsuranceEventListener}): period
 * derivation, the {@code UNKNOWN} sentinels a missing dimension lookup falls back to rather
 * than dropping a movement, and the bounded optimistic-locking retry every one of them wraps its
 * read-modify-write in -- one small internal helper instead of the same quarter arithmetic,
 * sentinel literals and retry loop repeated four times.
 *
 * <p><b>Every period this module derives is UTC.</b> {@link #quarterOfInstant} reads an instant in
 * UTC explicitly, and {@link #quarterOf}/{@link #quarterOfDate} take a {@code LocalDate} that has
 * already been fixed by the producer -- there is no {@code LocalDate.now()}, and so no JVM-default
 * zone, anywhere in this class any more (M10 final review, I1). That matters because this module's
 * documented recovery remedy for a failed projection is to REPLAY it
 * (observability/alert-rules.yml, {@code RegreportingEventProcessingFailed}): a period derived from
 * "today" would land a replayed movement in the REPLAY's quarter rather than the original one,
 * silently corrupting history. Every handler now derives its period from a timestamp carried by the
 * event itself -- either a business timestamp on the payload, or the envelope's own {@code
 * occurredAt()} when the payload has none.
 */
final class ProjectionSupport {

    private static final Logger log = LoggerFactory.getLogger(ProjectionSupport.class);

    private ProjectionSupport() {}

    /** Attributed to a {@code policy_movement}/{@code premium_movement} row when the
     * {@code policyNumber -> productId} lookup through {@code policy_dimension} misses. Never
     * skip the movement -- a visibly-unattributed figure is strictly better than one that silently
     * vanished from a return (Task 6 brief). */
    static final UUID UNKNOWN_PRODUCT = new UUID(0L, 0L);

    /** Attributed to a {@code claims_movement} row when the {@code claimId -> claimType} lookup
     * through {@code claim_dimension} misses. {@code claim_dimension.claim_type}'s CHECK does NOT
     * admit this value (regreporting/V2 section 5), which is exactly why the sentinel is written to
     * {@code claims_movement} (no such CHECK there) rather than to a dimension row -- verified
     * against db-migrations/regreporting/V2 before relying on it. */
    static final String UNKNOWN_CLAIM_TYPE = "UNKNOWN";

    /** Every fact table in this module defaults its currency column to TZS (regreporting/V2
     * sections 4-6; see also {@code ReturnGenerator.REPORTING_CURRENCY}) -- the same fallback
     * applies once a dimension lookup has failed and the triggering payload carries no currency of
     * its own to fall back on instead. */
    static final String UNKNOWN_CURRENCY = "TZS";

    /**
     * Counts movements written under one of the {@code UNKNOWN} sentinels above (M10 final review,
     * I3). Before this, a dimension miss only logged at WARN, so unattributed movements could
     * accumulate indefinitely with nobody able to notice unless they already suspected a problem
     * and went looking through logs. Tagged by {@code eventType} so the pattern (which event's
     * dimension is missing) is visible from the metric alone. Alerted at low severity by
     * {@code RegreportingUnattributedMovements} -- "notice a pattern", not "something is broken":
     * a single miss is a legitimate out-of-order delivery, a sustained rate is a real projection
     * gap understating whichever return line filters on that dimension.
     */
    static final String UNATTRIBUTED_MOVEMENT_COUNTER = "lifeplatform_regreporting_unattributed_movement_total";

    /** Attempts, not retries: 1 initial try plus at most 2 more. */
    private static final int MAX_ATTEMPTS = 3;

    static String quarterOf(LocalDate date) {
        int quarter = (date.getMonthValue() - 1) / 3 + 1;
        return date.getYear() + "-Q" + quarter;
    }

    /**
     * For a date-only business field, e.g. {@code PolicyIssuedPayload.issueDate} or {@code
     * ClaimRegisteredPayload.dateOfEvent}. No zone is applied because none can be: a bare {@code
     * YYYY-MM-DD} on the wire has already had its zone resolved by whoever produced it, and
     * re-interpreting it in any zone here could only move it. (For {@code PolicyIssued.issueDate}
     * that producer is {@code PolicyApiImpl}, which stamps a server-local {@code LocalDate.now()} --
     * a producer-side concern deliberately out of scope for this module, recorded here so the
     * asymmetry with {@link #quarterOfInstant}'s explicit UTC is visible rather than accidental. A
     * quarter boundary is the only date on which the two could disagree, and only for a policy
     * issued within hours of it.)
     */
    static String quarterOfDate(String isoDate) {
        return quarterOf(LocalDate.parse(isoDate));
    }

    /** For a date-time business field, e.g. {@code PolicyLapsedPayload.lapsedAt} or {@code
     * ClaimSettledPayload.settledAt}. Interpreted in UTC: this platform has no per-tenant reporting
     * timezone, and every {@code occurredAt}-shaped instant elsewhere on it is read the same way. */
    static String quarterOfInstant(String isoInstant) {
        return quarterOf(Instant.parse(isoInstant).atZone(ZoneOffset.UTC).toLocalDate());
    }

    /** For an event whose payload carries no business timestamp at all -- verified against
     * asyncapi-events.yaml for {@code ClaimApproved}, {@code ClaimRejected} and {@code
     * CessionRecorded} -- the envelope's own {@code occurredAt()}, in UTC. Replaces the former
     * {@code currentQuarter()} ({@code LocalDate.now()}, JVM default zone), which was
     * replay-unsafe: see this class's javadoc. */
    static String quarterOfOccurrence(Instant occurredAt) {
        return quarterOf(occurredAt.atZone(ZoneOffset.UTC).toLocalDate());
    }

    /**
     * Runs {@code transactionalAttempt} -- a WHOLE transaction, containing one find-modify-save
     * sequence -- retrying up to {@link #MAX_ATTEMPTS} times if it fails the optimistic lock
     * regreporting/V3 added to all four fact tables (M10 final review, C1).
     *
     * <p><b>The retry is deliberately OUTSIDE the transaction, not inside it.</b> Two reasons, both
     * load-bearing. First, with {@code @Version} the conflict is not detected when {@code save()} is
     * called -- the UPDATE ... WHERE version = ? runs at COMMIT, so an in-handler retry would never
     * see the failure at all. Second, a Hibernate session that has thrown a stale-state exception
     * must be discarded, not reused: re-fetching the row in the same (rollback-marked) persistence
     * context is undefined. Retrying the whole {@code PROPAGATION_REQUIRES_NEW} transaction gives
     * each attempt a fresh session, so the re-fetch genuinely reads the winner's committed value
     * and the increment is re-applied on top of it rather than on top of a stale copy.
     *
     * <p>After {@link #MAX_ATTEMPTS} the last failure is RETHROWN, on purpose: the caller's existing
     * catch-all then increments {@code lifeplatform_regreporting_event_processing_failed_total} and
     * logs, which makes genuinely unrecoverable contention ALERTABLE. Swallowing it here would
     * reintroduce exactly the silence C1 was about.
     */
    static void withOptimisticLockRetry(String eventType, Runnable transactionalAttempt) {
        ObjectOptimisticLockingFailureException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                transactionalAttempt.run();
                return;
            } catch (ObjectOptimisticLockingFailureException e) {
                lastFailure = e;
                log.warn("regreporting lost the optimistic lock applying {} (attempt {} of {}) -- "
                    + "re-fetching the movement row and re-applying the increment", eventType, attempt, MAX_ATTEMPTS);
            }
        }
        log.error("regreporting could not apply {} after {} optimistic-lock attempts -- rethrowing so "
            + "the failure counter and its alert see it rather than losing the movement silently",
            eventType, MAX_ATTEMPTS);
        throw lastFailure;
    }
}
