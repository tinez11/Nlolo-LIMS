package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.underwriting.domain.UnitLinkedChoiceEntity;

import java.util.UUID;

public interface UnitLinkedChoiceRepository extends JpaRepository<UnitLinkedChoiceEntity, UUID> {}
