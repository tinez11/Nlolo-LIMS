package tz.co.nlolo.lifeplatform.product.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.product.domain.PayoutScheduleRow;

import java.util.List;
import java.util.UUID;

public interface PayoutScheduleRowRepository extends JpaRepository<PayoutScheduleRow, UUID> {

    /** In authoring order, because a row's index IS the key a policy's instalments carry. */
    List<PayoutScheduleRow> findByProductVersionIdOrderByRowOrder(UUID productVersionId);
}
