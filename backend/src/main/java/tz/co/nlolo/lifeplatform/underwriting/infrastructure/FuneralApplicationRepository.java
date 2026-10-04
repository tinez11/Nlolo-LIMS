package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.underwriting.domain.FuneralApplicationEntity;

import java.util.UUID;

public interface FuneralApplicationRepository extends JpaRepository<FuneralApplicationEntity, UUID> {}
