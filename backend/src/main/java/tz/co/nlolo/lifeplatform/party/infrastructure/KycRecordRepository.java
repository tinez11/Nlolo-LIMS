package tz.co.nlolo.lifeplatform.party.infrastructure;

import tz.co.nlolo.lifeplatform.party.domain.KycRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface KycRecordRepository extends JpaRepository<KycRecord, UUID> {

    /**
     * Every decision taken on one party, newest first.
     *
     * <p>Newest first, unlike a membership roll: this is a history read to find out where a case
     * stands, so the decision in force is the first line rather than the last.
     *
     * <p>Matched on {@code tenantId} as well as {@code partyId} although RLS already refuses
     * another tenant's rows — the same belt-and-braces the party searches above use. The caller
     * resolves the party first, which is what turns a foreign id into a 404 rather than an empty
     * list.
     */
    List<KycRecord> findByTenantIdAndPartyIdOrderByCreatedAtDesc(UUID tenantId, UUID partyId);
}
