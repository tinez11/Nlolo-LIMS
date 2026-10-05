package tz.co.nlolo.lifeplatform.unitlinked.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedValuation;
import tz.co.nlolo.lifeplatform.unitlinked.domain.Fund;
import tz.co.nlolo.lifeplatform.unitlinked.domain.FundLiability;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.FundLiabilityRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.FundPriceRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.FundRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Each fund's liability as the last pricing run carried it -- exactly units in issue x that run's price (plan D1) --
 * for finaccounting to compare with 2150 (plan R10).
 */
@Component
class Reconciliation implements UnitLinkedValuation {

    private final FundLiabilityRepository liabilities;
    private final FundRepository funds;
    private final FundPriceRepository prices;

    Reconciliation(FundLiabilityRepository liabilities, FundRepository funds, FundPriceRepository prices) {
        this.liabilities = liabilities;
        this.funds = funds;
        this.prices = prices;
    }

    @Override
    @Transactional(readOnly = true)
    public List<FundValuation> valuations() {
        UUID tenantId = TenantContext.get();
        Map<UUID, Fund> byId = funds.findByTenantIdOrderByCode(tenantId).stream()
            .collect(Collectors.toMap(Fund::getFundId, f -> f));
        return liabilities.findByTenantId(tenantId).stream().map((FundLiability l) -> {
            Fund fund = byId.get(l.getFundId());
            var price = prices.findByTenantIdAndPriceId(tenantId, l.getPriceId()).orElseThrow();
            return new FundValuation(fund.getCode(), l.getUnitsInIssue(), price.getPrice(), price.getValuationDate(),
                l.getCarried(), fund.getCurrency());
        }).sorted(java.util.Comparator.comparing(FundValuation::fundCode)).toList();
    }
}
