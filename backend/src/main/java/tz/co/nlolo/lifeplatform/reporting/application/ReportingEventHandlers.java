package tz.co.nlolo.lifeplatform.reporting.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.reporting.domain.AnnualisedPremium;
import tz.co.nlolo.lifeplatform.reporting.domain.CivilDates;
import tz.co.nlolo.lifeplatform.reporting.domain.PolicyStatus;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ProjectionStore;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.domain.PayloadReader.amount;
import static tz.co.nlolo.lifeplatform.reporting.domain.PayloadReader.currency;
import static tz.co.nlolo.lifeplatform.reporting.domain.PayloadReader.date;
import static tz.co.nlolo.lifeplatform.reporting.domain.PayloadReader.flag;
import static tz.co.nlolo.lifeplatform.reporting.domain.PayloadReader.instant;
import static tz.co.nlolo.lifeplatform.reporting.domain.PayloadReader.integer;
import static tz.co.nlolo.lifeplatform.reporting.domain.PayloadReader.text;
import static tz.co.nlolo.lifeplatform.reporting.domain.PayloadReader.uuid;

/**
 * Turns one domain event into reporting rows. The only place event payloads are read. Called by the live
 * listener and by the rebuild, always through EventApplier, inside one transaction with TenantContext set.
 *
 * <p>Payload fields were verified against the producers (PolicyApiImpl, BillingApiImpl, PartyApiImpl), not
 * against intent. Three facts worth knowing before changing anything here:
 * <ul>
 *   <li>{@code policy.PolicySurrendered} fires for three things: a settled claim (carries {@code claimId}),
 *       a credit-life scheme whose last borrower left (a group scheme, no claimId), and a policyholder
 *       surrender being APPROVED (an individual policy, no claimId) -- cover stops at approval, so that is
 *       the status date. {@code policy.SurrenderPaid} only reports the money and changes no status.</li>
 *   <li>{@code GroupMemberAdded} has no member id; every member and covered-life event carries
 *       {@code livesCovered}, the lives AFTER the change.</li>
 *   <li>{@code PolicyNotTakenUp} is never in force, so it sets a date and adds no status change.</li>
 * </ul>
 */
@Component
public class ReportingEventHandlers {

    public static final UUID UNKNOWN_PRODUCT = new UUID(0L, 0L);
    static final String UNATTRIBUTED = "lifeplatform_reporting_unattributed_total";
    static final String UNKNOWN_FREQUENCY = "lifeplatform_reporting_unknown_premium_frequency_total";

    public static final Set<String> HANDLED = Set.of(
        "policy.PolicyIssued", "policy.PolicyActivated", "policy.PolicyNotTakenUp", "policy.PolicySuspended",
        "policy.PolicyResumed", "policy.PolicyLapsed", "policy.PolicyReinstated", "policy.PolicyMadePaidUp",
        "policy.PolicyMatured", "policy.PolicyExpired", "policy.PolicySurrendered", "policy.PolicyCancelledFreeLook",
        "policy.AnnuityEnded", "policy.GroupMemberAdded", "policy.GroupMemberExited", "policy.CoveredLifeAdded",
        "policy.CoveredLifeEnded", "billing.PremiumCollected");

    private final ProjectionStore store;
    private final MeterRegistry meters;

    public ReportingEventHandlers(ProjectionStore store, MeterRegistry meters) {
        this.store = store;
        this.meters = meters;
    }

    public boolean handles(String eventType) {
        return HANDLED.contains(eventType);
    }

