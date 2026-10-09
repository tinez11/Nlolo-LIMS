package tz.co.nlolo.lifeplatform.communication.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One attempt to tell one person one thing.
 *
 * <p>A historical record, not a live view of configuration: it stores the template KEY rather
 * than a reference to the template row, because that row's body is editable and a dispatch has to
 * keep meaning what it meant when it was sent.
 *
 * <p>A FAILED row is a first-class outcome here rather than an error to be swept up. Nothing on
 * this platform retries a notification, deliberately, so the row is the only trace that somebody
 * was owed a message and did not get one — which is exactly what the outbox exists to show an
 * operator, and why {@code failure_reason} had to be added in V4.
 */
@Entity
@Table(name = "notification_dispatch", schema = "communication")
public class NotificationDispatch {

    @Id
    @Column(name = "dispatch_id")
    private UUID dispatchId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "party_id", nullable = false)
    private UUID partyId;

    @Column(name = "template_key", nullable = false)
    private String templateKey;

    @Column(nullable = false)
    private String channel;

    @Column(nullable = false)
    private String status = "PENDING";

    /** Null unless this message was about a policy. See V4. */
    @Column(name = "policy_number")
    private String policyNumber;

    /** Null on PENDING and SENT. Words an operator can act on, not a stack trace. */
    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** The text as rendered for this send (V15); null when it never rendered, and on rows from before V15. */
    @Column(name = "body")
    private String body;

    /** The event that owed the message (V15): its SMS and its email share it. Null on rows from before V15. */
    @Column(name = "event_id")
    private UUID eventId;

    /** When the customer opened it in the portal (V15). */
    @Column(name = "read_at")
    private Instant readAt;

    protected NotificationDispatch() {}

    public NotificationDispatch(UUID tenantId, UUID partyId, String templateKey, String channel, String policyNumber) {
        this.dispatchId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.partyId = partyId;
        this.templateKey = templateKey;
        this.channel = channel;
        this.policyNumber = policyNumber;
    }

    /**
     * Accepted by the transport.
     *
     * <p>Not "delivered" and not "read". Neither SMTP nor an aggregator's HTTP 200 proves a human
     * received anything, and this platform has no inbound delivery receipt to learn otherwise
     * from. SENT is the honest ceiling on what the outbox can claim.
     */
    public void markSent() {
        this.status = "SENT";
        this.dispatchedAt = Instant.now();
    }

    public void markFailed(String reason) {
        this.status = "FAILED";
        this.failureReason = reason;
        this.dispatchedAt = Instant.now();
    }

    public void recordBody(UUID eventId, String body) {
        this.eventId = eventId;
        this.body = body;
    }

    public void markRead() {
        if (readAt == null) {
            readAt = Instant.now();
        }
    }

    public String getBody() { return body; }
    public UUID getEventId() { return eventId; }
    public Instant getReadAt() { return readAt; }
    public UUID getDispatchId() { return dispatchId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getPartyId() { return partyId; }
    public String getTemplateKey() { return templateKey; }
    public String getChannel() { return channel; }
    public String getStatus() { return status; }
    public String getPolicyNumber() { return policyNumber; }
    public String getFailureReason() { return failureReason; }
    public Instant getDispatchedAt() { return dispatchedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
