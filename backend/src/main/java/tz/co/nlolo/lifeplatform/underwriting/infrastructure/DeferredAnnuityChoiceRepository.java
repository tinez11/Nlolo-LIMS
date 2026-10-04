package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.underwriting.domain.DeferredAnnuityChoiceEntity;

import java.util.UUID;

public interface DeferredAnnuityChoiceRepository extends JpaRepository<DeferredAnnuityChoiceEntity, UUID> {}
