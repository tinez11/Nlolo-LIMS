package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.underwriting.domain.AnnuityChoiceEntity;

import java.util.UUID;

public interface AnnuityChoiceRepository extends JpaRepository<AnnuityChoiceEntity, UUID> {}
