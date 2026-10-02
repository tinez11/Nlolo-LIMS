package tz.co.nlolo.lifeplatform.bonus.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.bonus.domain.Participant;
import tz.co.nlolo.lifeplatform.bonus.domain.StatusEvent;
import tz.co.nlolo.lifeplatform.bonus.infrastructure.ParticipantRepository;
import tz.co.nlolo.lifeplatform.bonus.infrastructure.StatusEventRepository;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyNotFoundException;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Turns policy's lifecycle events into this module's status record -- for participating policies
 * only. A policy on a non-participating version has no participant row, and every later event for
 * it is ignored, so the record never grows with policies that can never receive a bonus.
 *
 * <p><b>Product first, bonus tables second</b> (plan §12, L6). Every policy event on the platform
 * reaches this listener, so the very first question is product's: is this version with-profits? Only
 * then is a {@code bonus.*} table read. A test class that never issues a with-profits policy -- most
 * of them -- therefore never needs bonus V1 in its migration list, and logs no failures for it.
 *
 * <p>effective_at is the envelope's occurredAt. Every event is published in the transaction that
 * made the change, so that IS when the status changed.
 */
@Service
public class StatusRecorder {

    /** Event type -> the status the policy is in afterwards (policy.domain.Policy's own strings). */
    static final Map<String, String> STATUS_AFTER = Map.ofEntries(
        Map.entry("policy.PolicyActivated", "ACTIVE"),
        Map.entry("policy.PolicySuspended", "SUSPENDED"),
        Map.entry("policy.PolicyResumed", "ACTIVE"),
        Map.entry("policy.PolicyLapsed", "LAPSED"),
        Map.entry("policy.PolicyReinstated", "REINSTATED"),
        Map.entry("policy.PolicyMadePaidUp", "PAID_UP"),
        Map.entry("policy.PolicySurrendered", "SURRENDERED"),
        Map.entry("policy.PolicyMatured", "MATURED"),
        Map.entry("policy.PolicyExpired", "EXPIRED"),
        Map.entry("policy.PolicyNotTakenUp", "NOT_TAKEN_UP"),
        Map.entry("policy.PolicyCancelledFreeLook", "CANCELLED_FREE_LOOK"));

    private final ParticipantRepository participants;
    private final StatusEventRepository events;
    private final ProductApi productApi;
    private final PolicyApi policyApi;

    public StatusRecorder(ParticipantRepository participants, StatusEventRepository events, ProductApi productApi,
                          PolicyApi policyApi) {
        this.participants = participants;
        this.events = events;
        this.productApi = productApi;
        this.policyApi = policyApi;
    }

    @Transactional
    public void record(DomainEventEnvelope<?> envelope) {
        UUID tenantId = TenantContext.get();
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) envelope.payload();
        String policyNumber = (String) p.get("policyNumber");
        boolean issued = "policy.PolicyIssued".equals(envelope.eventType());

        UUID versionId = issued ? uuid(p.get("productVersionId")) : versionOf(policyNumber);
        if (versionId == null || !productApi.resolveBonusPlan(versionId).participating()) {
            return; // not with-profits: no bonus table is touched
        }
        if (events.existsByTenantIdAndEventId(tenantId, envelope.eventId())) {
            return; // a redelivery; ux_status_event_event is the guarantee behind this fast path
        }
        if (issued) {
            if (participants.existsById(policyNumber)) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> sa = (Map<String, Object>) p.get("sumAssured");
            participants.save(new Participant(tenantId, policyNumber, uuid(p.get("productId")), versionId,
                (String) sa.get("currencyCode"), LocalDate.parse((String) p.get("issueDate"))));
            events.save(new StatusEvent(tenantId, envelope.eventId(), policyNumber, (String) p.get("status"),
                new BigDecimal((String) sa.get("amount")), envelope.occurredAt()));
            return;
        }
        String status = STATUS_AFTER.get(envelope.eventType());
        if (status == null || !participants.existsById(policyNumber)) {
            return;
        }
        // PolicyMadePaidUp carries the reduced sum assured as a plain decimal string, not a money
        // object (PolicyApiImpl.makePaidUp) -- the plan had it as one, which would have thrown.
        BigDecimal sumAssured = "PAID_UP".equals(status) && p.get("paidUpSumAssured") != null
            ? new BigDecimal(String.valueOf(p.get("paidUpSumAssured"))) : null;
        events.save(new StatusEvent(tenantId, envelope.eventId(), policyNumber, status, sumAssured, envelope.occurredAt()));
    }

    /** The policy's own version, from policy -- an event naming a policy that does not exist is not ours. */
    private UUID versionOf(String policyNumber) {
        if (policyNumber == null) {
            return null;
        }
        try {
            return policyApi.getPolicy(policyNumber).productVersionId();
        } catch (PolicyNotFoundException e) {
            return null;
        }
    }

    /** A UUID in process, a String after any serialising hop -- accept both. */
    private static UUID uuid(Object value) {
        return value == null ? null : value instanceof UUID u ? u : UUID.fromString(String.valueOf(value));
    }
}
