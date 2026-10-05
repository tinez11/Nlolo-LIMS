package tz.co.nlolo.lifeplatform.unitlinked.application;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnitLinkedChoice;
import tz.co.nlolo.lifeplatform.unitlinked.api.PremiumSplitView;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedStateException;
import tz.co.nlolo.lifeplatform.unitlinked.domain.Fund;
import tz.co.nlolo.lifeplatform.unitlinked.domain.PremiumSplit;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.FundRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.PremiumSplitRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Premium redirection (U2, spec §4): the split as a history. Recorded by one staff member and audited by its own row
 * (who, when); it applies to premiums RECEIVED after it -- a premium already waiting was split when it arrived, and
 * nothing already bought moves (that is a switch).
 */
@Component
class PremiumSplits {

    /** U1's issue writes the first split; its rows take effect from the epoch, before any premium could arrive. */
    static final Instant FROM_ISSUE = Instant.parse("1970-01-01T00:00:00Z");
    private static final Set<String> IN_FORCE = Set.of("ACTIVE", "REINSTATED");

    private final PremiumSplitRepository splits;
    private final FundRepository funds;
    private final PolicyApi policyApi;
    private final ProductApi productApi;
    private final UnitLedger ledger;
    private final Clock clock;

    PremiumSplits(PremiumSplitRepository splits, FundRepository funds, PolicyApi policyApi, ProductApi productApi,
                  @org.springframework.context.annotation.Lazy UnitLedger ledger, @Qualifier("unitLinkedClock") Clock clock) {
        this.splits = splits;
        this.funds = funds;
        this.policyApi = policyApi;
        this.productApi = productApi;
        this.ledger = ledger;
        this.clock = clock;
    }

    /** The split in force when the money was received, fund by fund in a stable order. */
    List<PremiumSplit.Share> splitAt(String policyNumber, Instant receivedAt) {
        return splits.findFirstByTenantIdAndPolicyNumberAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                TenantContext.get(), policyNumber, receivedAt)
            .orElseThrow(() -> new IllegalStateException("Unit-linked policy " + policyNumber + " has no premium split"))
            .getShares().stream().sorted(Comparator.comparing(s -> s.getFundId().toString())).toList();
    }

    /** The split written at issue, from the case's choice. Idempotent. */
    void recordAtIssue(String policyNumber, List<PremiumSplit.Share> shares) {
        UUID tenantId = TenantContext.get();
        if (!splits.existsByTenantIdAndPolicyNumber(tenantId, policyNumber)) {
            splits.save(new PremiumSplit(tenantId, policyNumber, FROM_ISSUE, UnitLedger.SYSTEM, clock.instant(), shares));
        }
    }

    @Transactional
    PremiumSplitView redirect(String policyNumber, List<UnitLinkedChoice.Split> split, String by) {
        PolicyView policy = policyApi.getPolicy(policyNumber);
        if (!"UNIT_LINKED".equals(policy.productCategory())) {
            throw new UnitLinkedStateException("Policy " + policyNumber + " is not unit-linked; it has no premium split");
        }
        if (!IN_FORCE.contains(policy.status().name()) || ledger.isFrozen(policyNumber)) {
            throw new UnitLinkedStateException("Policy " + policyNumber + " is not in force; its premium split cannot change");
        }
        List<PremiumSplit.Share> shares = SplitRules.resolve(split, productApi.resolveUnitLinkedPlan(policy.productVersionId()), funds);
        Instant now = clock.instant();
        return view(splits.save(new PremiumSplit(TenantContext.get(), policyNumber, now, by, now, shares)));
    }

    @Transactional(readOnly = true)
    List<PremiumSplitView> history(String policyNumber) {
        return splits.findByTenantIdAndPolicyNumberOrderByEffectiveFromDesc(TenantContext.get(), policyNumber).stream()
            .map(this::view).toList();
    }

    private PremiumSplitView view(PremiumSplit s) {
        UUID tenantId = TenantContext.get();
        return new PremiumSplitView(s.getSplitId(), s.getPolicyNumber(), s.getEffectiveFrom(),
            s.getShares().stream().map(sh -> new PremiumSplitView.Share(
                funds.findByTenantIdAndFundId(tenantId, sh.getFundId()).map(Fund::getCode).orElse("?"), sh.getPercent()))
                .sorted(Comparator.comparing(PremiumSplitView.Share::fundCode)).toList(),
            s.getRecordedBy(), s.getRecordedAt());
    }
}
