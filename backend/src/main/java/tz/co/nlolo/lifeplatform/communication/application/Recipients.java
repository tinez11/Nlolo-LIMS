package tz.co.nlolo.lifeplatform.communication.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Who to tell about a policy, a claim or a loan (communication V19, 2026-11-08). Most business events name only the
 * thing they are about, and communication may not read policy, claims or policyloan -- so it remembers what their own
 * events told it: a policy's holder, a claim's claimant, a loan's policy.
 */
@Component
class Recipients {

    record Recipient(UUID partyId, String policyNumber) {}

    private final JdbcTemplate jdbc;

    Recipients(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Remembers, or updates, who a reference names -- a null party keeps the one already known. */
    void remember(UUID tenantId, String refType, String refId, UUID partyId, String policyNumber) {
        if (refId == null) return;
        jdbc.update("INSERT INTO communication.recipient (tenant_id, ref_type, ref_id, party_id, policy_number)"
                + " VALUES (?, ?, ?, ?, ?) ON CONFLICT (tenant_id, ref_type, ref_id) DO UPDATE"
                + " SET party_id = COALESCE(EXCLUDED.party_id, communication.recipient.party_id),"
                + "     policy_number = COALESCE(EXCLUDED.policy_number, communication.recipient.policy_number),"
                + "     updated_at = now()",
            tenantId, refType, refId, partyId, policyNumber);
    }

    Optional<Recipient> find(UUID tenantId, String refType, String refId) {
        if (refId == null) return Optional.empty();
        return jdbc.query("SELECT party_id, policy_number FROM communication.recipient"
                + " WHERE tenant_id = ? AND ref_type = ? AND ref_id = ?",
            (rs, i) -> new Recipient(rs.getObject("party_id", UUID.class), rs.getString("policy_number")),
            tenantId, refType, refId).stream().findFirst();
    }

    /** The policyholder, as the policy's own events last named them. */
    Optional<UUID> holderOf(UUID tenantId, String policyNumber) {
        return find(tenantId, "POLICY", policyNumber).map(Recipient::partyId);
    }
}
