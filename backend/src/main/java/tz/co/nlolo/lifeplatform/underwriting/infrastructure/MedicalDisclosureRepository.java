package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.underwriting.domain.MedicalDisclosure;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MedicalDisclosureRepository extends JpaRepository<MedicalDisclosure, UUID> {}
