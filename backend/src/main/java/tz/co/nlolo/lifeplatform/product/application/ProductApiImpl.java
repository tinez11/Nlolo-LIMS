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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ProductApiImpl implements ProductApi {

    private final ProductDefinitionRepository productDefinitionRepository;
    private final ProductVersionRepository productVersionRepository;
    private final RatingFactorRepository ratingFactorRepository;
    private final BenefitScheduleEntryRepository benefitScheduleEntryRepository;
    private final UnitLinkedTermsStore unitLinkedTermsStore;
    private final org.springframework.beans.factory.ObjectProvider<FundDirectory> fundDirectory;
    private final BaseRateRepository baseRateRepository;
    private final CashValueEntryRepository cashValueEntryRepository;
    private final CashValueConfigRepository cashValueConfigRepository;
    private final PayoutScheduleRowRepository payoutScheduleRowRepository;
    private final VersionPayoutTermsRepository versionPayoutTermsRepository;
    private final VersionAccumulationTermsRepository versionAccumulationTermsRepository;
    private final AccumulationChargeRepository accumulationChargeRepository;
    private final DepositRateRepository depositRateRepository;
    private final VersionBonusTermsRepository versionBonusTermsRepository;
    private final BonusSurrenderEntryRepository bonusSurrenderEntryRepository;
    private final VersionAnnuityTermsRepository versionAnnuityTermsRepository;
    private final AnnuityFormRepository annuityFormRepository;
    private final AnnuityRateEntryRepository annuityRateEntryRepository;
    private final AnnuityFrequencyEntryRepository annuityFrequencyEntryRepository;
    private final VersionVestingTermsRepository versionVestingTermsRepository;
    private final FuneralTermsStore funeralTermsStore;

    public ProductApiImpl(ProductDefinitionRepository productDefinitionRepository, ProductVersionRepository productVersionRepository,
                           RatingFactorRepository ratingFactorRepository, BenefitScheduleEntryRepository benefitScheduleEntryRepository,
                           BaseRateRepository baseRateRepository,
                           CashValueEntryRepository cashValueEntryRepository, CashValueConfigRepository cashValueConfigRepository,
                           PayoutScheduleRowRepository payoutScheduleRowRepository,
                           VersionPayoutTermsRepository versionPayoutTermsRepository,
                           VersionAccumulationTermsRepository versionAccumulationTermsRepository,
                           AccumulationChargeRepository accumulationChargeRepository,
                           DepositRateRepository depositRateRepository,
                           VersionBonusTermsRepository versionBonusTermsRepository,
                           BonusSurrenderEntryRepository bonusSurrenderEntryRepository,
                           VersionAnnuityTermsRepository versionAnnuityTermsRepository,
                           AnnuityFormRepository annuityFormRepository,
                           AnnuityRateEntryRepository annuityRateEntryRepository,
                           AnnuityFrequencyEntryRepository annuityFrequencyEntryRepository,
                           VersionVestingTermsRepository versionVestingTermsRepository,
                           FuneralTermsStore funeralTermsStore, UnitLinkedTermsStore unitLinkedTermsStore,
                           org.springframework.beans.factory.ObjectProvider<FundDirectory> fundDirectory) {
        this.unitLinkedTermsStore = unitLinkedTermsStore;
        this.fundDirectory = fundDirectory;
        this.versionVestingTermsRepository = versionVestingTermsRepository;
        this.funeralTermsStore = funeralTermsStore;
        this.versionAnnuityTermsRepository = versionAnnuityTermsRepository;
        this.annuityFormRepository = annuityFormRepository;
        this.annuityRateEntryRepository = annuityRateEntryRepository;
        this.annuityFrequencyEntryRepository = annuityFrequencyEntryRepository;
        this.depositRateRepository = depositRateRepository;
        this.versionBonusTermsRepository = versionBonusTermsRepository;
        this.bonusSurrenderEntryRepository = bonusSurrenderEntryRepository;
        this.versionAccumulationTermsRepository = versionAccumulationTermsRepository;
        this.accumulationChargeRepository = accumulationChargeRepository;
        this.productDefinitionRepository = productDefinitionRepository;
        this.productVersionRepository = productVersionRepository;
        this.ratingFactorRepository = ratingFactorRepository;
        this.benefitScheduleEntryRepository = benefitScheduleEntryRepository;
        this.baseRateRepository = baseRateRepository;
        this.cashValueEntryRepository = cashValueEntryRepository;
        this.cashValueConfigRepository = cashValueConfigRepository;
        this.payoutScheduleRowRepository = payoutScheduleRowRepository;
        this.versionPayoutTermsRepository = versionPayoutTermsRepository;
    }

    @Override
    @Transactional
    public ProductSummaryView createProduct(String productCode, String productName, ProductCategory category, String defaultCurrency, String createdBy) {
        return createProduct(productCode, productName, category, PortfolioCode.defaultFor(category), defaultCurrency, createdBy);
    }

    @Override
    @Transactional
    public ProductSummaryView createProduct(String productCode, String productName, ProductCategory category,
                                            PortfolioCode portfolioCode, String defaultCurrency, String createdBy) {
        UUID tenantId = TenantContext.get();
        if (productDefinitionRepository.findByTenantIdAndProductCode(tenantId, productCode).isPresent()) {
            throw new DuplicateProductCodeException(productCode);
        }
        ProductDefinition product = new ProductDefinition(tenantId, productCode, productName, category.name(),
            (portfolioCode != null ? portfolioCode : PortfolioCode.defaultFor(category)).name(), defaultCurrency, createdBy);
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
            // ONLY ux_product_code means a duplicate. This catch used to claim every
            // integrity violation was one, so a CHECK violation on category surfaced as
            // "a product with code X already exists" against a code that did not exist --
            // observed for real while adding CREDIT_LIFE, and it cost real diagnosis time.
            // Same bug class as M7's onboardAgent, which reported a value-too-long as a
            // duplicate licence.
            //
            // Anything else is rethrown unchanged. A 500 carrying Postgres's own message
            // is worth far more than a confident, wrong 409: the first sends you to the
            // constraint that actually failed, the second sends you hunting for a row that
            // is not there. Proven by ProductIntegrityViolationTest, which runs against a
            // schema that predates the CREDIT_LIFE category.
            if (violatesConstraint(e, "ux_product_code")) {
                throw new DuplicateProductCodeException(productCode);
            }
            throw e;
        }
        return toSummaryView(product);
    }

    /**
     * Whether an integrity violation was caused by the named constraint.
     *
     * <p>Walks the cause chain because the constraint name appears on the Postgres-level
     * cause, not on Spring's wrapper.
     */
    private static boolean violatesConstraint(DataIntegrityViolationException e, String constraintName) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains(constraintName)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<ProductSummaryView> listActiveProducts(ProductCategory categoryFilter) {
        UUID tenantId = TenantContext.get();
        List<ProductDefinition> products = categoryFilter != null
            ? productDefinitionRepository.findByTenantIdAndStatusAndCategory(tenantId, "ACTIVE", categoryFilter.name())
            : productDefinitionRepository.findByTenantIdAndStatus(tenantId, "ACTIVE");
        return products.stream().map(this::toSummaryView).collect(Collectors.toList());
    }

    /**
     * No category filter, on purpose: a draft is an unfinished authoring task rather than a
     * catalogue entry, and there are only ever a handful. Filtering a to-do list by product
     * category answers a question nobody has.
     */
    @Override
    public List<ProductSummaryView> listDraftProducts() {
        return productDefinitionRepository.findByTenantIdAndStatus(TenantContext.get(), "DRAFT")
            .stream().map(this::toSummaryView).collect(Collectors.toList());
    }

    /**
     * Any status, deliberately. A retired product is exactly the case that needs this: it is
     * gone from the catalogue while the policies and the underwriting cases that reference it
     * are still on screen, so filtering by ACTIVE here would print a uuid on precisely the
     * records whose history someone is trying to read.
     */
    @Override
    public ProductSummaryView getProduct(UUID productId) {
        return productDefinitionRepository.findByTenantIdAndProductId(TenantContext.get(), productId)
            .map(this::toSummaryView)
            .orElseThrow(() -> new ProductNotFoundException(productId));
    }

    @Override
    public List<ProductVersionSummaryView> listVersions(UUID productId) {
        UUID tenantId = TenantContext.get();
        productDefinitionRepository.findByTenantIdAndProductId(tenantId, productId)
            .orElseThrow(() -> new ProductNotFoundException(productId));
        UUID current = productVersionRepository.findActiveAsOf(tenantId, productId, LocalDate.now()).stream()
            .findFirst().map(ProductVersion::getProductVersionId).orElse(null);
        return productVersionRepository.findByTenantIdAndProductIdOrderByCreatedAtDesc(tenantId, productId).stream()
            .map(v -> new ProductVersionSummaryView(v.getProductVersionId(), v.getEffectiveDate(), v.getRetirementDate(),
                v.getProductVersionId().equals(current), v.getCreatedAt(), v.getCreatedBy(), v.getGracePeriodDays(),
                v.getExpectedProfitabilityBucket(), v.getMeasurementModelOverride(), v.getSurvivalInvestmentComponentPercent()))
            .toList();
    }

    /**
     * The two convenience overloads, each carrying {@code @Transactional} itself.
     *
     * <p>They live here rather than as {@code default} methods on the interface for the
     * reason recorded on {@link ProductApi#publishVersion}: a {@code default} method has
     * no annotation for the proxy to see, so its delegating call becomes a
     * self-invocation and the whole publish runs untransacted. With the annotation here,
     * the transaction is already open by the time the delegation happens, so the retire
     * -then-insert sequence below is atomic for every caller regardless of which
     * overload they use.
     */
    @Override
    @Transactional
    public void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                                List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule,
                                List<FundInput> fundDefinitions, TiraFiling tiraFiling, String publishedBy) {
        publishVersion(productId, ifrsMeasurementModel, effectiveDate, retirementDate, ratingTable, benefitSchedule,
            fundDefinitions, List.of(), EligibilityBounds.none(), tiraFiling, publishedBy);
    }

    @Override
    @Transactional
    public void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                                List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule,
                                List<FundInput> fundDefinitions, List<BaseRateInput> baseRates, TiraFiling tiraFiling, String publishedBy) {
        publishVersion(productId, ifrsMeasurementModel, effectiveDate, retirementDate, ratingTable, benefitSchedule,
            fundDefinitions, baseRates, EligibilityBounds.none(), tiraFiling, publishedBy);
    }

    @Override
    @Transactional
    public void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                                List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions,
                                List<BaseRateInput> baseRates, EligibilityBounds bounds, TiraFiling tiraFiling, String publishedBy) {
        publishVersion(productId, ifrsMeasurementModel, effectiveDate, retirementDate, ratingTable, benefitSchedule,
            fundDefinitions, baseRates, bounds, FrequencyLoading.none(), tiraFiling, publishedBy);
    }

    @Override
    @Transactional
    public void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                                List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions,
                                List<BaseRateInput> baseRates, EligibilityBounds bounds, FrequencyLoading frequencyLoading, TiraFiling tiraFiling, String publishedBy) {
        publishVersion(productId, ifrsMeasurementModel, effectiveDate, retirementDate, ratingTable, benefitSchedule,
            fundDefinitions, baseRates, bounds, frequencyLoading, tiraFiling, CashValuePlan.none(), publishedBy);
    }

    @Override
    @Transactional
    public void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                                List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions,
                                List<BaseRateInput> baseRates, EligibilityBounds bounds, FrequencyLoading frequencyLoading,
                                TiraFiling tiraFiling, CashValuePlan cashValue, String publishedBy) {
        publishVersion(productId, ifrsMeasurementModel, effectiveDate, retirementDate, ratingTable, benefitSchedule,
            fundDefinitions, baseRates, bounds, frequencyLoading, tiraFiling, cashValue, PayoutPlan.none(), publishedBy);
    }

    @Override
    @Transactional
    public void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                                List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions,
                                List<BaseRateInput> baseRates, EligibilityBounds bounds, FrequencyLoading frequencyLoading,
                                TiraFiling tiraFiling, CashValuePlan cashValue, PayoutPlan payoutPlan, String publishedBy) {
        publishVersion(productId, ifrsMeasurementModel, effectiveDate, retirementDate, ratingTable, benefitSchedule,
            fundDefinitions, baseRates, bounds, frequencyLoading, tiraFiling, cashValue, payoutPlan,
            AccumulationPlan.none(), publishedBy);
    }

    @Override
    @Transactional
    public void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                                List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions,
                                List<BaseRateInput> baseRates, EligibilityBounds bounds, FrequencyLoading frequencyLoading,
                                TiraFiling tiraFiling, CashValuePlan cashValue, PayoutPlan payoutPlan,
                                AccumulationPlan accumulationPlan, String publishedBy) {
        publishVersion(productId, ifrsMeasurementModel, effectiveDate, retirementDate, ratingTable, benefitSchedule,
            fundDefinitions, baseRates, bounds, frequencyLoading, tiraFiling, cashValue, payoutPlan, accumulationPlan,
            DepositPlan.none(), publishedBy);
    }

    @Override
    @Transactional
    public void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                                List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions,
                                List<BaseRateInput> baseRates, EligibilityBounds bounds, FrequencyLoading frequencyLoading,
                                TiraFiling tiraFiling, CashValuePlan cashValue, PayoutPlan payoutPlan,
                                AccumulationPlan accumulationPlan, DepositPlan depositPlan, String publishedBy) {
        publishVersion(productId, ifrsMeasurementModel, effectiveDate, retirementDate, ratingTable, benefitSchedule,
            fundDefinitions, baseRates, bounds, frequencyLoading, tiraFiling, cashValue, payoutPlan, accumulationPlan,
            depositPlan, BonusPlan.none(), publishedBy);
    }

    @Override
    @Transactional
    public void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                                List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions,
                                List<BaseRateInput> baseRates, EligibilityBounds bounds, FrequencyLoading frequencyLoading,
                                TiraFiling tiraFiling, CashValuePlan cashValue, PayoutPlan payoutPlan,
                                AccumulationPlan accumulationPlan, DepositPlan depositPlan, BonusPlan bonusPlan,
                                String publishedBy) {
        publishVersion(productId, ifrsMeasurementModel, effectiveDate, retirementDate, ratingTable, benefitSchedule,
            fundDefinitions, baseRates, bounds, frequencyLoading, tiraFiling, cashValue, payoutPlan, accumulationPlan,
            depositPlan, bonusPlan, AnnuityPlan.none(), publishedBy);
    }

    @Override
    @Transactional
    public void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                                List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions,
                                List<BaseRateInput> baseRates, EligibilityBounds bounds, FrequencyLoading frequencyLoading,
                                TiraFiling tiraFiling, CashValuePlan cashValue, PayoutPlan payoutPlan,
                                AccumulationPlan accumulationPlan, DepositPlan depositPlan, BonusPlan bonusPlan,
                                AnnuityPlan annuityPlan, String publishedBy) {
        publishVersion(productId, ifrsMeasurementModel, effectiveDate, retirementDate, ratingTable, benefitSchedule,
            fundDefinitions, baseRates, bounds, frequencyLoading, tiraFiling, cashValue, payoutPlan, accumulationPlan,
            depositPlan, bonusPlan, annuityPlan, FuneralPlan.none(), publishedBy);
    }

    @Override
    @Transactional
    public void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                                List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions,
                                List<BaseRateInput> baseRates, EligibilityBounds bounds, FrequencyLoading frequencyLoading,
                                TiraFiling tiraFiling, CashValuePlan cashValue, PayoutPlan payoutPlan,
                                AccumulationPlan accumulationPlan, DepositPlan depositPlan, BonusPlan bonusPlan,
                                AnnuityPlan annuityPlan, FuneralPlan funeralPlan, String publishedBy) {
        publishVersion(productId, ifrsMeasurementModel, effectiveDate, retirementDate, ratingTable, benefitSchedule,
            fundDefinitions, baseRates, bounds, frequencyLoading, tiraFiling, cashValue, payoutPlan, accumulationPlan,
            depositPlan, bonusPlan, annuityPlan, funeralPlan, UnitLinkedPlan.none(), publishedBy);
    }

    @Override
    @Transactional
    public void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                                List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions,
                                List<BaseRateInput> baseRates, EligibilityBounds bounds, FrequencyLoading frequencyLoading,
                                TiraFiling tiraFiling, CashValuePlan cashValue, PayoutPlan payoutPlan,
                                AccumulationPlan accumulationPlan, DepositPlan depositPlan, BonusPlan bonusPlan,
                                AnnuityPlan annuityPlan, FuneralPlan funeralPlan, UnitLinkedPlan unitLinkedPlan,
                                String publishedBy) {
        publishVersion(productId, ifrsMeasurementModel, effectiveDate, retirementDate, ratingTable, benefitSchedule,
            fundDefinitions, baseRates, bounds, frequencyLoading, tiraFiling, cashValue, payoutPlan, accumulationPlan,
            depositPlan, bonusPlan, annuityPlan, funeralPlan, unitLinkedPlan, Ifrs17Terms.DEFAULT, publishedBy);
    }

    @Override
    @Transactional
    public void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                                List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions,
                                List<BaseRateInput> baseRates, EligibilityBounds bounds, FrequencyLoading frequencyLoading,
                                TiraFiling tiraFiling, CashValuePlan cashValue, PayoutPlan payoutPlan,
                                AccumulationPlan accumulationPlan, DepositPlan depositPlan, BonusPlan bonusPlan,
                                AnnuityPlan annuityPlan, FuneralPlan funeralPlan, UnitLinkedPlan unitLinkedPlan,
                                Ifrs17Terms ifrs17, String publishedBy) {
        // First, so the message is about the filing rather than about a rating table the caller
        // may not have reached yet. TiraFiling validates its own contents; what it cannot do is
        // object to its own absence.
        if (tiraFiling == null) {
            throw new InvalidProductVersionException(
                "A TIRA filing reference and approval date are required to publish a product"
                    + " version -- a version may not exist without the filing that authorises it");
        }

        UUID tenantId = TenantContext.get();
        ProductDefinition product = productDefinitionRepository.findById(productId)
            .filter(p -> p.getTenantId().equals(tenantId))
            .orElseThrow(() -> new ProductNotFoundException(productId));

        // A product that covers nothing is not a product. Refused going forward; the 143 versions
        // that already have no benefits are grandfathered at ISSUANCE, not here -- see
        // PolicyApiImpl. Making this a publish rule is what CLOSES that population: it can only
        // shrink from here, which is the difference between a migration path and a permanent
        // default.
        if (benefitSchedule == null || benefitSchedule.isEmpty()) {
            throw new InvalidProductVersionException(
                "A product version must cover at least one benefit -- a version that covers"
                    + " nothing cannot be sold, and nothing downstream could value a claim on it");
        }

        // The shape rule is enforced by BUILDING the benefit, not by restating it here. Without
        // this the only check on the WRITE path was benefit_schedule_amount_shape at the
        // database, which surfaces an author's mistake -- a percentage benefit with no
        // percentage -- as a DataIntegrityViolationException, so a 500 rather than the 422 it
        // is. The read path already goes through BenefitDefinition; this makes both ends the
        // same one place.
        List<BenefitDefinition> benefits = new ArrayList<>();
        for (BenefitInput input : benefitSchedule) {
            try {
                benefits.add(new BenefitDefinition(input.benefitType(), input.calculationMethod(),
                    input.percent(), input.flatAmount()));
            } catch (IllegalArgumentException e) {
                throw new InvalidProductVersionException(e.getMessage());
            }
        }

        // Funds live in unitlinked's register now (product step 6, plan C1); a version names them by code in its
        // unit-linked terms. The parameter stays for its 149 callers, all of which pass nothing.
        if (fundDefinitions != null && !fundDefinitions.isEmpty()) {
            throw new InvalidProductVersionException("fundDefinitions is replaced by unit-linked terms' fund codes:"
                + " a fund is defined once in the fund register and a version names the funds it offers");
        }
        // Deliverable 3 invariant: full rating-factor coverage validated at publish --
        // every declared FactorType must have at least one band defined so the rules
        // engine (Task 4) never silently falls back to a neutral 1.0 for a factor type
        // this product intended to rate on.
        java.util.Set<FactorType> coveredFactorTypes = ratingTable.stream().map(RatingFactorInput::factorType).collect(Collectors.toSet());
        boolean priced = baseRates != null && !baseRates.isEmpty();
        // A FUNERAL version is priced by its premium table alone (plan R1): FuneralPlanValidator refuses
        // any rating factor or base rate on it, so the unpriced-version rating rule below does not apply.
        boolean funeralCategory = ProductCategory.FUNERAL.name().equals(product.getCategory());
        // Before the rating rules, so a funeral author reads the funeral words, not a rating-table one.
        FuneralPlan funeral = funeralPlan != null ? funeralPlan : FuneralPlan.none();
        FuneralPlanValidator.validate(ProductCategory.valueOf(product.getCategory()), funeral, baseRates, ratingTable);
        // A UNIT_LINKED version is likewise not rated: its cost of insurance is taken monthly from units, by its
        // mortality table (spec §4). Validated here, before the rating rules, for the same reason.
        boolean unitLinkedCategory = ProductCategory.UNIT_LINKED.name().equals(product.getCategory());
        UnitLinkedPlan unitLinked = unitLinkedPlan != null ? unitLinkedPlan : UnitLinkedPlan.none();
        UnitLinkedPlanValidator.validate(ProductCategory.valueOf(product.getCategory()), unitLinked,
            product.getDefaultCurrency(), bounds != null ? bounds.minEntryAge() : null, fundDirectory.getIfAvailable(),
            priced || (unitLinkedCategory && !ratingTable.isEmpty()));

        if (priced) {
            // M13. Age IS rated on a priced version -- it is a key of the base rate
            // table -- so requiring an AGE multiplier as well would demand the one
            // thing the next check forbids. Only SUM_ASSURED_BAND stays required.
            if (!coveredFactorTypes.contains(FactorType.SUM_ASSURED_BAND)) {
                throw new InvalidProductVersionException("Rating table must cover at least the SUM_ASSURED_BAND factor type");
            }
            // The double-count guard, and the single most likely defect in this
            // design. AGE and SMOKER_STATUS are keys of the base rate table, so a
            // rating_table row for either would be applied a second time on top of
            // the rate it already selected -- silently inflating or deflating the
            // premium with nothing failing. Refused rather than left to review.
            java.util.Set<FactorType> doubleCounted = new java.util.LinkedHashSet<>(coveredFactorTypes);
            doubleCounted.retainAll(List.of(FactorType.AGE, FactorType.SMOKER_STATUS));
            if (!doubleCounted.isEmpty()) {
                throw new InvalidProductVersionException(
                    "A version with base rates must not also carry " + doubleCounted
                        + " rating factors -- those dimensions are keys of the base rate table and would be counted twice");
            }
            rejectOverlappingAgeBands(baseRates);
            rejectPricedVersionWithoutEntryAgeBounds(bounds);
            rejectUncoveredEntryAges(baseRates, bounds);
        } else if (!funeralCategory && !unitLinkedCategory
                && !coveredFactorTypes.containsAll(List.of(FactorType.AGE, FactorType.SUM_ASSURED_BAND))) {
            // Unpriced version: unchanged from M2. Age is rated by multiplier alone.
            throw new InvalidProductVersionException("Rating table must cover at least AGE and SUM_ASSURED_BAND factor types");
        }
        rejectNonPositiveMultipliers(ratingTable);
        rejectDuplicateRatingFactors(ratingTable);
        rejectMalformedAgeBands(ratingTable);
        rejectMalformedSumAssuredBands(ratingTable);
        ProductCategory category = ProductCategory.valueOf(product.getCategory());
        CashValuePlanValidator.validate(category, cashValue);
        DepositPlan deposit = depositPlan != null ? depositPlan : DepositPlan.none();
        DepositPlanValidator.validate(category, deposit, accumulationPlan, cashValue, frequencyLoading, payoutPlan, bounds);
        // A deposit's account plan is the server's: an account that charges and guarantees nothing,
        // so every ACCOUNT seam (lapse exemption, surrender event, death valuation) applies to it.
        AccumulationPlan effectiveAccumulation = deposit.isDeposit() ? AccumulationPlan.forDeposit() : accumulationPlan;
        AnnuityPlan annuity = annuityPlan != null ? annuityPlan : AnnuityPlan.none();
        AccumulationPlanValidator.validate(category, effectiveAccumulation, cashValue, annuity.deferred());
        PayoutPlanValidator.validate(category, payoutPlan, effectiveAccumulation, deposit.isDeposit());
        // The EFFECTIVE account plan, so a deposit is told the deposit rule rather than the account one.
        BonusPlanValidator.validate(category, bonusPlan, cashValue, effectiveAccumulation, payoutPlan, deposit);
        AnnuityPlanValidator.validate(category, annuity, bounds, cashValue, effectiveAccumulation, deposit, bonusPlan, payoutPlan);
        VestingPlanValidator.validate(category, annuity, effectiveAccumulation, bounds);
        if (effectiveAccumulation != null && effectiveAccumulation.isAccount()) {
            refuseUnlessASavingsPortfolio(product, deposit.isDeposit(), annuity.deferred());
        }

        // Version rollover: ux_product_version_active permits at most one
        // is_active_for_new_business = true row per product_id. Retire whatever version
        // currently holds that flag before inserting the new one, in the same transaction,
        // so a second publishVersion call on an existing product succeeds instead of
        // crashing on the partial unique index (C1 in the final review). This MUST be
        // saveAndFlush, not save: Hibernate's flush executes all queued INSERTs before any
        // queued UPDATEs regardless of call order, so a plain save() here would still let
        // the new version's INSERT reach the DB (and violate the partial unique index)
        // before this retirement UPDATE does. saveAndFlush forces the UPDATE to execute
        // synchronously, right here, ahead of the new version's insert below.
        for (ProductVersion currentActive : productVersionRepository.findByTenantIdAndProductIdAndActiveForNewBusinessTrue(tenantId, productId)) {
            currentActive.retireFromNewBusiness();
            productVersionRepository.saveAndFlush(currentActive);
        }

        int gracePeriodDays = 30; // Deliverable 3 doesn't specify a grace-period source yet at this layer -- see Global Constraints; this is a fixed, flagged default, not read from an OpenAPI field (ProductVersionSpec has no gracePeriodDays field).
        ProductVersion version = new ProductVersion(tenantId, productId, effectiveDate, retirementDate, gracePeriodDays, null,
            ifrsMeasurementModel != null ? ifrsMeasurementModel.name() : null, publishedBy);
        // IFRS 17 I2: what the actuary signs off. The model is the register's, resolved when a policy is classified.
        Ifrs17Terms terms = ifrs17 != null ? ifrs17 : Ifrs17Terms.DEFAULT;
        version.applyIfrs17Terms(terms.bucket().name(),
            terms.modelOverride() != null ? terms.modelOverride().name() : null,
            terms.survivalInvestmentComponentPercent());
        // What this version will accept. Never null -- callers that state nothing pass
        // EligibilityBounds.none(), because an unbounded version is a real design.
        version.applyEligibilityBounds(bounds != null ? bounds : EligibilityBounds.none());
        // What instalment payment costs. Never null -- callers that load nothing pass
        // FrequencyLoading.none(), because charging every frequency the same is a real decision.
        version.applyFrequencyLoading(frequencyLoading != null ? frequencyLoading : FrequencyLoading.none());
        // Never null: refused at the top of this method. There is no FrequencyLoading.none()
        // equivalent here on purpose -- an absent filing is not a kind of filing.
        version.applyTiraFiling(tiraFiling);
        productVersionRepository.save(version);
        persistCashValue(tenantId, version.getProductVersionId(), cashValue);
        persistPayoutPlan(tenantId, version.getProductVersionId(), payoutPlan);
        persistAccumulationPlan(tenantId, version.getProductVersionId(), effectiveAccumulation);
        persistDepositPlan(tenantId, version.getProductVersionId(), deposit);
        persistBonusPlan(tenantId, version.getProductVersionId(), bonusPlan);
        persistAnnuityPlan(tenantId, version.getProductVersionId(), annuity);
        funeralTermsStore.persist(tenantId, version.getProductVersionId(), funeral);
        unitLinkedTermsStore.persist(tenantId, version.getProductVersionId(), unitLinked);

        for (RatingFactorInput input : ratingTable) {
            ratingFactorRepository.save(new RatingFactor(tenantId, version.getProductVersionId(),
                input.factorType().name(), input.band(), input.multiplier(), input.ageFrom(), input.ageTo(),
                input.sumAssuredFrom(), input.sumAssuredTo()));
        }
        if (baseRates != null) {
            for (BaseRateInput input : baseRates) {
                baseRateRepository.save(new BaseRate(tenantId, version.getProductVersionId(),
                    input.ageFrom(), input.ageTo(), input.sex().name(), input.smokerStatus().name(),
                    input.ratePerMille(), input.termFromMonths(), input.termToMonths()));
            }
        }
        // Written from the validated definitions above, not from the raw inputs -- so a row can
        // only reach the table in a shape BenefitDefinition accepts.
        for (BenefitDefinition benefit : benefits) {
            benefitScheduleEntryRepository.save(new BenefitScheduleEntry(tenantId, version.getProductVersionId(),
                benefit.benefitType().name(), benefit.calculationMethod().name(),
                benefit.percent(), benefit.flatAmount()));
        }
        product.activate();
        productDefinitionRepository.save(product);
    }

    @Override
    public ProductSnapshotView getActiveSnapshot(UUID productId, LocalDate asOfDate) {
        UUID tenantId = TenantContext.get();
        LocalDate effectiveAsOf = asOfDate != null ? asOfDate : LocalDate.now();

        /*
         * The definition is resolved FIRST, and the order is the fix rather than a tidy-up.
         *
         * Both lookups used to throw ProductNotFoundException, and the version lookup ran
         * first -- so a product that exists but has no version in force today was reported
         * as a product that does not exist. On the console that became "this record does not
         * exist, or it is not available to your role", shown to a staff member who had just
         * clicked the product in the catalogue: the record plainly existed, and their role
         * had nothing to do with it. A GROUP_LIFE product whose only version was effective
         * the next day hit exactly this.
         *
         * Asking "is there such a product" before "is any version of it in force" is what
         * lets the two answers stay different.
         */
        ProductDefinition definition = productDefinitionRepository.findById(productId)
            .orElseThrow(() -> new ProductNotFoundException(productId));
        ProductVersion version = productVersionRepository.findActiveAsOf(tenantId, productId, effectiveAsOf).stream()
            .findFirst()
            .orElseThrow(() -> new NoActiveProductVersionException(productId, effectiveAsOf,
                productVersionRepository
                    .findFirstByTenantIdAndProductIdAndEffectiveDateAfterOrderByEffectiveDateAsc(
                        tenantId, productId, effectiveAsOf)
                    .map(ProductVersion::getEffectiveDate)
                    .orElse(null)));
        return new ProductSnapshotView(productId, version.getProductVersionId(), version.getEffectiveDate(),
            legacyModel(version),
            version.getGracePeriodDays(), version.getMaxLoanToValuePercent(),
            ProductCategory.valueOf(definition.getCategory()), version.getSurrenderChargeScheduleJson(),
            version.getEligibilityBounds(),
            version.getSuicideExclusionMonths(), version.getPreExistingExclusionMonths(),
            PortfolioCode.valueOf(definition.getPortfolioCode()),
            ProfitabilityBucket.valueOf(version.getExpectedProfitabilityBucket()), modelOverride(version),
            version.getSurvivalInvestmentComponentPercent());
    }

    /**
     * Order of operations is fixed and stated, because every step is a place a
     * silent mispricing could live:
     *
     * 1. Resolve the version active as of `asOf` -- the same lookup getActiveSnapshot
     *    uses, so a quote and an issuance on the same date price on the same rules.
     * 2. Derive entry age from dateOfBirth and asOf. Never taken from the caller.
     * 3. Look up the rate cell for (age, sex, smoker). No match is a 422.
     * 4. annualBase = sumAssured / 1000 * ratePerMille.
     * 5. Apply the OCCUPATION_CLASS multiplier strictly, then the SUM_ASSURED_BAND
     *    multiplier resolved BY RANGE from the amount -- the same lookup issuance
     *    makes, so an illustration and the policy it becomes cannot disagree.
     * 6. Apply the version's frequency loading to the annual premium. A payment
     *    term, applied after every risk term, so the breakdown reads in that order.
     * 7. Divide by the frequency's instalments per year.
     * 8. Round ONCE, at the end, HALF_UP to 2dp. Intermediate values keep full
     *    precision: rounding at each step would drift by cents that compound over
     *    a 20-year premium term.
     *
     * No policy fee -- no product field holds one (open item in the M13 spec).
     */
    @Override
    public PremiumQuoteView quotePremium(PremiumQuoteInput input) {
        UUID tenantId = TenantContext.get();
        LocalDate asOf = input.asOf() != null ? input.asOf() : LocalDate.now();

        ProductVersion version = productVersionRepository.findActiveAsOf(tenantId, input.productId(), asOf).stream()
            .findFirst()
            .orElseThrow(() -> new ProductNotFoundException(input.productId()));
        UUID versionId = version.getProductVersionId();

        if (!baseRateRepository.existsByProductVersionId(versionId)) {
            throw new PremiumNotQuotableException("Product version " + versionId
                + " carries no base rate table, so it cannot be priced");
        }

        int ageAtEntry = java.time.Period.between(input.dateOfBirth(), asOf).getYears();
        if (ageAtEntry < 0) {
            throw new PremiumNotQuotableException("Date of birth " + input.dateOfBirth() + " is after " + asOf);
        }

        // A SINGLE premium is one charge for a term the rate table does not span: rate_per_mille is
        // an ANNUAL rate, so a single premium is only the annual figure while the term is twelve
        // months. A longer single-premium term needs its own declared basis and is refused here
        // rather than quoted at a wrong number (the existing comment lower down said so; now it is
        // enforced).
        if (input.frequency() == PremiumFrequency.SINGLE
                && input.policyTermMonths() != null && input.policyTermMonths() > 12) {
            throw new PremiumNotQuotableException("A single premium over a " + input.policyTermMonths()
                + "-month term cannot be priced from an annual rate table; single premiums are"
                + " supported only up to twelve months of cover until a single-premium basis is added");
        }

        BaseRate cell = baseRateRepository
            .findApplicable(versionId, ageAtEntry, input.sex().name(), input.smokerStatus().name(), input.policyTermMonths())
            .orElseThrow(() -> new PremiumNotQuotableException("No base rate for age " + ageAtEntry
                + ", " + input.sex() + ", " + input.smokerStatus()
                + (input.policyTermMonths() != null ? ", term " + input.policyTermMonths() + " months" : ", no term")
                + " on product version " + versionId));

        BigDecimal annualBase = input.sumAssuredAmount()
            .divide(new BigDecimal("1000"), java.math.MathContext.DECIMAL64)
            .multiply(cell.getRatePerMille());

        List<AppliedFactor> applied = new java.util.ArrayList<>();
        BigDecimal annual = annualBase;
        annual = annual.multiply(strictMultiplier(versionId, FactorType.OCCUPATION_CLASS, input.occupationClass(), applied));
        // By RANGE, through the same lookup the issuance path makes. A neutral 1.0 when no band
        // covers the amount is correct here and matches issuance: a sum assured above every band is
        // already treated platform-wide as a SOFT flag, because above-retention business is what
        // the reinsurance treaties exist to absorb. Nothing is recorded in the breakdown when no
        // band applied, because no factor did.
        annual = annual.multiply(findSumAssuredBand(versionId, input.sumAssuredAmount())
            .map(row -> {
                applied.add(new AppliedFactor(FactorType.SUM_ASSURED_BAND, row.getBand(), row.getMultiplier()));
                return row.getMultiplier();
            })
            .orElse(BigDecimal.ONE));

        // The payment term, applied after all the risk arithmetic. Multiplication commutes, so its
        // position does not change the number -- it changes whether a reader can follow the
        // breakdown, which is the whole reason this view returns one.
        FrequencyLoading loading = version.getFrequencyLoading();
        BigDecimal loadedAnnual = loading.applyTo(annual, input.frequency());

        // SINGLE branches rather than divides. `instalmentsPerYear` is 0 for it BY DESIGN --
        // there is no recurring period, and dividing by that value throws, which is the
        // correct outcome for any caller reaching for a per-instalment figure on a contract
        // that has none. The single premium is the whole loaded annual figure, charged once.
        //
        // That equivalence holds because this input carries no term and every single-premium
        // product the platform sells today runs twelve months. A longer-term single premium
        // is NOT this number and must not be quoted here until the input carries a term.
        int instalments = input.frequency().instalmentsPerYear();
        BigDecimal instalment = input.frequency() == PremiumFrequency.SINGLE
            ? loadedAnnual.setScale(2, java.math.RoundingMode.HALF_UP)
            : loadedAnnual.divide(BigDecimal.valueOf(instalments), 2, java.math.RoundingMode.HALF_UP);

        return new PremiumQuoteView(versionId, input.sumAssuredCurrency(),
            ageAtEntry, cell.getAgeFrom(), cell.getAgeTo(), cell.getRatePerMille(),
            annualBase.setScale(2, java.math.RoundingMode.HALF_UP), List.copyOf(applied),
            annual.setScale(2, java.math.RoundingMode.HALF_UP),
            loading.percentFor(input.frequency()),
            loadedAnnual.setScale(2, java.math.RoundingMode.HALF_UP),
            input.frequency(), instalments, instalment);
    }

    @Override
    public VersionRatingView getVersionRating(UUID productId, UUID versionId) {
        UUID tenantId = TenantContext.get();
        ProductVersion version = productVersionRepository.findById(versionId)
            .filter(v -> v.getTenantId().equals(tenantId) && v.getProductId().equals(productId))
            .orElseThrow(() -> new ProductNotFoundException(versionId));

        List<BaseRateInput> rates = baseRateRepository.findByProductVersionId(versionId).stream()
            .map(r -> new BaseRateInput(r.getAgeFrom(), r.getAgeTo(), Sex.valueOf(r.getSex()),
                SmokerStatus.valueOf(r.getSmokerStatus()), r.getRatePerMille(),
                r.getTermFromMonths(), r.getTermToMonths()))
            .toList();
        List<RatingFactorInput> factors = ratingFactorRepository.findByProductVersionId(versionId).stream()
            // Bounds included, both kinds: this is the read an actuary reviews a version's
            // rating basis on, and a SUM_ASSURED_BAND row's bounds are what it now rates by.
            // Showing only the band text would show the label while hiding the rule -- and the
            // label is exactly what turned out to be untrustworthy (V9).
            .map(f -> new RatingFactorInput(FactorType.valueOf(f.getFactorType()), f.getBand(),
                f.getMultiplier(), f.getAgeFrom(), f.getAgeTo(),
                f.getSumAssuredFrom(), f.getSumAssuredTo()))
            .toList();
        List<BenefitInput> benefits = benefitScheduleEntryRepository.findByProductVersionId(versionId).stream()
            .map(b -> new BenefitInput(BenefitType.valueOf(b.getBenefitType()),
                BenefitCalculationMethod.valueOf(b.getCalculationMethod()), b.getBenefitPercent(), b.getFlatAmount()))
            .toList();

        return new VersionRatingView(productId, versionId, version.getEffectiveDate(), rates, factors, benefits,
            version.getFrequencyLoading(), version.getTiraFiling());
    }

    /**
     * Two bands covering the same age in the same (sex, smoker) cell would make the
     * premium depend on which row the query happens to return first -- a silent
     * mispricing rather than an error, and invisible to the unique constraint,
     * which only stops an identical starting age.
     *
     * Enforced here rather than in SQL: a true non-overlap constraint needs an
     * EXCLUDE ... USING gist over an int4range, which requires the btree_gist
     * extension for the equality parts of the key. Not worth a new extension on
     * every environment for a rule this cheap to check where the rows are authored.
     */
    /**
     * Two rating_table rows sharing a (factorType, band) make the multiplier that applies depend on
     * which row the query returns first -- {@code strictMultiplier} and {@code
     * resolveRatingMultiplier} both filter to the band and then take {@code findFirst()}. Same
     * silent-mispricing shape as overlapping age bands, and until this check nothing stopped it:
     * {@code rating_table} carried only a NON-unique index on (product_version_id, factor_type),
     * and the authoring form lets a user add the same band twice with different multipliers.
     *
     * <p>Found by sweeping for this defect class after three unordered-query bugs turned up in one
     * day, rather than by a failing test -- a duplicate band is not reachable by accident, so it
     * would have sat here until someone authored one and quietly got the wrong premium.
     *
     * <p>{@code V4__rating_table_unique_band.sql} adds the constraint the table should always have
     * had. This check exists as well as that one so the failure names the offending band instead of
     * surfacing as a constraint violation.
     */
    /**
     * A rating multiplier must be a positive number.
     *
     * <p><b>A zero here zeroes the premium, and it happened.</b> A product was published with its
     * AGE band at 0.0000; an applicant was accepted against it; the premium computed to nil; the
     * insert hit {@code chk_premium_amount_positive} inside an AFTER_COMMIT listener, so the
     * decision stood, no policy was created, and nothing surfaced. The underwriter read ACCEPT
     * and believed a policy existed.
     *
     * <p>Nothing stopped it at any layer: the console's schema had a bare
     * {@code z.coerce.number()}, this method checked coverage, duplicates and age ranges but
     * never the number, and {@code rating_table} carried no CHECK. A negative multiplier would
     * have reached just as far and priced a policy below nothing.
     *
     * <p>There is no product in which a rating factor of zero is a real design. A band that adds
     * nothing is expressed by 1.0000, or by leaving the row out — both of which say so, where a
     * zero only looks like a number somebody meant.
     *
     * <p>{@code rating_table_multiplier_positive} backs this at the database. Checked here as
     * well so the failure names the band instead of surfacing as a constraint violation, which is
     * the same arrangement as the duplicate-band and age-bound rules below.
     */
    private static void rejectNonPositiveMultipliers(List<RatingFactorInput> ratingTable) {
        for (RatingFactorInput factor : ratingTable) {
            if (factor.multiplier() == null || factor.multiplier().signum() <= 0) {
                throw new InvalidProductVersionException("Rating factor " + factor.factorType()
                    + " band '" + factor.band() + "' has a multiplier of "
                    + (factor.multiplier() != null ? factor.multiplier().toPlainString() : "none")
                    + " -- a rating multiplier must be greater than zero, or it prices every policy"
                    + " in that band at nothing. Use 1.0000 for a band that does not load.");
            }
        }
    }

    private static void rejectDuplicateRatingFactors(List<RatingFactorInput> ratingTable) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (RatingFactorInput factor : ratingTable) {
            if (!seen.add(factor.factorType() + "|" + factor.band())) {
                throw new InvalidProductVersionException("Rating table has two rows for "
                    + factor.factorType() + " band '" + factor.band()
                    + "' -- which multiplier applies would depend on row order");
            }
        }
    }

    /**
     * AGE rating factors must carry an age range, and the ranges must not overlap.
     *
     * <p>The shape half is also a database CHECK ({@code rating_table_age_bounds_shape}),
     * checked here so the failure names the offending band instead of surfacing as a
     * constraint violation. The overlap half exists only here, for the same reason it does
     * for base rates: a true non-overlap constraint needs an EXCLUDE ... USING gist over an
     * int4range, which needs btree_gist on every environment, and the rule is cheap to
     * check where the rows are authored.
     *
     * <p>Two AGE bands covering one applicant would make the multiplier depend on which row
     * the query returned first. That is the same silent-mispricing shape found four times
     * over on this platform, and it decides an underwriting outcome here, not just a label.
     */
    /**
     * SUM_ASSURED_BAND rating factors must carry an amount range, and the ranges must not overlap.
     *
     * <p>The same rule as {@link #rejectMalformedAgeBands}, for the same defect one factor type
     * over (V9). A band was matched by exact string against three values hardcoded in
     * underwriting, so a product author's band could never match and the factor silently did
     * nothing. Bounds are now what the platform resolves on, so a row without them would be a row
     * that rates nobody — which is precisely the failure being removed, and must not be
     * publishable.
     *
     * <p>The shape half is also a database CHECK ({@code rating_table_sum_assured_bounds_shape}),
     * checked here so the failure names the offending band. The overlap half exists only here,
     * for the same reason it does for ages and base rates: a true non-overlap constraint needs an
     * EXCLUDE ... USING gist, and the rule is cheap to check where the rows are authored.
     *
     * <p><b>A range is demanded of a row that RATES, and not of a neutral one.</b> The defect
     * being removed is a row that claims to change a price and silently does not; a row at
     * exactly 1.0000 changes no price whether it resolves or not, so demanding bounds of it would
     * be ceremony. It would also invalidate every version published before V9 on republish, and
     * around eighty existing fixtures whose SUM_ASSURED_BAND row exists only to satisfy the
     * coverage rule — none of which would be made more correct by inventing an amount range for
     * them.
     */
    private static void rejectMalformedSumAssuredBands(List<RatingFactorInput> ratingTable) {
        List<RatingFactorInput> bands = ratingTable.stream()
            .filter(f -> f.factorType() == FactorType.SUM_ASSURED_BAND)
            .toList();

        for (RatingFactorInput f : bands) {
            boolean neutral = f.multiplier() != null && f.multiplier().compareTo(BigDecimal.ONE) == 0;
            boolean unbounded = f.sumAssuredFrom() == null || f.sumAssuredTo() == null;
            if (unbounded && !neutral) {
                throw new InvalidProductVersionException("SUM_ASSURED_BAND rating factor '" + f.band()
                    + "' carries a multiplier of " + f.multiplier().toPlainString()
                    + " but no amount range. A sum assured is rated by RANGE, not by matching the"
                    + " band text, so this row would rate nobody and the multiplier would never"
                    + " reach a premium -- which is the defect this rule exists to stop.");
            }
            if (unbounded) {
                continue;
            }
            if (f.sumAssuredFrom().signum() < 0) {
                throw new InvalidProductVersionException("SUM_ASSURED_BAND rating factor '" + f.band()
                    + "' starts below zero");
            }
            if (f.sumAssuredTo().compareTo(f.sumAssuredFrom()) < 0) {
                throw new InvalidProductVersionException("SUM_ASSURED_BAND rating factor '" + f.band()
                    + "' ends before it begins");
            }
        }

        // Only bounded rows can overlap; an unbounded neutral one covers nothing.
        List<RatingFactorInput> bounded = bands.stream()
            .filter(f -> f.sumAssuredFrom() != null && f.sumAssuredTo() != null)
            .toList();
        for (RatingFactorInput a : bounded) {
            for (RatingFactorInput b : bounded) {
                if (a == b) continue;
                if (a.sumAssuredFrom().compareTo(b.sumAssuredTo()) <= 0
                        && b.sumAssuredFrom().compareTo(a.sumAssuredTo()) <= 0) {
                    throw new InvalidProductVersionException("SUM_ASSURED_BAND ranges '" + a.band()
                        + "' and '" + b.band() + "' overlap -- a sum assured in both would price"
                        + " differently depending on row order");
                }
            }
        }
    }

    private static void rejectMalformedAgeBands(List<RatingFactorInput> ratingTable) {
        List<RatingFactorInput> ageBands = ratingTable.stream()
            .filter(f -> f.factorType() == FactorType.AGE)
            .toList();

        for (RatingFactorInput f : ageBands) {
            if (f.ageFrom() == null || f.ageTo() == null) {
                throw new InvalidProductVersionException("AGE rating factor '" + f.band()
                    + "' needs an age range -- age is rated by range, not by matching the band text");
            }
            if (f.ageFrom() < 0 || f.ageTo() < f.ageFrom()) {
                throw new InvalidProductVersionException("AGE rating factor '" + f.band()
                    + "' has an impossible range " + f.ageFrom() + "-" + f.ageTo());
            }
        }
        for (RatingFactorInput a : ageBands) {
            for (RatingFactorInput b : ageBands) {
                if (a == b) continue;
                if (a.ageFrom() <= b.ageTo() && b.ageFrom() <= a.ageTo()) {
                    throw new InvalidProductVersionException("AGE rating factors " + a.ageFrom() + "-"
                        + a.ageTo() + " and " + b.ageFrom() + "-" + b.ageTo()
                        + " overlap -- an applicant in both would be rated by whichever row came back first");
                }
            }
        }
        // A version that rates on AGE at all should cover the ages it sells to, but nothing
        // on this platform records a product's minimum or maximum entry age, so there is no
        // range to check completeness against. An uncovered age resolves to the neutral 1.0
        // rather than failing, which is the documented contract -- worth revisiting if entry
        // age limits ever become product data.
    }

    /**
     * A priced version must say what ages it sells to.
     *
     * <p>Without bounds there is no declared range for {@link #rejectUncoveredEntryAges} to check
     * a rate table against, so the table's own span silently becomes the product's selling range.
     * That is not hypothetical: a real published version declares it accepts entry ages 18-78
     * while pricing no woman under 56, and nothing could tell the difference between "we price
     * 18-30 deliberately" and "we forgot the rest".
     *
     * <p>Only priced versions. An unpriced version has no rate table to be incomplete against, and
     * bounds stay optional there exactly as {@link EligibilityBounds} describes.
     */
    private static void rejectPricedVersionWithoutEntryAgeBounds(EligibilityBounds bounds) {
        if (bounds == null || bounds.minEntryAge() == null || bounds.maxEntryAge() == null) {
            throw new InvalidProductVersionException(
                "A version priced from a base rate table must declare its minimum and maximum"
                    + " entry age. Without them the rate table's own span silently becomes the"
                    + " product's selling range, and nothing can tell a deliberate range from an"
                    + " incomplete one.");
        }
    }

    /**
     * A priced version must be able to price every life it says it will accept.
     *
     * <p>Ranges over the cross-product of sex and smoker status, not over age alone, because age
     * alone does not catch the defect. A real version's bands span its full declared 18-78 in
     * aggregate while pricing women only from 56 and men only to 56 — an age-only rule reads that
     * table as complete, and an aggregate query over it looks healthy.
     *
     * <p>Both sexes are required. The smoker statuses required are exactly those the table prices
     * somewhere: a product may decline to price {@link SmokerStatus#UNKNOWN} and demand a
     * declaration, which is a real underwriting stance, but pricing {@code SMOKER} for women and
     * not for men is an asymmetry with no product meaning. A combination absent entirely covers
     * nothing and fails here.
     *
     * <p>Application-level only. The SQL equivalent needs {@code EXCLUDE ... USING gist} over an
     * {@code int4range} and therefore {@code btree_gist} on every environment — the same reasoning
     * recorded on {@link #rejectOverlappingAgeBands}.
     *
     * <p>Package-private (not private) solely so {@code ProductCoverageRuleTest} can exercise this
     * premium-affecting range walk directly, without a Spring context — the same arrangement, for
     * the same reason, as {@code PolicyApiImpl.resolveSurrenderChargePercent}.
     */
    static void rejectUncoveredEntryAges(List<BaseRateInput> baseRates, EligibilityBounds bounds) {
        int min = bounds.minEntryAge();
        int max = bounds.maxEntryAge();

        java.util.Set<SmokerStatus> pricedSmokerStatuses = baseRates.stream()
            .map(BaseRateInput::smokerStatus)
            .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

        for (Sex sex : Sex.values()) {
            for (SmokerStatus smokerStatus : pricedSmokerStatuses) {
                List<BaseRateInput> cells = baseRates.stream()
                    .filter(r -> r.sex() == sex && r.smokerStatus() == smokerStatus)
                    .sorted(java.util.Comparator.comparingInt(BaseRateInput::ageFrom))
                    .toList();

                // Walk the sorted bands, extending coverage through any band that starts at or
                // before the first still-uncovered age. Tolerates overlaps and bands running past
                // the declared range; stops at the first gap.
                int covered = min - 1;
                for (BaseRateInput cell : cells) {
                    if (cell.ageFrom() > covered + 1) {
                        break;
                    }
                    covered = Math.max(covered, cell.ageTo());
                }
                if (covered >= max) {
                    continue;
                }

                int gapFrom = covered + 1;
                int gapTo = cells.stream()
                    .filter(c -> c.ageFrom() > gapFrom)
                    .mapToInt(c -> c.ageFrom() - 1)
                    .min()
                    .orElse(max);
                throw new InvalidProductVersionException("Base rate table does not price "
                    + sex + "/" + smokerStatus + " for ages " + gapFrom + "-" + Math.min(gapTo, max)
                    + ", but this version accepts entry ages " + min + "-" + max
                    + ". A priced version must be able to price every life it says it will accept.");
            }
        }
    }

    /** The cash-value table and its sign-off, in the version's own transaction. Nothing for none(). */
    private void persistCashValue(UUID tenantId, UUID productVersionId, CashValuePlan cashValue) {
        if (cashValue == null || !cashValue.isPresent()) {
            return;
        }
        cashValueConfigRepository.save(new CashValueConfig(productVersionId, tenantId, cashValue.basisReference(),
            cashValue.basisDate(), cashValue.paidUpBasis(), cashValue.minYearsForValue()));
        for (CashValueRowInput row : cashValue.rows()) {
            cashValueEntryRepository.save(new CashValueEntry(tenantId, productVersionId, row.policyYear(), row.ageFrom(),
                row.ageTo(), row.cashValuePerMille(), row.paidUpPerMille()));
        }
    }

    /** The payout schedule and its terms, in the version's own transaction. Nothing for none(). */
    private void persistPayoutPlan(UUID tenantId, UUID productVersionId, PayoutPlan plan) {
        if (plan == null || !plan.authored()) {
            return;
        }
        versionPayoutTermsRepository.save(new VersionPayoutTerms(tenantId, productVersionId, plan.terms()));
        for (int order = 0; order < plan.rows().size(); order++) {
            payoutScheduleRowRepository.save(new PayoutScheduleRow(tenantId, productVersionId, order, plan.rows().get(order)));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PayoutPlan resolvePayoutPlan(UUID productVersionId) {
        // RLS scopes both reads to the caller's tenant, so no explicit tenant filter is needed --
        // the same arrangement getCashValueConfig uses.
        return versionPayoutTermsRepository.findById(productVersionId)
            .map(terms -> PayoutPlan.authored(terms.toTerms(),
                payoutScheduleRowRepository.findByProductVersionIdOrderByRowOrder(productVersionId).stream()
                    .map(PayoutScheduleRow::toInput).toList()))
            .orElse(PayoutPlan.none());
    }

    /** An ACCOUNT version's terms and charges (product step 3). Nothing for a SCALE version. */
    private void persistAccumulationPlan(UUID tenantId, UUID productVersionId, AccumulationPlan plan) {
        if (plan == null || !plan.isAccount()) {
            return; // a SCALE version writes nothing -- its absence here IS the scale basis
        }
        versionAccumulationTermsRepository.save(new VersionAccumulationTerms(tenantId, productVersionId,
            plan.guaranteedRatePercent(), plan.minimumBalance()));
        for (AccumulationChargeRow row : plan.charges()) {
            accumulationChargeRepository.save(new AccumulationCharge(tenantId, productVersionId, row));
        }
    }

    /** A deposit version's grid (V20). Nothing for any other version. */
    private void persistDepositPlan(UUID tenantId, UUID productVersionId, DepositPlan plan) {
        for (DepositRateRow row : plan.rows()) {
            depositRateRepository.save(new DepositRate(tenantId, productVersionId, row));
        }
    }

    /** A with-profits version's terms (V21). Nothing for a non-participating one -- its absence IS that. */
    private void persistBonusPlan(UUID tenantId, UUID productVersionId, BonusPlan plan) {
        if (plan == null || !plan.participating()) {
            return;
        }
        versionBonusTermsRepository.save(new VersionBonusTerms(tenantId, productVersionId, plan.method().name(),
            plan.paidUpParticipates(), plan.surrenderBasis().name()));
        for (BonusSurrenderRow row : plan.surrenderRows()) {
            bonusSurrenderEntryRepository.save(new BonusSurrenderEntry(tenantId, productVersionId, row));
        }
    }

    /** An ANNUITY version's terms (V22). Nothing for any other version -- its absence IS that. */
    private void persistAnnuityPlan(UUID tenantId, UUID productVersionId, AnnuityPlan plan) {
        if (plan == null || !plan.annuity()) {
            return;
        }
        versionAnnuityTermsRepository.save(new VersionAnnuityTerms(tenantId, productVersionId, plan.timing().name(),
            plan.proofOfLifeIntervalMonths(), plan.jointAgeDifferenceMin(), plan.jointAgeDifferenceMax(),
            plan.basisReference(), plan.basisDate()));
        for (AnnuityForm form : plan.forms()) {
            AnnuityFormEntity saved = annuityFormRepository.saveAndFlush(new AnnuityFormEntity(tenantId, productVersionId, form));
            for (AnnuityRateRow row : form.rates()) {
                annuityRateEntryRepository.save(new AnnuityRateEntry(tenantId, saved.getAnnuityFormId(), row));
            }
        }
        for (AnnuityFrequencyFactor f : plan.frequencies()) {
            annuityFrequencyEntryRepository.save(new AnnuityFrequencyEntry(tenantId, productVersionId, f));
        }
        if (plan.vesting() != null) {
            versionVestingTermsRepository.save(new VersionVestingTerms(tenantId, productVersionId, plan.vesting()));
        }
    }

    /**
     * Product first, annuity tables second (plan R2): the version's own product says whether it is an
     * ANNUITY before any V22 table is read. Every policy event on the platform reaches a caller of
     * this, so a test class that never publishes an annuity never needs V22 -- and never logs a
     * missing-relation error for it.
     */
    @Override
    @Transactional(readOnly = true)
    public AnnuityPlan resolveAnnuityPlan(UUID productVersionId) {
        if (productVersionId == null) {
            return AnnuityPlan.none();
        }
        Optional<ProductVersion> version = productVersionRepository.findById(productVersionId);
        if (version.isEmpty()) {
            return AnnuityPlan.none();
        }
        boolean annuity = productDefinitionRepository.findById(version.get().getProductId())
            .map(p -> ProductCategory.ANNUITY.name().equals(p.getCategory())).orElse(false);
        if (!annuity) {
            return AnnuityPlan.none();
        }
        return versionAnnuityTermsRepository.findById(productVersionId)
            .map(t -> new AnnuityPlan(true, AnnuityTiming.valueOf(t.getTiming()), t.getProofOfLifeIntervalMonths(),
                t.getJointAgeDifferenceMin(), t.getJointAgeDifferenceMax(), t.getBasisReference(), t.getBasisDate(),
                annuityFormRepository.findByProductVersionIdOrderByFormCode(productVersionId).stream()
                    .map(f -> f.toForm(annuityRateEntryRepository.findByAnnuityFormIdOrderByAge(f.getAnnuityFormId())
                        .stream().map(AnnuityRateEntry::toRow).toList()))
                    .toList(),
                annuityFrequencyEntryRepository.findByProductVersionId(productVersionId).stream()
                    .map(AnnuityFrequencyEntry::toFactor).toList(),
                // Still behind the category gate: only an ANNUITY version reads V23 (D2).
                versionVestingTermsRepository.findById(productVersionId).map(VersionVestingTerms::toTerms).orElse(null)))
            .orElse(AnnuityPlan.none());
    }

    /** Product first, funeral tables second -- resolveAnnuityPlan's rule, for the same reason. */
    @Override
    @Transactional(readOnly = true)
    public FuneralPlan resolveFuneralPlan(UUID productVersionId) {
        if (productVersionId == null) {
            return FuneralPlan.none();
        }
        Optional<ProductVersion> version = productVersionRepository.findById(productVersionId);
        if (version.isEmpty()) {
            return FuneralPlan.none();
        }
        boolean funeral = productDefinitionRepository.findById(version.get().getProductId())
            .map(p -> ProductCategory.FUNERAL.name().equals(p.getCategory())).orElse(false);
        return funeral ? funeralTermsStore.read(productVersionId) : FuneralPlan.none();
    }

    /** Product first, unit-linked tables second -- the same gate, so no other version ever reads V25. */
    @Override
    @Transactional(readOnly = true)
    public UnitLinkedPlan resolveUnitLinkedPlan(UUID productVersionId) {
        if (productVersionId == null) {
            return UnitLinkedPlan.none();
        }
        Optional<ProductVersion> version = productVersionRepository.findById(productVersionId);
        if (version.isEmpty()) {
            return UnitLinkedPlan.none();
        }
        boolean unitLinked = productDefinitionRepository.findById(version.get().getProductId())
            .map(p -> ProductCategory.UNIT_LINKED.name().equals(p.getCategory())).orElse(false);
        return unitLinked ? unitLinkedTermsStore.read(productVersionId) : UnitLinkedPlan.none();
    }

    /** A refusal is an answer (priceAnnuity's reason): it must not mark the caller's transaction rollback-only. */
    @Override
    @Transactional(readOnly = true, noRollbackFor = FuneralQuoteRefusedException.class)
    public FuneralQuote quoteFuneral(UUID productVersionId, FuneralQuoteInput input) {
        return FuneralQuoter.quote(resolveFuneralPlan(productVersionId), resolveFrequencyLoading(productVersionId), input);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> funeralFamilyProblems(UUID productVersionId, String planCode, LocalDate asOf,
                                              List<FuneralLifeInput> lives) {
        return FuneralQuoter.familyProblems(resolveFuneralPlan(productVersionId), planCode, asOf, lives);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> funeralJoinerProblems(UUID productVersionId, String planCode, LocalDate asOf, FuneralLifeInput life,
                                              int alreadyInRole) {
        return FuneralQuoter.joinerProblems(resolveFuneralPlan(productVersionId), planCode, asOf, life, alreadyInRole);
    }

    @Override
    @Transactional(readOnly = true, noRollbackFor = FuneralQuoteRefusedException.class)
    public BigDecimal funeralYearlyPremium(UUID productVersionId, String planCode, FuneralRole role, int age) {
        return FuneralQuoter.yearlyPremiumAt(resolveFuneralPlan(productVersionId), planCode, role, age);
    }

    @Override
    @Transactional(readOnly = true, noRollbackFor = FuneralQuoteRefusedException.class)
    public FuneralQuoteLine admitFuneralLife(UUID productVersionId, String planCode, FuneralLifeInput life, int alreadyInRole,
                                             LocalDate asOf) {
        return FuneralQuoter.admit(resolveFuneralPlan(productVersionId), planCode, life, alreadyInRole, asOf);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal funeralInstalment(UUID productVersionId, BigDecimal totalYearlyPremium, PremiumFrequency frequency) {
        return FuneralQuoter.instalment(totalYearlyPremium, resolveFrequencyLoading(productVersionId), frequency);
    }

    /**
     * A refusal is an answer, not a failure: callers catch it and record it -- the D1 lock as
     * LOCK_FAILED, the D2 vesting as a hold -- inside their own transaction. Without noRollbackFor the
     * refusal, thrown through this proxy, marked that transaction rollback-only and the record never
     * committed.
     */
    @Override
    @Transactional(readOnly = true, noRollbackFor = AnnuityPricingRefusedException.class)
    public AnnuityPrice priceAnnuity(UUID productVersionId, AnnuityPricingInput input) {
        return AnnuityPricer.price(resolveAnnuityPlan(productVersionId), input);
    }

    @Override
    @Transactional(readOnly = true)
    public BonusPlan resolveBonusPlan(UUID productVersionId) {
        // RLS scopes both reads to the caller's tenant, as resolveAccumulationPlan's are.
        return versionBonusTermsRepository.findById(productVersionId)
            .map(t -> new BonusPlan(true, BonusMethod.valueOf(t.getBonusMethod()), t.isPaidUpParticipates(),
                BonusSurrenderBasis.valueOf(t.getSurrenderBasis()),
                bonusSurrenderEntryRepository.findByProductVersionIdOrderByFromCompletedYears(productVersionId).stream()
                    .map(BonusSurrenderEntry::toRow).toList()))
            .orElse(BonusPlan.none());
    }

    @Override
    @Transactional(readOnly = true)
    public DepositPlan resolveDepositPlan(UUID productVersionId) {
        // RLS scopes the read to the caller's tenant, as resolveAccumulationPlan's are.
        return new DepositPlan(depositRateRepository.findByProductVersionIdOrderByMinAmountAscTermMonthsAsc(productVersionId)
            .stream().map(DepositRate::toRow).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public AccumulationPlan resolveAccumulationPlan(UUID productVersionId) {
        // RLS scopes both reads to the caller's tenant -- the arrangement resolvePayoutPlan uses.
        return versionAccumulationTermsRepository.findById(productVersionId)
            .map(terms -> new AccumulationPlan(ValueBasis.ACCOUNT, terms.getGuaranteedRatePercent(),
                terms.getMinimumBalance(),
                accumulationChargeRepository.findByProductVersionIdOrderByFromPolicyYear(productVersionId).stream()
                    .map(AccumulationCharge::toRow).toList()))
            .orElse(AccumulationPlan.none());
    }

    private static void rejectOverlappingAgeBands(List<BaseRateInput> baseRates) {
        for (BaseRateInput a : baseRates) {
            if (a.ageTo() < a.ageFrom()) {
                throw new InvalidProductVersionException(
                    "Base rate band " + a.ageFrom() + "-" + a.ageTo() + " ends before it begins");
            }
            // Both term bounds together, or neither -- mirrors base_rate_term_range_shape.
            if ((a.termFromMonths() == null) != (a.termToMonths() == null)) {
                throw new InvalidProductVersionException(
                    "A base rate term band needs both a from and a to month, or neither");
            }
            if (a.termFromMonths() != null && (a.termFromMonths() < 1 || a.termToMonths() < a.termFromMonths())) {
                throw new InvalidProductVersionException(
                    "Base rate term band " + a.termFromMonths() + "-" + a.termToMonths() + " months is not a range");
            }
            for (BaseRateInput b : baseRates) {
                if (a == b || a.sex() != b.sex() || a.smokerStatus() != b.smokerStatus()) continue;
                // Two cells collide only if BOTH their age ranges AND their term ranges overlap. An
                // unbanded row (null term) overlaps every term, so it cannot coexist with any other
                // row for the same age/sex/smoker -- which is what keeps findApplicable to one match.
                boolean ageOverlap = a.ageFrom() <= b.ageTo() && b.ageFrom() <= a.ageTo();
                if (ageOverlap && termRangesOverlap(a, b)) {
                    throw new InvalidProductVersionException("Base rate cells " + a.ageFrom() + "-" + a.ageTo()
                        + termLabel(a) + " and " + b.ageFrom() + "-" + b.ageTo() + termLabel(b)
                        + " overlap for " + a.sex() + "/" + a.smokerStatus()
                        + " -- an age and term in both would price differently depending on row order");
                }
            }
        }
    }

    /** Null term = any term, so it overlaps everything; otherwise the two month ranges intersect. */
    private static boolean termRangesOverlap(BaseRateInput a, BaseRateInput b) {
        if (a.termFromMonths() == null || b.termFromMonths() == null) {
            return true;
        }
        return a.termFromMonths() <= b.termToMonths() && b.termFromMonths() <= a.termToMonths();
    }

    private static String termLabel(BaseRateInput r) {
        return r.termFromMonths() == null ? " (any term)"
            : " (" + r.termFromMonths() + "-" + r.termToMonths() + "mo)";
    }

    /**
     * Unlike {@link #resolveRatingMultiplier}, a missing band here is an ERROR.
     * That method's neutral-1.0 is correct for underwriting risk scoring; on a
     * premium it would quietly price a real contract as if the factor did not
     * apply.
     *
     * <p>Only OCCUPATION_CLASS now. The sum-assured factor resolves by range instead, because the
     * amount determines the band; an occupation class is a code the same organisation chose on both
     * sides of the match, so a caller's typo should fail loudly rather than price at 1.0.
     */
    private BigDecimal strictMultiplier(UUID versionId, FactorType factorType, String band, List<AppliedFactor> applied) {
        if (band == null || band.isBlank()) {
            throw new PremiumNotQuotableException(factorType + " is required to price this product");
        }
        BigDecimal multiplier = ratingFactorRepository.findByProductVersionIdAndFactorType(versionId, factorType.name()).stream()
            .filter(f -> f.getBand().equals(band))
            .map(RatingFactor::getMultiplier)
            .findFirst()
            .orElseThrow(() -> new PremiumNotQuotableException(
                "No " + factorType + " multiplier for band '" + band + "' on product version " + versionId));
        applied.add(new AppliedFactor(factorType, band, multiplier));
        return multiplier;
    }

    @Override
    public BigDecimal resolveAgeMultiplier(UUID productVersionId, int age) {
        List<RatingFactor> covering = ratingFactorRepository.findAgeBandCovering(productVersionId, age);
        if (covering.isEmpty()) {
            // Neutral, exactly as resolveRatingMultiplier is for an unmatched band. This is
            // also the path every version published before V5 takes: its AGE rows have no
            // bounds, so they cover nobody and those versions keep rating age the way they
            // always have, rather than having decisions change underneath them.
            return BigDecimal.ONE;
        }
        if (covering.size() > 1) {
            // Overlaps are rejected at publish. Reaching here means a version predates that
            // check or was written around it, and picking one of two would be a silent
            // mis-rating -- the failure mode this platform has now found four times over.
            throw new InvalidProductVersionException("Product version " + productVersionId
                + " has " + covering.size() + " AGE bands covering age " + age
                + " -- which multiplier applies is undefined");
        }
        return covering.get(0).getMultiplier();
    }

    @Override
    public BigDecimal resolveRatingMultiplier(UUID productVersionId, FactorType factorType, String band) {
        return ratingFactorRepository.findByProductVersionIdAndFactorType(productVersionId, factorType.name()).stream()
            .filter(f -> f.getBand().equals(band))
            .map(RatingFactor::getMultiplier)
            .findFirst()
            .orElse(BigDecimal.ONE); // No matching band -- neutral multiplier, not an error (see ProductApi.resolveRatingMultiplier's javadoc).
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns a list and inspects it rather than taking {@code findFirst()}, for the same
     * reason {@code findAgeBandCovering} does: publish-time validation refuses overlapping bands,
     * so the steady state is 0 or 1 rows — but the invariant is enforced where the rows are
     * authored, not assumed here. A version published before that validation existed could still
     * hold overlaps, and silently taking one of two matches is the scan-order mispricing this
     * platform has now found five times.
     */
    @Override
    public BigDecimal resolveSumAssuredMultiplier(UUID productVersionId, BigDecimal sumAssuredAmount) {
        return findSumAssuredBand(productVersionId, sumAssuredAmount)
            .map(RatingFactor::getMultiplier)
            .orElse(BigDecimal.ONE);
    }

    /**
     * The SUM_ASSURED_BAND row covering this amount, if any.
     *
     * <p>Shared by {@link #resolveSumAssuredMultiplier} and {@code quotePremium} so the underwriting
     * path and the illustration path cannot resolve the same amount differently. They did: the quote
     * matched a caller-asserted band string while issuance matched by range, so an illustration and
     * the policy it became were priced by two mechanisms and only one of them worked.
     *
     * <p>Inspects the whole list rather than taking {@code findFirst()} because publish-time
     * validation refuses overlapping bands but a version published before that rule could still
     * hold them, and silently taking one of two is the scan-order mispricing this platform has now
     * found five times.
     */
    private Optional<RatingFactor> findSumAssuredBand(UUID productVersionId, BigDecimal sumAssuredAmount) {
        if (sumAssuredAmount == null) {
            return Optional.empty();
        }
        List<RatingFactor> covering = ratingFactorRepository
            .findByProductVersionIdAndFactorType(productVersionId, FactorType.SUM_ASSURED_BAND.name())
            .stream()
            .filter(f -> f.getSumAssuredFrom() != null && f.getSumAssuredTo() != null)
            .filter(f -> f.getSumAssuredFrom().compareTo(sumAssuredAmount) <= 0
                && f.getSumAssuredTo().compareTo(sumAssuredAmount) >= 0)
            .toList();
        if (covering.size() > 1) {
            throw new InvalidProductVersionException("Product version " + productVersionId
                + " has " + covering.size() + " SUM_ASSURED_BAND rows covering "
                + sumAssuredAmount.toPlainString()
                + " -- which multiplier applies would depend on row order");
        }
        return covering.stream().findFirst();
    }

    @Override
    public FrequencyLoading resolveFrequencyLoading(UUID productVersionId) {
        return productVersionRepository.findById(productVersionId)
            .filter(v -> v.getTenantId().equals(TenantContext.get()))
            .map(ProductVersion::getFrequencyLoading)
            .orElseThrow(() -> new ProductNotFoundException(productVersionId));
    }

    @Override
    public List<BenefitDefinition> resolveBenefitSchedule(UUID productVersionId) {
        return benefitScheduleEntryRepository.findByProductVersionId(productVersionId).stream()
            .map(b -> new BenefitDefinition(BenefitType.valueOf(b.getBenefitType()),
                BenefitCalculationMethod.valueOf(b.getCalculationMethod()),
                b.getBenefitPercent(), b.getFlatAmount()))
            .toList();
    }

    @Override
    public boolean isPriced(UUID productVersionId) {
        return baseRateRepository.existsByProductVersionId(productVersionId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A null sex or smoker status returns empty rather than guessing a cell. The caller then
     * refuses, which is the point: on a priced product those two facts are half the key to the
     * price, and a policy issued without them would be priced as somebody else.
     */
    @Override
    public Optional<BigDecimal> resolveBaseRatePerMille(UUID productVersionId, int ageAtEntry,
                                                         Sex sex, SmokerStatus smokerStatus, Integer termMonths) {
        if (sex == null || smokerStatus == null) {
            return Optional.empty();
        }
        return baseRateRepository
            .findApplicable(productVersionId, ageAtEntry, sex.name(), smokerStatus.name(), termMonths)
            .map(BaseRate::getRatePerMille);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CashValueConfigView> getCashValueConfig(UUID productVersionId) {
        return cashValueConfigRepository.findById(productVersionId)
            .map(c -> new CashValueConfigView(c.getBasisReference(), c.getBasisDate(),
                c.getPaidUpBasis(), c.getMinYearsForValue()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BigDecimal> resolveCashValuePerMille(UUID productVersionId, int policyYear, Integer ageAtEntry) {
        return cashValueEntryRepository.findApplicable(productVersionId, policyYear, ageAtEntry)
            .map(CashValueEntry::getCashValuePerMille);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BigDecimal> resolvePaidUpPerMille(UUID productVersionId, int policyYear, Integer ageAtEntry) {
        return cashValueEntryRepository.findApplicable(productVersionId, policyYear, ageAtEntry)
            .map(CashValueEntry::getPaidUpPerMille);
    }

    @Override
    public ProductSnapshotView getSnapshotByVersionId(UUID productVersionId) {
        UUID tenantId = TenantContext.get();
        ProductVersion version = productVersionRepository.findById(productVersionId)
            .filter(v -> v.getTenantId().equals(tenantId))
            .orElseThrow(() -> new ProductNotFoundException(productVersionId));
        ProductDefinition definition = productDefinitionRepository.findById(version.getProductId())
            .orElseThrow(() -> new ProductNotFoundException(version.getProductId()));
        return new ProductSnapshotView(version.getProductId(), productVersionId, version.getEffectiveDate(),
            legacyModel(version), version.getGracePeriodDays(), version.getMaxLoanToValuePercent(),
            ProductCategory.valueOf(definition.getCategory()), version.getSurrenderChargeScheduleJson(),
            // The by-version-id lookup carries the bounds too. It is what
            // PolicyController.manualIssue resolves, so omitting them here would leave the
            // issue path unable to see the very bounds its gates are meant to check.
            version.getEligibilityBounds(),
            // And the exclusion windows, for the same reason: this is the lookup policy uses
            // to answer a claim's question about which windows a policy's product carries.
            version.getSuicideExclusionMonths(), version.getPreExistingExclusionMonths(),
            PortfolioCode.valueOf(definition.getPortfolioCode()),
            ProfitabilityBucket.valueOf(version.getExpectedProfitabilityBucket()), modelOverride(version),
            version.getSurvivalInvestmentComponentPercent());
    }

    /** Null for a version published since IFRS 17 I2 retired the field. */
    private static IfrsMeasurementModel legacyModel(ProductVersion version) {
        return version.getIfrsMeasurementModel() != null ? IfrsMeasurementModel.valueOf(version.getIfrsMeasurementModel()) : null;
    }

    private static Ifrs17Model modelOverride(ProductVersion version) {
        return version.getMeasurementModelOverride() != null ? Ifrs17Model.valueOf(version.getMeasurementModelOverride()) : null;
    }

    @Override
    @Transactional
    public void setExclusionPeriods(UUID productVersionId, Integer suicideMonths,
                                     Integer preExistingMonths, String changedBy) {
        // findById then filter by tenant, exactly as getSnapshotByVersionId does -- the
        // repository has no tenant-scoped finder for a version id, and inventing one here would
        // be a second way of asking the same question.
        UUID tenantId = TenantContext.get();
        ProductVersion version = productVersionRepository.findById(productVersionId)
            .filter(v -> v.getTenantId().equals(tenantId))
            .orElseThrow(() -> new ProductNotFoundException(productVersionId));
        version.setExclusionPeriods(suicideMonths, preExistingMonths);
        productVersionRepository.save(version);
    }

    @Override
    @Transactional(readOnly = true)
    public tz.co.nlolo.lifeplatform.product.api.OnlineListingView onlineListing(UUID productId) {
        return toListing(productDefinitionRepository.findByTenantIdAndProductId(TenantContext.get(), productId)
            .orElseThrow(() -> new ProductNotFoundException(productId)));
    }

    @Override
    @Transactional
    public tz.co.nlolo.lifeplatform.product.api.OnlineListingView describeOnline(UUID productId, boolean available,
                                                                                String summary, List<String> benefits) {
        ProductDefinition product = productDefinitionRepository.findByTenantIdAndProductId(TenantContext.get(), productId)
            .orElseThrow(() -> new ProductNotFoundException(productId));
        if (available && !"ACTIVE".equals(product.getStatus())) {
            throw new IllegalArgumentException("Publish a version of the product before offering it online");
        }
        product.describeOnline(available, summary, benefits);
        return toListing(productDefinitionRepository.save(product));
    }

    @Override
    @Transactional(readOnly = true)
    public List<tz.co.nlolo.lifeplatform.product.api.OnlineListingView> listOnlineProducts() {
        return productDefinitionRepository.findByTenantIdAndStatus(TenantContext.get(), "ACTIVE").stream()
            .filter(ProductDefinition::isAvailableOnline)
            .sorted(java.util.Comparator.comparing(ProductDefinition::getProductName))
            .map(ProductApiImpl::toListing)
            .toList();
    }

    /**
     * A version that keeps an account is a savings contract, booked under IFRS 9 -- which the policy register does by
     * portfolio (SAV, DEP, PEN, DANN). In any other portfolio its policies are classified as insurance, where no posting
     * rule books an account's deposits or interest: on the dev ledger 26 such movements sat unposted (found
     * 2026-10-09, the user's Mkakati test). The create form defaults an ENDOWMENT to END, so the mistake was easy.
     */
    static void refuseUnlessASavingsPortfolio(ProductDefinition product, boolean deposit, boolean deferredAnnuity) {
        java.util.Set<String> allowed = deposit ? java.util.Set.of("DEP")
            : deferredAnnuity ? java.util.Set.of("PEN", "DANN")
            : java.util.Set.of("SAV", "PEN");
        if (!allowed.contains(product.getPortfolioCode())) {
            String want = deposit ? "DEP (fixed-term deposits)"
                : deferredAnnuity ? "PEN (pensions) or DANN (deferred annuities)" : "SAV (savings accounts)";
            throw new InvalidProductVersionException("A version that keeps an account belongs in the " + want
                + " portfolio, so its deposits and interest are booked as savings; this product is in "
                + product.getPortfolioCode() + ". Change the product's portfolio while it is a draft, or create it again.");
        }
    }

    @Override
    @Transactional
    public ProductSummaryView changePortfolio(UUID productId, PortfolioCode portfolioCode) {
        ProductDefinition product = productDefinitionRepository.findByTenantIdAndProductId(TenantContext.get(), productId)
            .orElseThrow(() -> new ProductNotFoundException(productId));
        if (!"DRAFT".equals(product.getStatus())) {
            throw new IllegalArgumentException("A published product keeps its portfolio: its policies are already"
                + " classified by it");
        }
        product.changePortfolio(portfolioCode.name());
        return toSummaryView(productDefinitionRepository.save(product));
    }

    private static tz.co.nlolo.lifeplatform.product.api.OnlineListingView toListing(ProductDefinition p) {
        return new tz.co.nlolo.lifeplatform.product.api.OnlineListingView(p.getProductId(), p.getProductName(),
            ProductCategory.valueOf(p.getCategory()), p.getDefaultCurrency(), p.isAvailableOnline(), p.getOnlineSummary(),
            p.getOnlineBenefits());
    }

    private ProductSummaryView toSummaryView(ProductDefinition p) {
        return new ProductSummaryView(p.getProductId(), p.getProductCode(), p.getProductName(),
            ProductCategory.valueOf(p.getCategory()), ProductStatus.valueOf(p.getStatus()), p.getDefaultCurrency(),
            PortfolioCode.valueOf(p.getPortfolioCode()));
    }
}
