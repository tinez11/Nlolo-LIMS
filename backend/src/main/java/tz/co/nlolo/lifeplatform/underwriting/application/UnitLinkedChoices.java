package tz.co.nlolo.lifeplatform.underwriting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan;
import tz.co.nlolo.lifeplatform.underwriting.api.DecisionOutcome;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingValidationException;
import tz.co.nlolo.lifeplatform.underwriting.api.UnitLinkedChoice;
import tz.co.nlolo.lifeplatform.underwriting.domain.UnderwritingCase;
import tz.co.nlolo.lifeplatform.underwriting.domain.UnitLinkedChoiceEntity;
import tz.co.nlolo.lifeplatform.underwriting.domain.UnitLinkedChoiceSplitEntity;
import tz.co.nlolo.lifeplatform.underwriting.infrastructure.UnitLinkedChoiceRepository;
import tz.co.nlolo.lifeplatform.underwriting.infrastructure.UnitLinkedChoiceSplitRepository;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A unit-linked case's fund split, premium and sum assured (underwriting V17; spec §4). Its own component, as
 * FuneralApplications is; reached only from UnderwritingApiImpl inside its transactions, and only after the
 * product says UNIT_LINKED. Every rule it checks is the version's -- the funds offered, the premium floors, the
 * sum-assured multiples -- read through {@link ProductApi#resolveUnitLinkedPlan}.
 */
@Component
class UnitLinkedChoices {

    /** Premiums a year, by the platform's own frequencies (PremiumFrequency). A single premium counts once. */
    private static final Map<String, Integer> PERIODS_PER_YEAR = Map.of("MONTHLY", 12, "QUARTERLY", 4, "ANNUALLY", 1, "SINGLE", 1);

    private final UnitLinkedChoiceRepository choices;
    private final UnitLinkedChoiceSplitRepository splits;
    private final ProductApi productApi;

    UnitLinkedChoices(UnitLinkedChoiceRepository choices, UnitLinkedChoiceSplitRepository splits, ProductApi productApi) {
        this.choices = choices;
        this.splits = splits;
        this.productApi = productApi;
    }

    boolean isUnitLinked(UnderwritingCase underwritingCase) {
        return productApi.resolveUnitLinkedPlan(underwritingCase.getProductVersionId()).unitLinked();
    }

    /**
     * Records (or replaces) the choice once it fits the version. The sum assured must be the case's own -- the
     * figure medical underwriting assesses -- so the choice can never quietly change the cover being decided.
     */
    UnitLinkedChoice record(UnderwritingCase underwritingCase, UnitLinkedChoice choice, String recordedBy) {
        UnitLinkedPlan plan = productApi.resolveUnitLinkedPlan(underwritingCase.getProductVersionId());
        String currency = underwritingCase.getSumAssuredCurrency();
        check(plan, choice, currency);
        BigDecimal caseSumAssured = underwritingCase.getSumAssuredAmount();
        if (caseSumAssured == null || caseSumAssured.compareTo(choice.sumAssured()) != 0) {
            throw new UnderwritingValidationException("The sum assured on a unit-linked case is the cover the customer chose: "
                + money(choice.sumAssured()) + " " + currency + ", but the case is for "
                + (caseSumAssured == null ? "nothing" : money(caseSumAssured)));
        }
        UUID tenantId = TenantContext.get();
        UnitLinkedChoiceEntity entity = choices.findById(underwritingCase.getCaseId())
            .orElseGet(() -> new UnitLinkedChoiceEntity(tenantId, underwritingCase.getCaseId()));
        entity.choose(choice.premium(), choice.frequency(), choice.sumAssured(), recordedBy);
        choices.saveAndFlush(entity);
        splits.deleteByCaseId(underwritingCase.getCaseId());
        for (UnitLinkedChoice.Split s : choice.split()) {
            splits.save(new UnitLinkedChoiceSplitEntity(tenantId, underwritingCase.getCaseId(),
                new UnitLinkedChoice.Split(s.fundCode().trim().toUpperCase(), s.percent())));
        }
        return read(underwritingCase).orElseThrow();
    }

    Optional<UnitLinkedChoice> read(UnderwritingCase underwritingCase) {
        return choices.findById(underwritingCase.getCaseId()).map(c -> new UnitLinkedChoice(
            splits.findByCaseIdOrderByFundCode(c.getCaseId()).stream().map(UnitLinkedChoiceSplitEntity::toSplit).toList(),
            c.getPremiumAmount(), c.getPremiumFrequency(), c.getSumAssured()));
    }

    /**
     * Nothing to load (the premium is the customer's choice, and the cost of insurance is charged from units), so
     * LOADED is refused; an acceptance needs a choice that still fits the version.
     */
    void checkDecision(UnderwritingCase underwritingCase, DecisionOutcome outcome) {
        if (outcome == DecisionOutcome.LOADED) {
            throw new UnderwritingValidationException("A unit-linked policy's premium is the customer's own choice and its"
                + " cost of insurance is charged from its units; accept, decline or postpone it");
        }
        if (outcome != DecisionOutcome.ACCEPT) {
            return;
        }
        UnitLinkedChoice choice = read(underwritingCase).orElseThrow(() -> new UnderwritingValidationException(
            "A unit-linked case must record its fund split, premium and sum assured before it is accepted"));
        check(productApi.resolveUnitLinkedPlan(underwritingCase.getProductVersionId()), choice,
            underwritingCase.getSumAssuredCurrency());
    }

    private static void check(UnitLinkedPlan plan, UnitLinkedChoice choice, String currency) {
        if (choice == null || choice.split().isEmpty()) {
            throw new UnderwritingValidationException("A unit-linked case says how each premium is split across the funds");
        }
        Set<String> seen = new HashSet<>();
        int total = 0;
        for (UnitLinkedChoice.Split s : choice.split()) {
            String code = s.fundCode() == null ? "" : s.fundCode().trim().toUpperCase();
            if (!plan.offersFund(code)) {
                throw new UnderwritingValidationException("Fund " + code + " is not offered by this product; it offers "
                    + String.join(", ", plan.fundCodes()));
            }
            if (!seen.add(code)) {
                throw new UnderwritingValidationException("Fund " + code + " appears twice in the split");
            }
            if (s.percent() < 1 || s.percent() > 100) {
                throw new UnderwritingValidationException("Each fund's share is a whole percent from 1 to 100");
            }
            total += s.percent();
        }
        if (total != 100) {
            throw new UnderwritingValidationException("The fund split totals " + total + "%; it must total 100%");
        }
        String frequency = choice.frequency();
        BigDecimal minimum = frequency == null ? null : plan.minimumPremium(frequency).orElse(null);
        if (minimum == null) {
            throw new UnderwritingValidationException("This product does not take " + frequency + " premiums");
        }
        if (choice.premium() == null || choice.premium().compareTo(minimum) < 0) {
            throw new UnderwritingValidationException("The " + frequency + " premium is at least " + money(minimum) + " " + currency);
        }
        if (choice.sumAssured() == null) {
            throw new UnderwritingValidationException("A unit-linked case needs the sum assured the customer chose");
        }
        BigDecimal annual = choice.premium().multiply(BigDecimal.valueOf(PERIODS_PER_YEAR.get(frequency)));
        BigDecimal low = annual.multiply(plan.sumAssuredMultipleMin());
        BigDecimal high = annual.multiply(plan.sumAssuredMultipleMax());
        if (choice.sumAssured().compareTo(low) < 0 || choice.sumAssured().compareTo(high) > 0) {
            throw new UnderwritingValidationException("The sum assured must be between " + money(low) + " and " + money(high)
                + " " + currency + " (" + plain(plan.sumAssuredMultipleMin()) + "x to " + plain(plan.sumAssuredMultipleMax())
                + "x the " + ("SINGLE".equals(frequency) ? "single premium" : "annual premium") + " of " + money(annual) + ")");
        }
    }

    private static String money(BigDecimal amount) {
        return String.format("%,.2f", amount);
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

}