    /** False for an unhandled type or an event already applied. */
    public boolean apply(UUID tenantId, UUID eventId, String eventType, Instant occurredAt, Map<String, Object> p) {
        if (!handles(eventType) || !store.markApplied(tenantId, eventId)) {
            return false;
        }
        String policyNumber = text(p, "policyNumber");
        switch (eventType) {
            case "policy.PolicyIssued" -> store.upsertPolicy(tenantId, facts(p, eventType));
            case "policy.PolicyActivated" -> {
                store.upsertPolicy(tenantId, facts(p, eventType));
                LocalDate on = onDate(p, "activatedAt", occurredAt);
                store.setActivated(tenantId, policyNumber, on);
                store.addStatusChange(tenantId, eventId, policyNumber, PolicyStatus.ACTIVE, eventType, on, occurredAt);
                Integer lives = integer(p, "livesCovered");
                if (lives != null) {
                    // Its own row id, derived from the event's so a replay produces the same one.
                    UUID livesRowId = UUID.nameUUIDFromBytes((eventId + ":lives").getBytes(StandardCharsets.UTF_8));
                    store.addLivesChange(tenantId, livesRowId, policyNumber, "ACTIVATED", lives, null, on, occurredAt);
                }
            }
            case "policy.PolicyNotTakenUp" -> {
                store.ensurePolicy(tenantId, policyNumber);
                store.setNotTakenUp(tenantId, policyNumber, onDate(p, "expiredAt", occurredAt));
            }
            case "policy.PolicySuspended" -> status(tenantId, eventId, eventType, occurredAt, p, PolicyStatus.SUSPENDED, "suspendedAt");
            case "policy.PolicyResumed" -> status(tenantId, eventId, eventType, occurredAt, p, PolicyStatus.ACTIVE, "resumedAt");
            case "policy.PolicyLapsed" -> status(tenantId, eventId, eventType, occurredAt, p, PolicyStatus.LAPSED, "lapsedAt");
            case "policy.PolicyReinstated" -> status(tenantId, eventId, eventType, occurredAt, p, PolicyStatus.ACTIVE, "reinstatedAt");
            case "policy.PolicyMadePaidUp" -> {
                status(tenantId, eventId, eventType, occurredAt, p, PolicyStatus.PAID_UP, "madePaidUpAt");
                if (amount(p, "paidUpSumAssured") != null) {
                    store.setSumAssured(tenantId, policyNumber, amount(p, "paidUpSumAssured"));
                }
            }
            case "policy.PolicyMatured" -> status(tenantId, eventId, eventType, occurredAt, p, PolicyStatus.MATURED, "maturedAt");
            case "policy.PolicyExpired" -> status(tenantId, eventId, eventType, occurredAt, p, PolicyStatus.EXPIRED, "expiredAt");
            case "policy.PolicySurrendered" -> {
                PolicyStatus outcome = p.get("claimId") != null ? PolicyStatus.CLAIM_TERMINATED
                    : store.isGroupScheme(tenantId, policyNumber) ? PolicyStatus.ENDED : PolicyStatus.SURRENDERED;
                status(tenantId, eventId, eventType, occurredAt, p, outcome, "surrenderedAt");
            }
            case "policy.PolicyCancelledFreeLook" ->
                status(tenantId, eventId, eventType, occurredAt, p, PolicyStatus.CANCELLED_FREE_LOOK, "cancelledAt");
            case "policy.AnnuityEnded" -> status(tenantId, eventId, eventType, occurredAt, p, PolicyStatus.ENDED, "endedAt");
            case "policy.GroupMemberAdded" -> lives(tenantId, eventId, occurredAt, p, "JOINED", "joinedOn");
            case "policy.CoveredLifeAdded" -> lives(tenantId, eventId, occurredAt, p, "JOINED", "coverStart");
            case "policy.GroupMemberExited" -> lives(tenantId, eventId, occurredAt, p, "EXITED", "leftOn");
            case "policy.CoveredLifeEnded" -> lives(tenantId, eventId, occurredAt, p, "EXITED", "endedOn");
            case "billing.PremiumCollected" -> {
                String payer = text(p, "payerRef");
                store.addCollection(tenantId, eventId, uuid(p, "invoiceId"), policyNumber, amount(p, "amount"),
                    currency(p, "amount"), onInstant(p, "collectedAt", occurredAt),
                    payer == null || payer.isBlank() ? "FIELD_CASH" : "MOBILE_MONEY");
            }
            default -> throw new IllegalStateException("HANDLED lists " + eventType + " but no case handles it");
        }
        return true;
    }

    private ProjectionStore.PolicyFacts facts(Map<String, Object> p, String eventType) {
        String frequency = text(p, "premiumFrequency");
        if (frequency != null && !AnnualisedPremium.isRecognised(frequency)) {
            meters.counter(UNKNOWN_FREQUENCY, "eventType", eventType).increment();
        }
        return new ProjectionStore.PolicyFacts(text(p, "policyNumber"), uuid(p, "policyholderPartyId"),
            uuid(p, "productId"), uuid(p, "productVersionId"), text(p, "productCategory"), text(p, "portfolioCode"),
            flag(p, "groupScheme"), uuid(p, "agentOfRecordId"), text(p, "salesChannel"), text(p, "branchCode"),
            amount(p, "sumAssured"), amount(p, "premium"), frequency,
            currency(p, "sumAssured") != null ? currency(p, "sumAssured") : currency(p, "premium"), date(p, "issueDate"));
    }

    private void status(UUID tenantId, UUID eventId, String eventType, Instant occurredAt, Map<String, Object> p,
                        PolicyStatus status, String timestampKey) {
        String policyNumber = text(p, "policyNumber");
        if (store.ensurePolicy(tenantId, policyNumber)) {
            meters.counter(UNATTRIBUTED, "eventType", eventType).increment();
        }
        store.addStatusChange(tenantId, eventId, policyNumber, status, eventType,
            onInstant(p, timestampKey, occurredAt), occurredAt);
    }

    private void lives(UUID tenantId, UUID eventId, Instant occurredAt, Map<String, Object> p, String kind, String dateKey) {
        Integer lives = integer(p, "livesCovered");
        if (lives == null) return;
        store.addLivesChange(tenantId, eventId, text(p, "policyNumber"), kind, lives, amount(p, "schemeTotalCovered"),
            onDate(p, dateKey, occurredAt), occurredAt);
    }

    /** A date-only field (activatedAt, joinedOn), else the Tanzanian day the event happened. */
    private static LocalDate onDate(Map<String, Object> p, String key, Instant occurredAt) {
        LocalDate d = date(p, key);
        return d != null ? d : CivilDates.of(occurredAt);
    }

    /** A timestamp field (lapsedAt, collectedAt) as a Tanzanian day, else the day the event happened. */
    private static LocalDate onInstant(Map<String, Object> p, String key, Instant occurredAt) {
        Instant at = instant(p, key);
        return CivilDates.of(at != null ? at : occurredAt);
    }
}
