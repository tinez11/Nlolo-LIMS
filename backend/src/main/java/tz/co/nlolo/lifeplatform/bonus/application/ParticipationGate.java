package tz.co.nlolo.lifeplatform.bonus.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyNotFoundException;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;

import java.util.UUID;

/**
 * <b>Product first, bonus tables second</b> (plan §12, L6). Every policy event, every maturity and
 * every death limit on the platform asks bonus whether a policy is with-profits, so the answer comes
 * from policy and product alone -- tables every test class already has. Only a yes leads to a
 * {@code bonus.*} read, and only a class that issues a with-profits policy needs bonus V1.
 */
@Component("bonusParticipationGate")
class ParticipationGate {

    private final PolicyApi policyApi;
    private final ProductApi productApi;

    ParticipationGate(PolicyApi policyApi, ProductApi productApi) {
        this.policyApi = policyApi;
        this.productApi = productApi;
    }

    boolean participates(UUID productVersionId) {
        return productVersionId != null && productApi.resolveBonusPlan(productVersionId).participating();
    }

    /** Whether this policy's own version is with-profits; a policy that does not exist is not. */
    boolean participates(String policyNumber) {
        return participates(versionOf(policyNumber));
    }

    UUID versionOf(String policyNumber) {
        if (policyNumber == null) {
            return null;
        }
        try {
            return policyApi.getPolicy(policyNumber).productVersionId();
        } catch (PolicyNotFoundException e) {
            return null;
        }
    }
}
