package tz.co.nlolo.lifeplatform.billing.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.billing.domain.PremiumReceipt;

import java.util.List;
import java.util.UUID;

public interface PremiumReceiptRepository extends JpaRepository<PremiumReceipt, UUID> {

    List<PremiumReceipt> findByTenantIdAndPolicyNumberOrderByReceivedAtAsc(UUID tenantId, String policyNumber);

    boolean existsByTenantIdAndInvoiceIdAndPaymentReference(UUID tenantId, UUID invoiceId, String paymentReference);
}
