package tz.co.nlolo.lifeplatform.payment.infrastructure;

import tz.co.nlolo.lifeplatform.payment.domain.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentTransactionRepository
        extends JpaRepository<PaymentTransaction, PaymentTransaction.PaymentTransactionId> {
    Optional<PaymentTransaction> findByPaymentTransactionIdAndTenantId(UUID paymentTransactionId, UUID tenantId);
    Optional<PaymentTransaction> findByIdempotencyKeyAndTenantId(String idempotencyKey, UUID tenantId);
    Optional<PaymentTransaction> findByGatewayReferenceAndTenantId(String gatewayReference, UUID tenantId);
}
