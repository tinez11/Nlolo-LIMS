package tz.co.nlolo.lifeplatform.billing.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.billing.domain.PremiumCredit;

import java.util.List;
import java.util.UUID;

public interface PremiumCreditRepository extends JpaRepository<PremiumCredit, UUID> {

    /**
     * A list rather than an Optional although ux_premium_credit_per_member makes it at most
     * one: a caller asking "has this member been refunded" reads better as an emptiness check
     * than as an Optional, and the test that proves a redelivery credits once asserts the size.
     */
    List<PremiumCredit> findByTenantIdAndPolicyMemberId(UUID tenantId, UUID policyMemberId);

    List<PremiumCredit> findByTenantIdAndPolicyNumber(UUID tenantId, String policyNumber);

    List<PremiumCredit> findByTenantIdAndOriginalInvoiceId(UUID tenantId, UUID originalInvoiceId);
}
