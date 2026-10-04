package tz.co.nlolo.lifeplatform.underwriting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyDetailView;
import tz.co.nlolo.lifeplatform.product.api.FuneralLifeInput;
import tz.co.nlolo.lifeplatform.product.api.FuneralQuote;
import tz.co.nlolo.lifeplatform.product.api.FuneralQuoteInput;
import tz.co.nlolo.lifeplatform.product.api.FuneralQuoteRefusedException;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import tz.co.nlolo.lifeplatform.product.api.PremiumFrequency;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.underwriting.api.DecisionOutcome;
import tz.co.nlolo.lifeplatform.underwriting.api.FuneralApplication;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingValidationException;
import tz.co.nlolo.lifeplatform.underwriting.domain.FuneralApplicationEntity;
import tz.co.nlolo.lifeplatform.underwriting.domain.FuneralApplicationLifeEntity;
import tz.co.nlolo.lifeplatform.underwriting.domain.UnderwritingCase;
import tz.co.nlolo.lifeplatform.underwriting.infrastructure.FuneralApplicationLifeRepository;
import tz.co.nlolo.lifeplatform.underwriting.infrastructure.FuneralApplicationRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A funeral case's plan and dependants (underwriting V16). Its own component so UnderwritingApiImpl's
 * constructor does not grow again; reached only from UnderwritingApiImpl, inside its transactions, and only
 * after the product says FUNERAL. Every price comes from {@link ProductApi#quoteFuneral} -- never computed here.
 */
@Component
class FuneralApplications {

    private static final ZoneId CIVIL_ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    private final FuneralApplicationRepository applications;
    private final FuneralApplicationLifeRepository lives;
    private final ProductApi productApi;
    private final PartyApi partyApi;

    FuneralApplications(FuneralApplicationRepository applications, FuneralApplicationLifeRepository lives,
                        ProductApi productApi, PartyApi partyApi) {
        this.applications = applications;
        this.lives = lives;
        this.productApi = productApi;
        this.partyApi = partyApi;
    }

    boolean isFuneral(UnderwritingCase underwritingCase) {
        return productApi.resolveFuneralPlan(underwritingCase.getProductVersionId()).funeral();
    }

    /** Records (or replaces) the plan and dependants, once the whole family prices and the sum assured agrees. */
    FuneralApplication record(UnderwritingCase underwritingCase, String planCode, List<FuneralApplication.Life> dependants,
                              String recordedBy) {
        List<FuneralApplication.Life> family = dependants != null ? dependants : List.of();
        for (FuneralApplication.Life life : family) {
            if (life.role() == null || life.role() == FuneralRole.MAIN_MEMBER) {
                throw new UnderwritingValidationException(
                    "Each dependant needs a role other than main member: the main member is the life assured");
            }
            if (life.fullName() == null || life.fullName().isBlank()) {
                throw new UnderwritingValidationException("Each dependant needs a full name");
            }
        }
        FuneralQuote quote = quoteOrRefuse(underwritingCase, planCode, family);
        BigDecimal sumAssured = underwritingCase.getSumAssuredAmount();
        // Plan R3: the case's sum assured IS the main member's plan benefit.
        if (sumAssured == null || sumAssured.compareTo(quote.mainMemberBenefit()) != 0) {
            throw new UnderwritingValidationException("The sum assured on a funeral case is the main member's benefit on plan "
                + planCode + ": " + money(quote.mainMemberBenefit()) + " " + underwritingCase.getSumAssuredCurrency()
                + ", not " + (sumAssured == null ? "nothing" : money(sumAssured)));
        }
        UUID tenantId = TenantContext.get();
        FuneralApplicationEntity application = applications.findById(underwritingCase.getCaseId())
            .orElseGet(() -> new FuneralApplicationEntity(tenantId, underwritingCase.getCaseId()));
        application.choose(planCode, recordedBy);
        applications.saveAndFlush(application);
        lives.deleteByCaseId(underwritingCase.getCaseId());
        for (int i = 0; i < family.size(); i++) {
            lives.save(new FuneralApplicationLifeEntity(tenantId, underwritingCase.getCaseId(), i, family.get(i)));
        }
        return new FuneralApplication(planCode, family, quote);
    }

    /** The application with a fresh quote; the quote is null when the family no longer prices (a life aged out). */
    Optional<FuneralApplication> read(UnderwritingCase underwritingCase) {
        return applications.findById(underwritingCase.getCaseId()).map(application -> {
            List<FuneralApplication.Life> family = lives.findByCaseIdOrderByPosition(underwritingCase.getCaseId()).stream()
                .map(FuneralApplicationLifeEntity::toLife).toList();
            FuneralQuote quote;
            try {
                quote = quote(underwritingCase, application.getPlanCode(), family);
            } catch (FuneralQuoteRefusedException e) {
                quote = null;
            }
            return new FuneralApplication(application.getPlanCode(), family, quote);
        });
    }

    /**
     * Plan R2 and the acceptance re-quote: never LOADED (the table is the price); accepted only with an
     * application whose family still prices today -- a life may have aged out of entry since it was recorded.
     */
    void checkDecision(UnderwritingCase underwritingCase, DecisionOutcome outcome) {
        if (outcome == DecisionOutcome.LOADED) {
            throw new UnderwritingValidationException(
                "A funeral plan is priced by its premium table; accept, decline or postpone it");
        }
        if (outcome != DecisionOutcome.ACCEPT) {
            return;
        }
        FuneralApplicationEntity application = applications.findById(underwritingCase.getCaseId())
            .orElseThrow(() -> new UnderwritingValidationException(
                "A funeral case must record its plan and lives before it is accepted"));
        List<FuneralApplication.Life> family = lives.findByCaseIdOrderByPosition(underwritingCase.getCaseId()).stream()
            .map(FuneralApplicationLifeEntity::toLife).toList();
        quoteOrRefuse(underwritingCase, application.getPlanCode(), family);
    }

    private FuneralQuote quoteOrRefuse(UnderwritingCase underwritingCase, String planCode,
                                       List<FuneralApplication.Life> family) {
        try {
            return quote(underwritingCase, planCode, family);
        } catch (FuneralQuoteRefusedException e) {
            throw new UnderwritingValidationException(e.getMessage());
        }
    }

    /** The main member (the life assured, from the party record) first, then the dependants in order. */
    private FuneralQuote quote(UnderwritingCase underwritingCase, String planCode, List<FuneralApplication.Life> family) {
        UUID mainMemberId = underwritingCase.getLifeAssuredPartyId() != null
            ? underwritingCase.getLifeAssuredPartyId() : underwritingCase.getApplicantPartyId();
        PartyDetailView mainMember = partyApi.getPartyDetail(mainMemberId);
        if (mainMember.dateOfBirth() == null) {
            throw new UnderwritingValidationException("The main member's date of birth is not recorded");
        }
        List<FuneralLifeInput> inputs = new ArrayList<>();
        inputs.add(new FuneralLifeInput(FuneralRole.MAIN_MEMBER, mainMember.displayName(), mainMember.dateOfBirth(), false));
        family.forEach(l -> inputs.add(new FuneralLifeInput(l.role(), l.fullName(), l.dateOfBirth(), l.student())));
        LocalDate asOf = underwritingCase.getProposedCommencementDate() != null
            ? underwritingCase.getProposedCommencementDate() : LocalDate.now(CIVIL_ZONE);
        PremiumFrequency frequency = underwritingCase.getPremiumFrequency() != null
            ? PremiumFrequency.valueOf(underwritingCase.getPremiumFrequency()) : PremiumFrequency.MONTHLY;
        return productApi.quoteFuneral(underwritingCase.getProductVersionId(),
            new FuneralQuoteInput(planCode, frequency, asOf, inputs));
    }

    private static String money(BigDecimal amount) {
        return String.format(java.util.Locale.ROOT, "%,.2f", amount);
    }
}
