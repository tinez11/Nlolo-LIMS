package tz.co.nlolo.lifeplatform.product.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan;
import tz.co.nlolo.lifeplatform.product.domain.UnitLinkedAllocationBandEntity;
import tz.co.nlolo.lifeplatform.product.domain.UnitLinkedFundEntity;
import tz.co.nlolo.lifeplatform.product.domain.UnitLinkedMortalityEntity;
import tz.co.nlolo.lifeplatform.product.domain.UnitLinkedPremiumMinimumEntity;
import tz.co.nlolo.lifeplatform.product.domain.UnitLinkedTermsEntity;
import tz.co.nlolo.lifeplatform.product.infrastructure.UnitLinkedAllocationBandRepository;
import tz.co.nlolo.lifeplatform.product.infrastructure.UnitLinkedFundRepository;
import tz.co.nlolo.lifeplatform.product.infrastructure.UnitLinkedMortalityRepository;
import tz.co.nlolo.lifeplatform.product.infrastructure.UnitLinkedPremiumMinimumRepository;
import tz.co.nlolo.lifeplatform.product.infrastructure.UnitLinkedTermsRepository;

import java.util.UUID;

/**
 * A UNIT_LINKED version's five tables (V25), written and read as one -- FuneralTermsStore's arrangement, for its
 * reason: ProductApiImpl's constructor does not grow by five more repositories. Called only from inside
 * ProductApiImpl's transactions, and read only after the category says UNIT_LINKED, so no test class that never
 * publishes one needs V25.
 */
@Component
class UnitLinkedTermsStore {

    private final UnitLinkedTermsRepository terms;
    private final UnitLinkedFundRepository funds;
    private final UnitLinkedAllocationBandRepository bands;
    private final UnitLinkedMortalityRepository mortality;
    private final UnitLinkedPremiumMinimumRepository minimums;

    UnitLinkedTermsStore(UnitLinkedTermsRepository terms, UnitLinkedFundRepository funds,
                         UnitLinkedAllocationBandRepository bands, UnitLinkedMortalityRepository mortality,
                         UnitLinkedPremiumMinimumRepository minimums) {
        this.terms = terms;
        this.funds = funds;
        this.bands = bands;
        this.mortality = mortality;
        this.minimums = minimums;
    }

    /** Nothing for a version that is not unit-linked -- its absence IS that. */
    void persist(UUID tenantId, UUID productVersionId, UnitLinkedPlan plan) {
        if (plan == null || !plan.unitLinked()) {
            return;
        }
        // The terms row first: every other row references it.
        terms.saveAndFlush(new UnitLinkedTermsEntity(tenantId, productVersionId, plan));
        for (String code : plan.fundCodes()) {
            funds.save(new UnitLinkedFundEntity(tenantId, productVersionId, code));
        }
        for (UnitLinkedPlan.AllocationBand band : plan.allocationBands()) {
            bands.save(new UnitLinkedAllocationBandEntity(tenantId, productVersionId, band));
        }
        for (UnitLinkedPlan.MortalityRow row : plan.mortality()) {
            mortality.save(new UnitLinkedMortalityEntity(tenantId, productVersionId, row));
        }
        for (UnitLinkedPlan.PremiumMinimum minimum : plan.premiumMinimums()) {
            minimums.save(new UnitLinkedPremiumMinimumEntity(tenantId, productVersionId, minimum));
        }
    }

    UnitLinkedPlan read(UUID productVersionId) {
        return terms.findById(productVersionId)
            .map(t -> t.toPlan(
                funds.findByProductVersionIdOrderByFundCode(productVersionId).stream().map(UnitLinkedFundEntity::getFundCode).toList(),
                bands.findByProductVersionIdOrderByFromYear(productVersionId).stream().map(UnitLinkedAllocationBandEntity::toBand).toList(),
                mortality.findByProductVersionIdOrderBySexAscAgeFromAsc(productVersionId).stream().map(UnitLinkedMortalityEntity::toRow).toList(),
                minimums.findByProductVersionIdOrderByFrequency(productVersionId).stream().map(UnitLinkedPremiumMinimumEntity::toMinimum).toList()))
            .orElse(UnitLinkedPlan.none());
    }
}
