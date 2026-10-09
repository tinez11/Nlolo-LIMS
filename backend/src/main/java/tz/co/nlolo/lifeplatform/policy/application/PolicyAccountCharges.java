package tz.co.nlolo.lifeplatform.policy.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.policy.api.PolicyAccountChargeApi;
import tz.co.nlolo.lifeplatform.policy.domain.PolicyAccountCharge;
import tz.co.nlolo.lifeplatform.policy.infrastructure.PolicyAccountChargeRepository;
import tz.co.nlolo.lifeplatform.policy.infrastructure.PolicyRepository;
import tz.co.nlolo.lifeplatform.product.api.AccountChargeApi;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;

import java.util.List;
import java.util.UUID;

/** See {@link PolicyAccountChargeApi}. */
@Service
public class PolicyAccountCharges implements PolicyAccountChargeApi {

    private final PolicyAccountChargeRepository charges;
    private final PolicyRepository policies;
    private final ProductApi productApi;
    private final AccountChargeApi accountChargeApi;

    public PolicyAccountCharges(PolicyAccountChargeRepository charges, PolicyRepository policies, ProductApi productApi,
                                AccountChargeApi accountChargeApi) {
        this.charges = charges;
        this.policies = policies;
        this.productApi = productApi;
        this.accountChargeApi = accountChargeApi;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> accountCharges(String policyNumber) {
        return charges.forPolicy(TenantContext.get(), policyNumber).stream().map(PolicyAccountCharge::getChargeId).toList();
    }

    @Override
    @Transactional
    public void assignAccountCharges(String policyNumber, List<UUID> chargeIds) {
        UUID tenantId = TenantContext.get();
        var policy = policies.findByPolicyNumberAndTenantId(policyNumber, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("No policy " + policyNumber));
        List<UUID> ids = chargeIds == null ? List.of() : chargeIds.stream().distinct().toList();
        if (!ids.isEmpty()) {
            if (!productApi.resolveAccumulationPlan(policy.getProductVersionId()).isAccount()
                    || productApi.resolveDepositPlan(policy.getProductVersionId()).isDeposit()) {
                throw new IllegalArgumentException("Account charges are chosen only for a savings product that keeps an"
                    + " account -- not a fixed-term deposit, which is priced by its rate grid");
            }
            accountChargeApi.requireChoosable(ids);
        }
        charges.deleteAll(charges.forPolicy(tenantId, policyNumber));
        charges.flush();
        ids.forEach(id -> charges.save(new PolicyAccountCharge(policyNumber, id, tenantId)));
    }
}
