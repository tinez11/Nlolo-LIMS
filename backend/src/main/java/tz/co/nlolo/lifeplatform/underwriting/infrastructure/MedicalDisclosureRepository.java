package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.underwriting.domain.MedicalDisclosure;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MedicalDisclosureRepository extends JpaRepository<MedicalDisclosure, UUID> {

    /**
     * Every disclosure set on a case, oldest first — the order they were taken in, which is the
     * order a reader reconstructing what the applicant said needs them in.
     *
     * <p>Tie-broken on the id because {@code createdAt} defaults to {@code Instant.now()} in
     * Java, so two sets recorded in one request can share it exactly and "the order they were
     * taken in" would otherwise be scan order.
     */
    List<MedicalDisclosure> findByTenantIdAndCaseIdOrderByCreatedAtAscMedicalDisclosureIdAsc(
        UUID tenantId, UUID caseId);
}
