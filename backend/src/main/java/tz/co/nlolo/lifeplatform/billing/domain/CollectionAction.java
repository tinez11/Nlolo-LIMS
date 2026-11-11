package tz.co.nlolo.lifeplatform.billing.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One thing done to collect a premium (billing V20, 2026-11-11, analytics plan 1): a call with its outcome, a reminder
 * SMS, or a mobile-money payment request. Append-only: what was done is never edited. A promise to pay is a note only;
 * nothing reads it to hold back reminders or dunning (the user, 2026-11-11).
 */
@Entity
@Table(name = "collection_action", schema = "billing")
public class CollectionAction {

    public static final String CALL = "CALL";
    public static final String REMINDER = "REMINDER";
    public static final String PAYMENT_REQUEST = "PAYMENT_REQUEST";

    @Id @Column(name = "action_id") private UUID actionId = UUID.randomUUID();
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "invoice_id", nullable = false) private UUID invoiceId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(nullable = false) private String kind;
    @Column private String outcome;
    @Column(name = "promised_on") private LocalDate promisedOn;
    @Column private String note;
    @Column(name = "recorded_by", nullable = false) private String recordedBy;
    @Column(name = "recorded_at", nullable = false) private Instant recordedAt = Instant.now();

    protected CollectionAction() {}

    private CollectionAction(UUID tenantId, UUID invoiceId, String policyNumber, String kind, String outcome,
                             LocalDate promisedOn, String note, String recordedBy) {
        this.tenantId = tenantId;
        this.invoiceId = invoiceId;
        this.policyNumber = policyNumber;
        this.kind = kind;
        this.outcome = outcome;
        this.promisedOn = promisedOn;
        this.note = note;
        this.recordedBy = recordedBy;
    }

    public static CollectionAction call(UUID tenantId, UUID invoiceId, String policyNumber, String outcome,
                                        LocalDate promisedOn, String note, String by) {
        return new CollectionAction(tenantId, invoiceId, policyNumber, CALL, outcome, promisedOn, note, by);
    }

    public static CollectionAction reminder(UUID tenantId, UUID invoiceId, String policyNumber, String by) {
        return new CollectionAction(tenantId, invoiceId, policyNumber, REMINDER, null, null, null, by);
    }

    public static CollectionAction paymentRequest(UUID tenantId, UUID invoiceId, String policyNumber, String by) {
        return new CollectionAction(tenantId, invoiceId, policyNumber, PAYMENT_REQUEST, null, null, null, by);
    }

    public UUID getActionId() { return actionId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getInvoiceId() { return invoiceId; }
    public String getPolicyNumber() { return policyNumber; }
    public String getKind() { return kind; }
    public String getOutcome() { return outcome; }
    public LocalDate getPromisedOn() { return promisedOn; }
    public String getNote() { return note; }
    public String getRecordedBy() { return recordedBy; }
    public Instant getRecordedAt() { return recordedAt; }
}
