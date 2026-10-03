package tz.co.nlolo.lifeplatform.annuity.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.annuity.domain.AnnuityContract;

/** Keyed by the globally unique policy number, so no query here needs a tenant filter beyond RLS. */
public interface AnnuityContractRepository extends JpaRepository<AnnuityContract, String> {}
