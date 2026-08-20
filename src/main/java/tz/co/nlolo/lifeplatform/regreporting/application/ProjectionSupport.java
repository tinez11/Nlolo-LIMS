package tz.co.nlolo.lifeplatform.regreporting.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Shared by all four Task 6 event listeners ({@link PolicyEventListener}, {@link
 * ClaimsEventListener}, {@link BillingEventListener}, {@link ReinsuranceEventListener}): period
 * derivation and the {@code UNKNOWN} sentinels a missing dimension lookup falls back to rather
 * than dropping a movement -- one small internal helper instead of the same quarter arithmetic and
 * sentinel literals repeated four times.
 */
final class ProjectionSupport {

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

    static String quarterOf(LocalDate date) {
        int quarter = (date.getMonthValue() - 1) / 3 + 1;
        return date.getYear() + "-Q" + quarter;
    }

    /** For a date-only business field, e.g. {@code PolicyIssuedPayload.issueDate} or {@code
     * ClaimRegisteredPayload.dateOfEvent}. */
    static String quarterOfDate(String isoDate) {
        return quarterOf(LocalDate.parse(isoDate));
    }

    /** For a date-time business field, e.g. {@code PolicyLapsedPayload.lapsedAt} or {@code
     * ClaimSettledPayload.settledAt}. Interpreted in UTC: this platform has no per-tenant reporting
     * timezone, and every {@code occurredAt}-shaped instant elsewhere on it is read the same way. */
    static String quarterOfInstant(String isoInstant) {
        return quarterOf(Instant.parse(isoInstant).atZone(ZoneOffset.UTC).toLocalDate());
    }

    /** Fallback for an event whose payload carries no business timestamp at all -- verified against
     * asyncapi-events.yaml for {@code ClaimApproved}, {@code ClaimRejected} and {@code
     * CessionRecorded} -- "today", per the Task 6 brief's period-derivation rule. */
    static String currentQuarter() {
        return quarterOf(LocalDate.now());
    }
}
