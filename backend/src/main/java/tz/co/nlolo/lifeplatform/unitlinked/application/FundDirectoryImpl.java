package tz.co.nlolo.lifeplatform.unitlinked.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.product.api.FundDirectory;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.FundRepository;

import java.util.Optional;

/** The register, as product's publish sees it (plan R1): whether a fund exists, is open, and what it prices in. */
@Component
class FundDirectoryImpl implements FundDirectory {

    private final FundRepository funds;

    FundDirectoryImpl(FundRepository funds) {
        this.funds = funds;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FundSummary> find(String fundCode) {
        if (fundCode == null) {
            return Optional.empty();
        }
        return funds.findByTenantIdAndCode(TenantContext.get(), fundCode.trim().toUpperCase())
            .map(f -> new FundSummary(f.getCode(), f.getCurrency(), f.isOpen()));
    }
}
