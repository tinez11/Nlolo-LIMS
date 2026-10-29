package tz.co.nlolo.lifeplatform.distribution.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * The platform's side of an agent's Keycloak login (2026-10-28, db-migrations/distribution/V8): which user, its
 * username, how its last password went out, and whether it is open. One row per agent.
 */
@Entity
@Table(name = "agent_login", schema = "distribution")
public class AgentLogin {

    public enum Status { OPEN, CLOSED, SUSPENDED }

    public enum Delivery { EMAIL_LINK, SMS_PASSWORD, TEMPORARY_PASSWORD }

    @Id @Column(name = "agent_login_id") private UUID agentLoginId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "agent_id", nullable = false) private UUID agentId;
    @Column(name = "party_id", nullable = false) private UUID partyId;
    @Column(name = "keycloak_user_id", nullable = false) private String keycloakUserId;
    @Column(nullable = false) private String username;
    @Column(nullable = false) private String delivery;
    @Column(nullable = false) private String status;
    @Column(name = "created_by", nullable = false) private String createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "password_sent_at", nullable = false) private Instant passwordSentAt;
    @Column(name = "closed_by") private String closedBy;
    @Column(name = "closed_at") private Instant closedAt;
    @Version private long version;

    protected AgentLogin() {}

    public AgentLogin(UUID tenantId, UUID agentId, UUID partyId, String keycloakUserId, String username,
                      Delivery delivery, String createdBy) {
        this.agentLoginId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.agentId = agentId;
        this.partyId = partyId;
        this.keycloakUserId = keycloakUserId;
        this.username = username;
        this.delivery = delivery.name();
        this.status = Status.OPEN.name();
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.passwordSentAt = this.createdAt;
    }

    /** A new password went out, by this route. */
    public void passwordSent(Delivery delivery) {
        this.delivery = delivery.name();
        this.passwordSentAt = Instant.now();
    }

    public void opened() {
        this.status = Status.OPEN.name();
        this.closedBy = null;
        this.closedAt = null;
    }

    public void closed(Status why, String by) {
        this.status = why.name();
        this.closedBy = by;
        this.closedAt = Instant.now();
    }

    public UUID getAgentId() { return agentId; }
    public UUID getPartyId() { return partyId; }
    public String getKeycloakUserId() { return keycloakUserId; }
    public String getUsername() { return username; }
    public Status getStatus() { return Status.valueOf(status); }
    public Delivery getDelivery() { return Delivery.valueOf(delivery); }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPasswordSentAt() { return passwordSentAt; }
    public String getClosedBy() { return closedBy; }
    public Instant getClosedAt() { return closedAt; }
}
