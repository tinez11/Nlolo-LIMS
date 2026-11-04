package tz.co.nlolo.lifeplatform.party.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.approvals.api.ActionSource;
import tz.co.nlolo.lifeplatform.document.api.DocumentApi;
import tz.co.nlolo.lifeplatform.document.api.DocumentType;
import tz.co.nlolo.lifeplatform.party.api.KycStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * KYC's jobs for the bell (2026-11-03). Identity documents filed after the last decision are staff's to check; a
 * rejected client with nothing filed since is their agent's to chase. Sending documents does not move the status, so
 * the line between the two is the newest upload against the last decision.
 */
@Configuration
class PartyActionSources {

    @Bean
    ActionSource kycToVerify(PartyRepository parties, DocumentApi documents) {
        return ActionSource.forStaff("kyc-verify", "clients.kyc", "Verify them", (viewer, limit) -> {
            Map<String, Instant> uploads = documents.latestUploads("party:", DocumentType.KYC_EVIDENCE);
            List<ActionSource.Item> waiting = new ArrayList<>();
            for (Object[] p : parties.findUnverified(TenantContext.get(), null)) {
                Instant sent = uploads.get("party:" + p[0]);
                if (sent != null && (p[3] == null || sent.isAfter((Instant) p[3]))) {
                    waiting.add(new ActionSource.Item(p[0].toString(), null, (String) p[1], "Sent identity documents",
                        sent));
                }
            }
            return ActionSource.Pending.of(waiting, limit);
        });
    }

    @Bean
    ActionSource kycToResend(PartyRepository parties, DocumentApi documents) {
        return ActionSource.forAgents("kyc-resend", "Send clearer documents", (viewer, limit) -> {
            Map<String, Instant> uploads = documents.latestUploads("party:", DocumentType.KYC_EVIDENCE);
            List<ActionSource.Item> waiting = new ArrayList<>();
            for (Object[] p : parties.findUnverified(TenantContext.get(), viewer.subject())) {
                if (p[2] != KycStatus.REJECTED) continue;
                Instant rejected = (Instant) p[3];
                Instant sent = uploads.get("party:" + p[0]);
                if (sent == null || rejected == null || !sent.isAfter(rejected)) {
                    waiting.add(new ActionSource.Item(((UUID) p[0]).toString(), null, (String) p[1],
                        "Identity documents rejected", rejected));
                }
            }
            return ActionSource.Pending.of(waiting, limit);
        });
    }
}
