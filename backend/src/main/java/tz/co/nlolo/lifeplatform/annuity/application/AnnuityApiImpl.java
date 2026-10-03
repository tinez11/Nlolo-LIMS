package tz.co.nlolo.lifeplatform.annuity.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.annuity.api.AnnuityApi;
import tz.co.nlolo.lifeplatform.annuity.api.AnnuityContractView;
import tz.co.nlolo.lifeplatform.annuity.api.ContractStatus;
import tz.co.nlolo.lifeplatform.annuity.api.DeathValue;
import tz.co.nlolo.lifeplatform.annuity.domain.AnnuityContract;
import tz.co.nlolo.lifeplatform.annuity.domain.DeathArithmetic;
import tz.co.nlolo.lifeplatform.annuity.infrastructure.AnnuityContractRepository;
import tz.co.nlolo.lifeplatform.benefitpayout.api.BenefitPayoutApi;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyDetailView;
import tz.co.nlolo.lifeplatform.policy.api.BeneficiaryType;
import tz.co.nlolo.lifeplatform.policy.api.BeneficiaryView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyNotFoundException;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import tz.co.nlolo.lifeplatform.product.api.AnnuityForm;
import tz.co.nlolo.lifeplatform.product.api.AnnuityFrequencies;
import tz.co.nlolo.lifeplatform.product.api.AnnuityPlan;
import tz.co.nlolo.lifeplatform.product.api.AnnuityPrice;
import tz.co.nlolo.lifeplatform.product.api.AnnuityPricingInput;
import tz.co.nlolo.lifeplatform.product.api.AnnuityPricingRefusedException;
import tz.co.nlolo.lifeplatform.product.api.AnnuityTiming;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.underwriting.api.AnnuityChoice;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

/**
 * The annuity contract's decisions (product step 5): created at issue from the case's choice, locked
 * once when the single premium arrives, and settled at each death. Product is asked first everywhere,
 * so an ordinary policy never reads this module's table.
 */
@Service
public class AnnuityApiImpl implements AnnuityApi {

    private static final Logger log = LoggerFactory.getLogger(AnnuityApiImpl.class);
    static final ZoneId CIVIL_ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    private final AnnuityContractRepository contracts;
    private final ProductApi productApi;
    private final PolicyApi policyApi;
    private final PartyApi partyApi;
    private final UnderwritingApi underwritingApi;
    private final BenefitPayoutApi payouts;

    public AnnuityApiImpl(AnnuityContractRepository contracts, ProductApi productApi, PolicyApi policyApi, PartyApi partyApi,
                          UnderwritingApi underwritingApi, BenefitPayoutApi payouts) {
        this.contracts = contracts;
        this.productApi = productApi;
        this.policyApi = policyApi;
        this.partyApi = partyApi;
        this.underwritingApi = underwritingApi;
        this.payouts = payouts;
    }

    // ---- Reads ----

