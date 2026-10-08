package tz.co.nlolo.lifeplatform.omnichannel.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * One client's customer-portal access (2026-10-08): the Keycloak user the platform created for them, how the first
 * password reached them, and whether the access is open. See omnichannel V1 for why there is one row per client.
 */
@Entity
@Table(name = "portal_access", schema = "omnichannel")
public class PortalAccess {

    public enum Status { INVITED, ACTIVE, REVOKED }

    public enum Delivery { EMAIL_LINK, TEMPORARY_PASSWORD }

    @Id @Column(name = "portal_access_id") private UUID portalAccessId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "party_id", nullable = false) private UUID partyId;
    @Column(name = "keycloak_user_id", nullable = false) private String keycloakUserId;
    @Column(nullable = false) private String username;
    @Column(nullable = false) private String delivery;
    @Column(nullable = false) private String status;
    @Column(name = "invited_by", nullable = false) private String invitedBy;
    @Column(name = "invited_at", nullable = false) private Instant invitedAt;
    @Column(name = "activated_at") private Instant activatedAt;
    @Column(name = "revoked_by") private String revokedBy;
    @Column(name = "revoked_at") private Instant revokedAt;
    @Version private long version;

    protected PortalAccess() {}

    public PortalAccess(UUID tenantId, UUID partyId, String keycloakUserId, String username, Delivery delivery,
                        String invitedBy) {
        this.portalAccessId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.partyId = partyId;
        this.keycloakUserId = keycloakUserId;
        this.username = username;
        this.delivery = delivery.name();
        this.status = Status.INVITED.name();
        this.invitedBy = invitedBy;
        this.invitedAt = Instant.now();
    }

    /** Invited again after a revoke: the same Keycloak user, opened and given a fresh first password. */
    public void reinvited(Delivery delivery, String invitedBy) {
        this.delivery = delivery.name();
        this.status = Status.INVITED.name();
        this.invitedBy = invitedBy;
        this.invitedAt = Instant.now();
        this.activatedAt = null;
        this.revokedBy = null;
        this.revokedAt = null;
    }

    /** The first time the customer's own token reaches the platform. Idempotent. */
    public void activated() {
        if (Status.INVITED.name().equals(status)) {
            this.status = Status.ACTIVE.name();
            this.activatedAt = Instant.now();
        }
    }

    public void revoked(String by) {
        this.status = Status.REVOKED.name();
        this.revokedBy = by;
        this.revokedAt = Instant.now();
    }

    public UUID getPartyId() { return partyId; }
    public String getKeycloakUserId() { return keycloakUserId; }
    public String getUsername() { return username; }
    public Status getStatus() { return Status.valueOf(status); }
    public Delivery getDelivery() { return Delivery.valueOf(delivery); }
    public String getInvitedBy() { return invitedBy; }
    public Instant getInvitedAt() { return invitedAt; }
    public Instant getActivatedAt() { return activatedAt; }
    public String getRevokedBy() { return revokedBy; }
    public Instant getRevokedAt() { return revokedAt; }
}
