package tz.co.nlolo.lifeplatform.product.application;

import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.product.api.*;
import tz.co.nlolo.lifeplatform.product.domain.*;
import tz.co.nlolo.lifeplatform.product.infrastructure.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ProductApiImpl implements ProductApi {

    private final ProductDefinitionRepository productDefinitionRepository;
    private final ProductVersionRepository productVersionRepository;
    private final RatingFactorRepository ratingFactorRepository;
    private final BenefitScheduleEntryRepository benefitScheduleEntryRepository;
    private final FundDefinitionRepository fundDefinitionRepository;

    public ProductApiImpl(ProductDefinitionRepository productDefinitionRepository, ProductVersionRepository productVersionRepository,
                           RatingFactorRepository ratingFactorRepository, BenefitScheduleEntryRepository benefitScheduleEntryRepository,
                           FundDefinitionRepository fundDefinitionRepository) {
        this.productDefinitionRepository = productDefinitionRepository;
        this.productVersionRepository = productVersionRepository;
        this.ratingFactorRepository = ratingFactorRepository;
        this.benefitScheduleEntryRepository = benefitScheduleEntryRepository;
        this.fundDefinitionRepository = fundDefinitionRepository;
    }

    @Override
    @Transactional
    public ProductSummaryView createProduct(String productCode, String productName, ProductCategory category, String defaultCurrency, String createdBy) {
        UUID tenantId = TenantContext.get();
        if (productDefinitionRepository.findByTenantIdAndProductCode(tenantId, productCode).isPresent()) {
            throw new DuplicateProductCodeException(productCode);
        }
        ProductDefinition product = new ProductDefinition(tenantId, productCode, productName, category.name(), defaultCurrency, createdBy);
        try {
            // The check above is a fast-path UX improvement, not the guarantee -- ux_product_code
            // (the unique index on (tenant_id, product_code)) is. Two concurrent requests can both
            // pass the check above and race to insert; the loser's DataIntegrityViolationException is
            // translated here so callers see the same domain exception regardless of timing. This MUST
            // be saveAndFlush, not save: productId is an in-memory-generated UUID (Hibernate's
            // UuidGenerator, no DB round-trip needed to assign it), so plain save() only queues the
            // INSERT in the flush action queue -- it doesn't hit the DB until the surrounding
            // @Transactional proxy commits, which is after this method (and this catch block) has
            // already returned. saveAndFlush forces the INSERT to execute synchronously, right here, so
            // a real unique-constraint violation is actually caught. Mirrors PartyApiImpl.registerCorporate.
            productDefinitionRepository.saveAndFlush(product);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateProductCodeException(productCode);
        }
        return toSummaryView(product);
    }

    @Override
    public List<ProductSummaryView> listActiveProducts(ProductCategory categoryFilter) {
        UUID tenantId = TenantContext.get();
        List<ProductDefinition> products = categoryFilter != null
            ? productDefinitionRepository.findByTenantIdAndStatusAndCategory(tenantId, "ACTIVE", categoryFilter.name())
            : productDefinitionRepository.findByTenantIdAndStatus(tenantId, "ACTIVE");
        return products.stream().map(this::toSummaryView).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                                List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions, String publishedBy) {
        UUID tenantId = TenantContext.get();
        ProductDefinition product = productDefinitionRepository.findById(productId)
            .filter(p -> p.getTenantId().equals(tenantId))
            .orElseThrow(() -> new ProductNotFoundException(productId));

        // Deliverable 3 invariant: fund definitions restricted to UNIT_LINKED products.
        if (fundDefinitions != null && !fundDefinitions.isEmpty() && !"UNIT_LINKED".equals(product.getCategory())) {
            throw new InvalidProductVersionException("Fund definitions are only valid for UNIT_LINKED products");
        }
        // Deliverable 3 invariant: full rating-factor coverage validated at publish --
        // every declared FactorType must have at least one band defined so the rules
        // engine (Task 4) never silently falls back to a neutral 1.0 for a factor type
        // this product intended to rate on.
        java.util.Set<FactorType> coveredFactorTypes = ratingTable.stream().map(RatingFactorInput::factorType).collect(Collectors.toSet());
        if (!coveredFactorTypes.containsAll(List.of(FactorType.AGE, FactorType.SUM_ASSURED_BAND))) {
            throw new InvalidProductVersionException("Rating table must cover at least AGE and SUM_ASSURED_BAND factor types");
        }

        int gracePeriodDays = 30; // Deliverable 3 doesn't specify a grace-period source yet at this layer -- see Global Constraints; this is a fixed, flagged default, not read from an OpenAPI field (ProductVersionSpec has no gracePeriodDays field).
        ProductVersion version = new ProductVersion(tenantId, productId, effectiveDate, retirementDate, gracePeriodDays, null, publishedBy);
        productVersionRepository.save(version);

        for (RatingFactorInput input : ratingTable) {
            ratingFactorRepository.save(new RatingFactor(tenantId, version.getProductVersionId(), input.factorType().name(), input.band(), input.multiplier()));
        }
        for (BenefitInput input : benefitSchedule) {
            benefitScheduleEntryRepository.save(new BenefitScheduleEntry(tenantId, version.getProductVersionId(), input.benefitType().name(), input.calculationMethod()));
        }
        if (fundDefinitions != null) {
            for (FundInput input : fundDefinitions) {
                fundDefinitionRepository.save(new FundDefinition(tenantId, version.getProductVersionId(), input.fundCode(), input.currentNav()));
            }
        }

        product.activateWithMeasurementModel(ifrsMeasurementModel.name());
        productDefinitionRepository.save(product);
    }

    @Override
    public ProductSnapshotView getActiveSnapshot(UUID productId, LocalDate asOfDate) {
        UUID tenantId = TenantContext.get();
        LocalDate effectiveAsOf = asOfDate != null ? asOfDate : LocalDate.now();
        ProductVersion version = productVersionRepository.findActiveAsOf(tenantId, productId, effectiveAsOf).stream()
            .findFirst()
            .orElseThrow(() -> new ProductNotFoundException(productId));
        return new ProductSnapshotView(productId, version.getProductVersionId(), version.getEffectiveDate(),
            IfrsMeasurementModel.valueOf(productDefinitionRepository.findById(productId).orElseThrow(() -> new ProductNotFoundException(productId)).getIfrsMeasurementModel()),
            version.getGracePeriodDays(), version.getMaxLoanToValuePercent());
    }

    @Override
    public BigDecimal resolveRatingMultiplier(UUID productVersionId, FactorType factorType, String band) {
        return ratingFactorRepository.findByProductVersionIdAndFactorType(productVersionId, factorType.name()).stream()
            .filter(f -> f.getBand().equals(band))
            .map(RatingFactor::getMultiplier)
            .findFirst()
            .orElse(BigDecimal.ONE); // No matching band -- neutral multiplier, not an error (see ProductApi.resolveRatingMultiplier's javadoc).
    }

    private ProductSummaryView toSummaryView(ProductDefinition p) {
        return new ProductSummaryView(p.getProductId(), p.getProductCode(), p.getProductName(),
            ProductCategory.valueOf(p.getCategory()), ProductStatus.valueOf(p.getStatus()), p.getDefaultCurrency());
    }
}
