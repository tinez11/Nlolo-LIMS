package tz.co.nlolo.lifeplatform.bonus.application;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.bonus.api.BonusEntryType;
import tz.co.nlolo.lifeplatform.bonus.api.BonusStateException;
import tz.co.nlolo.lifeplatform.bonus.domain.AttachmentEntry;
import tz.co.nlolo.lifeplatform.bonus.domain.Declaration;
import tz.co.nlolo.lifeplatform.bonus.domain.Participant;
import tz.co.nlolo.lifeplatform.bonus.infrastructure.AttachmentEntryRepository;
import tz.co.nlolo.lifeplatform.bonus.infrastructure.ParticipantRepository;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * The ONE place an attachment entry is written -- accumulation's LedgerService, for the same reason.
 * The head is locked, the entry is flushed so the once-only index fires HERE, and policy's projection
 * follows in the same transaction.
 */
@Service
public class AttachmentLedger {

    private final ParticipantRepository participants;
    private final AttachmentEntryRepository entries;
    private final PolicyApi policyApi;
    private final ApplicationEventPublisher events;

    public AttachmentLedger(ParticipantRepository participants, AttachmentEntryRepository entries, PolicyApi policyApi,
                            ApplicationEventPublisher events) {
        this.participants = participants;
        this.entries = entries;
        this.policyApi = policyApi;
        this.events = events;
    }

    @Transactional
    public AttachmentEntry attach(String policyNumber, Declaration d, BigDecimal basis, BigDecimal amount, String createdBy) {
        return write(policyNumber, BonusEntryType.REVERSIONARY, amount, d.getValuationDate(), d.getDeclarationId(), basis,
            d.getReversionaryRatePercent(), "declaration", "declaration:" + d.getDeclarationId() + ":" + policyNumber,
            null, null, createdBy);
    }

    @Transactional
    public AttachmentEntry reverse(String policyNumber, AttachmentEntry original, LocalDate on, String reason, String createdBy) {
        return write(policyNumber, BonusEntryType.REVERSAL, original.getAmount().negate(), on, null, null, null,
            "reversal", "reversal:" + original.getEntryId(), original.getEntryId(), reason, createdBy);
    }

    private AttachmentEntry write(String policyNumber, BonusEntryType type, BigDecimal amount, LocalDate effectiveDate,
                                  UUID declarationId, BigDecimal basis, BigDecimal rate, String sourceType, String sourceRef,
                                  UUID reverses, String reason, String createdBy) {
        UUID tenantId = TenantContext.get();
        Participant head = participants.lockForPosting(policyNumber)
            .orElseThrow(() -> new BonusStateException("Policy " + policyNumber + " is not with-profits"));
        int seq = head.advance(amount);
        AttachmentEntry entry = entries.saveAndFlush(new AttachmentEntry(tenantId, policyNumber, seq, type, amount,
            head.getAttachedTotal(), effectiveDate, declarationId, basis, rate, sourceType, sourceRef, reverses, reason, createdBy));
        participants.save(head);
        policyApi.restateAttachedBonus(policyNumber, head.getAttachedTotal());
        // One event per entry: audit records every domain event, so this is the bonus audit trail.
        events.publishEvent(DomainEventEnvelope.of("bonus.BonusEntryRecorded", tenantId, Map.of(
            "policyNumber", policyNumber, "seq", seq, "type", type.name(),
            "amount", Map.of("amount", amount.toPlainString(), "currencyCode", head.getCurrency()),
            "totalAfter", Map.of("amount", head.getAttachedTotal().toPlainString(), "currencyCode", head.getCurrency()),
            "effectiveDate", effectiveDate.toString(), "sourceRef", sourceRef)));
        return entry;
    }
}
