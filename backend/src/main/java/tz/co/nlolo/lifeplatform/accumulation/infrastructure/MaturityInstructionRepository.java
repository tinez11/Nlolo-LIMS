package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.accumulation.domain.MaturityInstruction;

import java.util.Optional;
import java.util.UUID;

public interface MaturityInstructionRepository extends JpaRepository<MaturityInstruction, UUID> {
    Optional<MaturityInstruction> findByPeriodIdAndSupersededAtIsNull(UUID periodId);
}
