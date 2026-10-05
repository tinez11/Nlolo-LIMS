package tz.co.nlolo.lifeplatform.unitlinked.application;

import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan;
import tz.co.nlolo.lifeplatform.underwriting.api.UnitLinkedChoice;
import tz.co.nlolo.lifeplatform.unitlinked.domain.Fund;
import tz.co.nlolo.lifeplatform.unitlinked.domain.PremiumSplit;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.FundRepository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Where a premium (or a switch's proceeds) may go (U2): funds the policy's version offers, open to new money, in whole
 * percents totalling 100. The same rules, in the same words, as underwriting's UnitLinkedChoices applies at the sale --
 * kept here rather than shared because unitlinked may not call underwriting's application layer.
 */
final class SplitRules {

    private SplitRules() {}

    static List<PremiumSplit.Share> resolve(List<UnitLinkedChoice.Split> split, UnitLinkedPlan plan, FundRepository funds) {
        if (split == null || split.isEmpty()) {
            throw new IllegalArgumentException("A unit-linked split says how each premium is divided across the funds");
        }
        List<PremiumSplit.Share> shares = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int total = 0;
        for (UnitLinkedChoice.Split s : split) {
            String code = s.fundCode() == null ? "" : s.fundCode().trim().toUpperCase();
            if (!plan.offersFund(code)) {
                throw new IllegalArgumentException("Fund " + code + " is not offered by this product; it offers "
                    + String.join(", ", plan.fundCodes()));
            }
            if (!seen.add(code)) {
                throw new IllegalArgumentException("Fund " + code + " appears twice in the split");
            }
            if (s.percent() < 1 || s.percent() > 100) {
                throw new IllegalArgumentException("Each fund's share is a whole percent from 1 to 100");
            }
            Fund fund = funds.findByTenantIdAndCode(TenantContext.get(), code)
                .orElseThrow(() -> new IllegalArgumentException("Fund " + code + " is not in the fund register"));
            if (!fund.isOpen()) {
                throw new IllegalArgumentException("Fund " + code + " is closed and takes no new money");
            }
            shares.add(new PremiumSplit.Share(fund.getFundId(), s.percent()));
            total += s.percent();
        }
        if (total != 100) {
            throw new IllegalArgumentException("The fund split totals " + total + "%; it must total 100%");
        }
        return shares;
    }
}
