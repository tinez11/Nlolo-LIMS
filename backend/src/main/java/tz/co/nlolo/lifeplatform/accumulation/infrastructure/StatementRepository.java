package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.accumulation.domain.Statement;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StatementRepository extends JpaRepository<Statement, UUID> {
    List<Statement> findByPolicyNumberOrderByPeriodToDescGeneratedAtDesc(String policyNumber);
    Optional<Statement> findByPolicyNumberAndPeriodFromAndPeriodToAndLastSeq(String policyNumber, LocalDate from,
                                                                            LocalDate to, int lastSeq);
}