    @Override
    @Transactional(readOnly = true)
    public boolean isAnnuity(String policyNumber) {
        return versionIfAnnuity(policyNumber).isPresent() && contracts.existsById(policyNumber);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AnnuityContractView> contract(String policyNumber) {
        if (versionIfAnnuity(policyNumber).isEmpty()) {
            return Optional.empty();
        }
        return contracts.findById(policyNumber).map(AnnuityContract::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public AnnuityPrice quote(UUID productVersionId, String formCode, String frequency, BigDecimal purchasePrice,
                              UUID annuitantPartyId, UUID jointLifePartyId) {
        return productApi.priceAnnuity(productVersionId, input(formCode, frequency, purchasePrice, annuitantPartyId,
            jointLifePartyId, LocalDate.now(CIVIL_ZONE)));
    }

    /** The policy's own version, when it is an annuity's -- product first, the cheap gate. */
    private Optional<UUID> versionIfAnnuity(String policyNumber) {
        if (policyNumber == null) {
            return Optional.empty();
        }
        try {
            UUID versionId = policyApi.getPolicy(policyNumber).productVersionId();
            return productApi.resolveAnnuityPlan(versionId).annuity() ? Optional.of(versionId) : Optional.empty();
        } catch (PolicyNotFoundException e) {
            return Optional.empty();
        }
    }

    private AnnuityPricingInput input(String formCode, String frequency, BigDecimal price, UUID annuitant, UUID joint,
                                      LocalDate pricingDate) {
        PartyDetailView a = partyApi.getPartyDetail(annuitant);
        PartyDetailView j = joint != null ? partyApi.getPartyDetail(joint) : null;
        return new AnnuityPricingInput(formCode, frequency, price, a.dateOfBirth(), a.sex() != null ? a.sex().name() : null,
            j != null ? j.dateOfBirth() : null, j != null && j.sex() != null ? j.sex().name() : null, pricingDate);
    }

    // ---- Issue ----

    /**
     * {@code policy.PolicyIssued}: the contract, with its form COPIED from the version and the choice
     * from the case. A policy issued with no case cannot know its form, so it is recorded as LOCK_FAILED
     * rather than dropped (plan R5): visible, paying nothing, cancellable in free-look.
     */
    @Transactional
    public void onIssued(String policyNumber, UUID productVersionId, UUID underwritingCaseId) {
        AnnuityPlan plan = productApi.resolveAnnuityPlan(productVersionId);
        if (!plan.annuity() || contracts.existsById(policyNumber)) {
            return;
        }
        PolicyView policy = policyApi.getPolicy(policyNumber);
        UUID annuitant = policy.lifeAssuredPartyId() != null ? policy.lifeAssuredPartyId() : policy.policyholderPartyId();
        Optional<AnnuityChoice> choice = choiceOf(underwritingCaseId);
        if (choice.isEmpty()) {
            contracts.save(AnnuityContract.failedAtIssue(TenantContext.get(), policyNumber, productVersionId, annuitant,
                policy.sumAssuredAmount(), policy.sumAssuredCurrency(),
                "An annuity is issued from an underwriting case that records its form and frequency, and this policy has none"));
            return;
        }
        AnnuityForm form = plan.form(choice.get().formCode()).orElse(null);
        if (form == null) {
            contracts.save(AnnuityContract.failedAtIssue(TenantContext.get(), policyNumber, productVersionId, annuitant,
                policy.sumAssuredAmount(), policy.sumAssuredCurrency(),
                "The version this policy was issued on does not offer form " + choice.get().formCode()));
            return;
        }
        contracts.save(AnnuityContract.issued(TenantContext.get(), policyNumber, productVersionId, form, plan.timing().name(),
            plan.proofOfLifeIntervalMonths(), choice.get().frequency(), annuitant, choice.get().jointLifePartyId(),
            policy.sumAssuredAmount(), policy.sumAssuredCurrency()));
    }

    /**
     * The case's choice; none when the policy names no case, or a case this tenant does not have.
     * annuityChoice answers that as empty rather than throwing -- a throw through it would mark this
     * transaction rollback-only even if caught here.
     */
    private Optional<AnnuityChoice> choiceOf(UUID underwritingCaseId) {
        return underwritingCaseId == null ? Optional.empty() : underwritingApi.annuityChoice(underwritingCaseId);
    }

    // ---- The lock ----

    /**
     * {@code billing.PremiumCollected}: price the purchase on the collection date, lock the income once,
     * and open the stream. Only a contract AWAITING_PAYMENT locks; a redelivered collection does nothing.
     */
    @Transactional
    public void onPremiumCollected(String policyNumber, LocalDate collectedOn) {
        if (versionIfAnnuity(policyNumber).isEmpty()) {
            return;
        }
        AnnuityContract c = contracts.findById(policyNumber).orElse(null);
        if (c == null || c.status() != ContractStatus.AWAITING_PAYMENT) {
            return;
        }
        AnnuityPrice price;
        try {
            price = productApi.priceAnnuity(c.getProductVersionId(), input(c.getFormCode(), c.getFrequency(),
                c.getPurchasePrice(), c.getAnnuitantPartyId(), c.getJointLifePartyId(), collectedOn));
        } catch (AnnuityPricingRefusedException e) {
            log.error("Annuity {} could not be priced at the lock: {}", policyNumber, e.getMessage());
            c.lockFailed(e.getMessage());
            contracts.save(c);
            return;
        }
        LocalDate firstDue = AnnuityTiming.valueOf(c.getTiming()) == AnnuityTiming.ADVANCE
            ? collectedOn : collectedOn.plusMonths(AnnuityFrequencies.monthsPer(c.getFrequency()));
        LocalDate guaranteeEnd = c.getGuaranteeYears() != null && c.getGuaranteeYears() > 0
            ? firstDue.plusYears(c.getGuaranteeYears()) : null;
        c.lock(price, collectedOn, firstDue, guaranteeEnd);
        contracts.save(c);
        payouts.openAnnuityStream(policyNumber, firstDue, c.getFrequency(), price.instalment(), c.getCurrency(),
            c.getEscalationPercent(), c.getProofOfLifeIntervalMonths());
    }

    // ---- Free-look ----

    /** Cancelled in free-look: nothing further is paid, and the contract never was. */
    @Transactional
    public void onFreeLookCancelled(String policyNumber) {
        contracts.findById(policyNumber).ifPresent(c -> {
            payouts.endAnnuityStream(policyNumber, LocalDate.of(1900, 1, 1), "Cancelled in the free-look period");
            c.cancel();
            contracts.save(c);
        });
    }

    /** {@code policy.AnnuityEnded}: a guarantee paid out (or the last death with none) -- the contract has ended. */
    @Transactional
    public void onPolicyEnded(String policyNumber) {
        contracts.findById(policyNumber).filter(c -> c.status() != ContractStatus.ENDED).ifPresent(c -> {
            c.ended();
            contracts.save(c);
        });
    }

    // ---- Death ----

    @Override
    @Transactional(readOnly = true)
    public DeathValue deathValue(String policyNumber, UUID deceasedPartyId, LocalDate dateOfDeath) {
        AnnuityContract c = contracts.findById(policyNumber)
            .orElseThrow(() -> new IllegalArgumentException("Policy " + policyNumber + " is not an annuity"));
        UUID deceased = requireALife(c, deceasedPartyId);
        if (!isLastDeath(c, deceased) || c.getFirstDueDate() == null) {
            return new DeathValue(BigDecimal.ZERO.setScale(2), c.getCurrency());
        }
        return new DeathValue(lastDeathOutcome(c, dateOfDeath).refundPayable(), c.getCurrency());
    }

    /**
     * An approved DEATH claim: the first of two lives switches the stream to the survivor percentage;
     * the last (or only) life continues the guarantee to the beneficiaries or ends the stream. Once per
     * claim -- a redelivered approval changes nothing.
     */
    @Transactional
    public void onDeathApproved(String policyNumber, UUID deceasedPartyId, LocalDate dateOfDeath, UUID claimId) {
        AnnuityContract c = contracts.findById(policyNumber).orElse(null);
        if (c == null || claimId.equals(c.getFirstDeathClaimId()) || claimId.equals(c.getLastDeathClaimId())) {
            return;
        }
        if (c.status() != ContractStatus.IN_PAYMENT && c.status() != ContractStatus.SURVIVOR) {
            log.warn("Death approved on annuity {} in status {}; nothing to change", policyNumber, c.status());
            return;
        }
        UUID deceased = requireALife(c, deceasedPartyId);
        LocalDate nextDue = nextDueAfter(c, dateOfDeath);
        if (!isLastDeath(c, deceased)) {
            payouts.reduceAnnuityStream(policyNumber, nextDue, c.getSurvivorPercent());
            c.recordFirstDeath(deceased, dateOfDeath, claimId);
            contracts.save(c);
            return;
        }
        DeathArithmetic.Outcome outcome = lastDeathOutcome(c, dateOfDeath);
        boolean guaranteeContinues = c.getGuaranteeEndDate() != null && c.getGuaranteeEndDate().isAfter(dateOfDeath);
        if (guaranteeContinues) {
            payouts.redirectAnnuityStream(policyNumber, nextDue, c.getGuaranteeEndDate(), beneficiaryPayee(policyNumber));
        } else {
            payouts.endAnnuityStream(policyNumber, dateOfDeath, "The annuitant died on " + dateOfDeath);
        }
        c.recordLastDeath(dateOfDeath, claimId, guaranteeContinues, outcome.overpayment());
        contracts.save(c);
        if (!guaranteeContinues) {
            policyApi.endAnnuity(policyNumber);
        }
    }

    /** The last death: a single-life form, or a joint form whose other life already died. */
    private static boolean isLastDeath(AnnuityContract c, UUID deceased) {
        return !c.isJoint() || c.getFirstDeathPartyId() != null && !c.getFirstDeathPartyId().equals(deceased);
    }

    private static UUID requireALife(AnnuityContract c, UUID deceasedPartyId) {
        UUID deceased = deceasedPartyId != null ? deceasedPartyId : c.getAnnuitantPartyId();
        if (!deceased.equals(c.getAnnuitantPartyId()) && !deceased.equals(c.getJointLifePartyId())) {
            throw new IllegalArgumentException("The deceased named on this claim is not a life on annuity " + c.getPolicyNumber());
        }
        return deceased;
    }

    private DeathArithmetic.Outcome lastDeathOutcome(AnnuityContract c, LocalDate dateOfDeath) {
        String policy = c.getPolicyNumber();
        BigDecimal paidAfter = payouts.annuityPaidGrossDueAfter(policy, dateOfDeath);
        BigDecimal paidBefore = payouts.annuityPaidGross(policy).subtract(paidAfter);
        BigDecimal guaranteed = c.getGuaranteeEndDate() != null && c.getGuaranteeEndDate().isAfter(dateOfDeath)
            ? payouts.annuityScheduledGrossBetween(policy, dateOfDeath, c.getGuaranteeEndDate())
            : BigDecimal.ZERO.setScale(2);
        return DeathArithmetic.lastDeath(c.isCapitalProtected(), c.getPurchasePrice(), paidBefore, guaranteed, paidAfter);
    }

    /** The first due date strictly after the death, on the contract's own calendar. */
    private static LocalDate nextDueAfter(AnnuityContract c, LocalDate date) {
        int months = AnnuityFrequencies.monthsPer(c.getFrequency());
        for (int n = 0; ; n++) {
            LocalDate due = c.getFirstDueDate().plusMonths((long) n * months);
            if (due.isAfter(date)) {
                return due;
            }
        }
    }

    /** The first named beneficiary's phone, or null -- each instalment then waits for a reviewer to enter one. */
    private String beneficiaryPayee(String policyNumber) {
        for (BeneficiaryView b : policyApi.getPolicy(policyNumber).beneficiaries()) {
            if (b.type() == BeneficiaryType.PARTY && b.partyId() != null) {
                String phone = partyApi.getPartyDetail(b.partyId()).phoneNumber();
                if (phone != null && !phone.isBlank()) {
                    return phone;
                }
            }
        }
        return null;
    }
}
