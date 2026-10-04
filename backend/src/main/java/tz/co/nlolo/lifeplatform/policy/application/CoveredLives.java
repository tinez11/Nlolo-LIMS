package tz.co.nlolo.lifeplatform.policy.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyDetailView;
import tz.co.nlolo.lifeplatform.policy.api.CoveredLifeView;
import tz.co.nlolo.lifeplatform.policy.api.InvalidPolicyStateException;
import tz.co.nlolo.lifeplatform.policy.domain.CoveredLife;
import tz.co.nlolo.lifeplatform.policy.domain.FuneralPolicy;
import tz.co.nlolo.lifeplatform.policy.domain.Policy;
import tz.co.nlolo.lifeplatform.policy.infrastructure.CoveredLifeRepository;
import tz.co.nlolo.lifeplatform.policy.infrastructure.FuneralPolicyRepository;
import tz.co.nlolo.lifeplatform.product.api.FuneralPlan;
import tz.co.nlolo.lifeplatform.product.api.FuneralQuote;
import tz.co.nlolo.lifeplatform.product.api.FuneralQuoteLine;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.underwriting.api.FuneralApplication;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Every operation on a funeral policy's lives (family funeral cover): written at issue, read for the
 * policy screen and claims. Its own component so PolicyApiImpl does not grow past its 3,300 lines; called
 * only from inside PolicyApiImpl's transactions and only once the product says FUNERAL.
 */
@Component
class CoveredLives {

    private final CoveredLifeRepository lives;
    private final FuneralPolicyRepository funeralPolicies;
    private final ProductApi productApi;
    private final PartyApi partyApi;

    CoveredLives(CoveredLifeRepository lives, FuneralPolicyRepository funeralPolicies, ProductApi productApi,
                 PartyApi partyApi) {
        this.lives = lives;
        this.funeralPolicies = funeralPolicies;
        this.productApi = productApi;
        this.partyApi = partyApi;
    }

    boolean isFuneral(Policy policy) {
        return productApi.resolveFuneralPlan(policy.getProductVersionId()).funeral();
    }

    /**
     * The family the case was accepted on, one row per life: the main member (the life assured, a party)
     * first, then each dependant from the quote's lines in the order listed. Every life's cover starts with
     * the policy's.
     */
    void recordAtIssue(Policy policy, FuneralApplication application, String recordedBy) {
        UUID tenantId = TenantContext.get();
        if (funeralPolicies.findById(policy.getPolicyNumber()).isPresent()) {
            throw new InvalidPolicyStateException("Policy " + policy.getPolicyNumber() + " already has its covered lives");
        }
        FuneralQuote quote = application.quote();
        if (quote == null) {
            throw new InvalidPolicyStateException("The family on case " + policy.getUnderwritingCaseId()
                + " no longer prices on plan " + application.planCode() + "; re-record the application");
        }
        funeralPolicies.saveAndFlush(new FuneralPolicy(tenantId, policy.getPolicyNumber(), application.planCode()));
        LocalDate coverStart = policy.getCommencementDate() != null ? policy.getCommencementDate() : policy.getIssueDate();
        UUID mainMemberId = policy.getLifeAssuredPartyId() != null ? policy.getLifeAssuredPartyId() : policy.getPolicyholderPartyId();
        PartyDetailView mainMember = partyApi.getPartyDetail(mainMemberId);

        // The quote lists the main member first, then the dependants in the application's order.
        List<FuneralQuoteLine> lines = quote.lines();
        FuneralQuoteLine main = lines.get(0);
        lives.save(new CoveredLife(tenantId, policy.getPolicyNumber(), FuneralRole.MAIN_MEMBER.name(), mainMember.displayName(),
            mainMember.dateOfBirth(), mainMember.sex() != null ? mainMember.sex().name() : null, null, false, mainMemberId,
            main.benefit(), main.yearlyPremium(), main.age(), coverStart, recordedBy));
        for (int i = 0; i < application.dependants().size(); i++) {
            FuneralApplication.Life dependant = application.dependants().get(i);
            FuneralQuoteLine line = lines.get(i + 1);
            lives.save(new CoveredLife(tenantId, policy.getPolicyNumber(), dependant.role().name(), dependant.fullName(),
                dependant.dateOfBirth(), dependant.sex(), dependant.idNumber(), dependant.student(), null,
                line.benefit(), line.yearlyPremium(), line.age(), coverStart, recordedBy));
        }
    }

    List<CoveredLifeView> list(Policy policy) {
        FuneralPlan plan = productApi.resolveFuneralPlan(policy.getProductVersionId());
        return lives.findByPolicy(TenantContext.get(), policy.getPolicyNumber()).stream()
            .map(life -> toView(life, plan)).toList();
    }

    static CoveredLifeView toView(CoveredLife life, FuneralPlan plan) {
        LocalDate waitingEnds = plan.waitingPeriodMonths() != null ? life.getCoverStart().plusMonths(plan.waitingPeriodMonths()) : null;
        return new CoveredLifeView(life.getCoveredLifeId(), FuneralRole.valueOf(life.getRole()), life.getFullName(),
            life.getDateOfBirth(), life.getSex(), life.getIdNumber(), life.isStudent(), life.getPartyId(), life.getBenefit(),
            life.getYearlyPremium(), life.getPricedAtAge(), life.getCoverStart(), waitingEnds, life.getCoverEnd(),
            life.getStatus(), life.getEndReason(), life.getEndedOn());
    }
}
