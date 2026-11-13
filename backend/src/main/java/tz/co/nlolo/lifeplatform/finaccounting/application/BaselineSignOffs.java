package tz.co.nlolo.lifeplatform.finaccounting.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.finaccounting.api.BaselineSignOffView;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingValidationException;
import tz.co.nlolo.lifeplatform.finaccounting.api.PolicyRegisterStateException;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The accounting-policy baseline signed off by people (2026-11-12, finance findings 99 and 66). The baseline is written
 * by the platform at the first posting -- nothing could post without it -- so it stays in force; what was missing is a
 * person's name on it. Finance signs it, and an actuary may co-sign (the user: "99 finance but can add role actuary").
 * Once per signer, never edited, and never one person for both.
 */
@Component
public class BaselineSignOffs {

    public enum Signer { FINANCE, ACTUARY }

    private final JdbcTemplate jdbc;

    BaselineSignOffs(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public BaselineSignOffView current() {
        Map<String, BaselineSignOffView.Signature> byRole = new HashMap<>();
        jdbc.query("SELECT signer_role, signed_by, signed_at, reference FROM finaccounting.baseline_sign_off"
                + " WHERE tenant_id = ?",
            rs -> {
                byRole.put(rs.getString("signer_role"), new BaselineSignOffView.Signature(rs.getString("signed_by"),
                    rs.getTimestamp("signed_at").toInstant(), rs.getString("reference")));
            },
            TenantContext.get());
        return new BaselineSignOffView(byRole.get(Signer.FINANCE.name()), byRole.get(Signer.ACTUARY.name()));
    }

    @Transactional
    public BaselineSignOffView signOff(Signer signer, String reference, String by) {
        if (signer == null) {
            throw new FinaccountingValidationException("Say whether this is finance's or the actuary's sign-off");
        }
        if (reference == null || reference.isBlank()) {
            throw new FinaccountingValidationException("Name what the sign-off rests on, such as a board minute");
        }
        if (reference.trim().length() > 200) {
            throw new FinaccountingValidationException("The sign-off reference is at most 200 characters");
        }
        BaselineSignOffView now = current();
        BaselineSignOffView.Signature mine = signer == Signer.FINANCE ? now.finance() : now.actuary();
        BaselineSignOffView.Signature other = signer == Signer.FINANCE ? now.actuary() : now.finance();
        if (mine != null) {
            throw new PolicyRegisterStateException("The baseline is already signed off by "
                + (signer == Signer.FINANCE ? "finance" : "the actuary"));
        }
        if (other != null && other.signedBy().equals(by)) {
            throw new PolicyRegisterStateException("You signed it off already; finance and the actuary are two people");
        }
        UUID tenantId = TenantContext.get();
        jdbc.update("INSERT INTO finaccounting.baseline_sign_off (tenant_id, signer_role, signed_by, signed_at, reference)"
            + " VALUES (?, ?, ?, ?, ?)", tenantId, signer.name(), by, Timestamp.from(java.time.Instant.now()),
            reference.trim());
        return current();
    }
}
