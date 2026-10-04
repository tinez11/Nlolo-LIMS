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
import tz.co.nlolo.lifeplatform.annuity.api.VestingInstructionInput;
import tz.co.nlolo.lifeplatform.annuity.api.VestingRefusedException;
import tz.co.nlolo.lifeplatform.annuity.api.VestingView;
import tz.co.nlolo.lifeplatform.annuity.domain.VestingRules;
import tz.co.nlolo.lifeplatform.annuity.domain.DeathArithmetic;
import tz.co.nlolo.lifeplatform.annuity.domain.Vesting;
import tz.co.nlolo.lifeplatform.annuity.domain.VestingInstruction;
import tz.co.nlolo.lifeplatform.annuity.infrastructure.AnnuityContractRepository;
import tz.co.nlolo.lifeplatform.annuity.infrastructure.VestingInstructionRepository;
import tz.co.nlolo.lifeplatform.annuity.infrastructure.VestingRepository;
import tz.co.nlolo.lifeplatform.product.api.VestingTerms;
import tz.co.nlolo.lifeplatform.underwriting.api.DeferredAnnuityChoice;
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
    private final VestingRepository vestings;
    private final VestingInstructionRepository instructions;

    public AnnuityApiImpl(AnnuityContractRepository contracts, ProductApi productApi, PolicyApi policyApi, PartyApi partyApi,
                          UnderwritingApi underwritingApi, BenefitPayoutApi payouts, VestingRepository vestings,
                          VestingInstructionRepository instructions) {
        this.contracts = contracts;
        this.vestings = vestings;
        this.instructions = instructions;
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
        // A deferred annuity (D2) issues as an account policy and has no form until it vests.
        if (plan.deferred()) {
            onDeferredIssued(policyNumber, productVersionId, underwritingCaseId, plan.vesting());
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
     * A deferred annuity's contract (D2): ACCUMULATING from issue, with its vesting -- the target and
     * window from the SOLD version, the dates from the date of birth confirmed at acceptance. A
     * policy issued with no case, or a case with no confirmed retirement age, cannot know when it
     * vests, so it is recorded as failed at issue, as D1 does for a missing form.
     */
    private void onDeferredIssued(String policyNumber, UUID productVersionId, UUID underwritingCaseId, VestingTerms terms) {
        PolicyView policy = policyApi.getPolicy(policyNumber);
        UUID annuitant = policy.lifeAssuredPartyId() != null ? policy.lifeAssuredPartyId() : policy.policyholderPartyId();
        Optional<DeferredAnnuityChoice> choice = underwritingCaseId == null ? Optional.empty()
            : underwritingApi.deferredAnnuityChoice(underwritingCaseId);
        if (choice.isEmpty() || choice.get().ageEvidenceConfirmedBy() == null) {
            // The contribution stands in for the price D1 records here: a failed contract is never priced.
            contracts.save(AnnuityContract.failedAtIssue(TenantContext.get(), policyNumber, productVersionId, annuitant,
                policy.sumAssuredAmount(), policy.sumAssuredCurrency(),
                "A deferred annuity is issued from an underwriting case that records the retirement age and confirmed "
                    + "proof of age, and this policy has none"));
            return;
        }
        DeferredAnnuityChoice c = choice.get();
        contracts.saveAndFlush(AnnuityContract.accumulating(TenantContext.get(), policyNumber, productVersionId, annuitant,
            policy.sumAssuredCurrency()));
        vestings.save(new Vesting(TenantContext.get(), policyNumber, c.retirementAge(), terms, c.confirmedDateOfBirth(),
            c.confirmedSex(), c.ageEvidenceConfirmedBy(), c.ageEvidenceConfirmedAt()));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean decidesDeath(String policyNumber) {
        if (versionIfAnnuity(policyNumber).isEmpty()) {
            return false;
        }
        return contracts.findById(policyNumber).map(c -> !(c.isDeferred() && c.getVestedOn() == null)).orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VestingView> vesting(String policyNumber) {
        if (versionIfAnnuity(policyNumber).isEmpty()) {
            return Optional.empty();
        }
        return contracts.findById(policyNumber).filter(AnnuityContract::isDeferred)
            .flatMap(c -> vestings.findById(policyNumber).map(v -> viewOf(c, v)));
    }

    VestingView viewOf(AnnuityContract c, Vesting v) {
        Optional<VestingInstruction> i = instructions.findByPolicyNumberAndCurrentTrue(c.getPolicyNumber());
        return new VestingView(c.getPolicyNumber(), v.getTargetDate(), v.getEarliestVestingDate(), v.getLatestVestingDate(),
            i.map(VestingInstruction::getVestingDate).orElse(v.getTargetDate()),
            i.map(VestingInstruction::getFormCode).orElse(v.getDefaultFormCode()),
            i.map(VestingInstruction::getFrequency).orElse(v.getDefaultFrequency()),
            i.map(VestingInstruction::getJointLifePartyId).orElse(null),
            i.map(VestingInstruction::getLumpSumPercent).orElse(BigDecimal.ZERO.setScale(4)),
            v.getMaxCommutationPercent(), i.isPresent(), i.map(VestingInstruction::getContributions).orElse(null),
            v.getHoldReason(), v.getHeldAt(), c.getVestedOn(), v.getVestedBalance(), v.getLumpSum(),
            v.getAgeConfirmedBy(), v.getConfirmedDateOfBirth(), v.getConfirmedSex(), c.getCurrency());
    }

    // ---- The vesting instruction (D2) ----

    @Override
    @Transactional
    public VestingView recordVestingInstruction(String policyNumber, VestingInstructionInput in, String recordedBy) {
        AnnuityContract c = savingContract(policyNumber);
        Vesting v = vestings.findById(policyNumber).orElseThrow();
        LocalDate today = LocalDate.now(CIVIL_ZONE);
        LocalDate vestingDate = in.vestingDate() != null ? in.vestingDate() : v.getTargetDate();
        // Spec Q7: the form and frequency must be on the product's CURRENT version -- the one that will price it.
        PolicyView policy = policyApi.getPolicy(policyNumber);
        UUID currentVersion = productApi.getActiveSnapshot(policy.productId(), vestingDate).productVersionId();
        AnnuityPlan current = productApi.resolveAnnuityPlan(currentVersion);
        AnnuityForm form = current.form(in.formCode()).orElseThrow(() ->
            new VestingRefusedException("This version does not offer form " + in.formCode()));
        if (current.frequency(in.frequency()).isEmpty()) {
            throw new VestingRefusedException("This version does not offer " + in.frequency() + " payments");
        }
        checkJointLife(form, in.jointLifePartyId(), c.getAnnuitantPartyId());
        try {
            VestingRules.check(in, vestingDate, today, v.getTargetDate(), v.getEarliestVestingDate(),
                v.getLatestVestingDate(), v.getMaxCommutationPercent());
        } catch (IllegalArgumentException e) {
            throw new VestingRefusedException(e.getMessage());
        }
        if ("CONTINUE".equals(in.contributions())) {
            try {
                policyApi.extendPremiumPayingTerm(policyNumber, vestingDate);
            } catch (tz.co.nlolo.lifeplatform.policy.api.InvalidPolicyStateException e) {
                throw new VestingRefusedException(e.getMessage());
            }
        }
        Optional<VestingInstruction> previous = instructions.findByPolicyNumberAndCurrentTrue(policyNumber);
        LocalDate previousDate = previous.map(VestingInstruction::getVestingDate).orElse(v.getTargetDate());
        previous.ifPresent(p -> {
            p.supersede();
            instructions.saveAndFlush(p);
        });
        instructions.save(new VestingInstruction(TenantContext.get(), policyNumber, vestingDate, in.formCode(), in.frequency(),
            in.jointLifePartyId(), in.lumpSumPercent(), in.contributions(), recordedBy));
        // A new instruction is a new attempt: whatever held the last one is for the next sweep to judge.
        v.clearHold();
        if (!vestingDate.equals(previousDate)) {
            v.rearmReminders();
        }
        vestings.save(v);
        return viewOf(c, v);
    }

    /** D1's joint-life rules (underwriting's recordAnnuityChoice), for a form chosen at vesting. */
    private void checkJointLife(AnnuityForm form, UUID jointLifePartyId, UUID annuitant) {
        if (form.joint() && jointLifePartyId == null) {
            throw new VestingRefusedException("Form " + form.formCode() + " is joint-life: name the joint life");
        }
        if (!form.joint() && jointLifePartyId != null) {
            throw new VestingRefusedException("Form " + form.formCode() + " is single-life: it takes no joint life");
        }
        if (jointLifePartyId == null) {
            return;
        }
        if (jointLifePartyId.equals(annuitant)) {
            throw new VestingRefusedException("The joint life must be someone other than the annuitant");
        }
        PartyDetailView joint = partyApi.getPartyDetail(jointLifePartyId);
        if (joint.partyType() != tz.co.nlolo.lifeplatform.party.api.PartyType.INDIVIDUAL) {
            throw new VestingRefusedException("The joint life must be a person");
        }
        if (joint.dateOfBirth() == null) {
            throw new VestingRefusedException("The joint life's date of birth is not recorded, so there is no age to price");
        }
    }

    @Override
    @Transactional
    public VestingView reconfirmAge(String policyNumber, String confirmedBy) {
        AnnuityContract c = savingContract(policyNumber);
        Vesting v = vestings.findById(policyNumber).orElseThrow();
        if (!AnnuityVesting.AGE_CHANGED.equals(v.getHoldReason())) {
            throw new VestingRefusedException("Policy " + policyNumber + " is not held for age re-confirmation");
        }
        PartyDetailView annuitant = partyApi.getPartyDetail(c.getAnnuitantPartyId());
        if (annuitant.dateOfBirth() == null) {
            throw new VestingRefusedException("The annuitant's date of birth is not recorded, so there is no age to confirm");
        }
        v.reconfirm(annuitant.dateOfBirth(), annuitant.sex() != null ? annuitant.sex().name() : null, confirmedBy);
        v.rearmReminders();
        vestings.save(v);
        return viewOf(c, v);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<VestingView> listHeldVestings() {
        return vestings.findByTenantIdAndHoldReasonIsNotNullOrderByHeldAtAsc(TenantContext.get()).stream()
            .flatMap(v -> contracts.findById(v.getPolicyNumber()).stream().map(c -> viewOf(c, v)))
            .toList();
    }

    /** A deferred annuity still saving -- the only kind an instruction or re-confirmation applies to. */
    private AnnuityContract savingContract(String policyNumber) {
        AnnuityContract c = versionIfAnnuity(policyNumber).flatMap(v -> contracts.findById(policyNumber))
            .filter(AnnuityContract::isDeferred)
            .orElseThrow(() -> new VestingRefusedException("Policy " + policyNumber + " is not a deferred annuity"));
        if (c.status() != ContractStatus.ACCUMULATING) {
            throw new VestingRefusedException(c.getVestedOn() != null
                ? "Policy " + policyNumber + " has already vested"
                : "Policy " + policyNumber + " is " + c.status() + "; it will not vest");
        }
        return c;
    }

    /**
     * {@code claims.ClaimRegistered}: a DEATH claim on a pension still saving holds its vesting until
     * the claim is decided (plan R6) -- a sweep must not buy an annuity for someone reported dead.
     */
    @Transactional
    public void onDeathReported(String policyNumber, UUID claimId) {
        contracts.findById(policyNumber).filter(c -> c.status() == ContractStatus.ACCUMULATING)
            .flatMap(c -> vestings.findById(policyNumber)).ifPresent(v -> {
                v.deathReported(claimId);
                vestings.save(v);
            });
    }

    /** {@code claims.ClaimRejected}: the reported death was not accepted; the vesting may proceed. */
    @Transactional
    public void onDeathRejected(String policyNumber, UUID claimId) {
        vestings.findById(policyNumber).ifPresent(v -> {
            v.deathCleared(claimId);
            if (AnnuityVesting.DEATH_REPORTED.equals(v.getHoldReason())) {
                v.clearHold();
            }
            vestings.save(v);
        });
    }

    /**
     * {@code policy.PolicySurrendered}: a deferred annuity surrendered before vesting, on an unlocked
     * version, never buys its annuity. Every other contract is left as it is.
     */
    @Transactional
    public void onSurrendered(String policyNumber) {
        contracts.findById(policyNumber).filter(c -> c.status() == ContractStatus.ACCUMULATING).ifPresent(c -> {
            c.cancelledBeforeVesting("Surrendered before vesting");
            contracts.save(c);
        });
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

    /**
     * The offer expired unpaid: no premium, so nothing was ever locked and nothing is owed. Without
     * this the contract read "locked when the single premium is collected" on a policy that never can be.
     * Only a contract still awaiting payment -- a paid one cannot be not-taken-up.
     */
    @Transactional
    public void onNotTakenUp(String policyNumber) {
        contracts.findById(policyNumber).ifPresent(c -> {
            if (c.status() == ContractStatus.AWAITING_PAYMENT) {
                c.cancel();
                contracts.save(c);
            } else if (c.status() == ContractStatus.ACCUMULATING) {
                // A pension whose first contribution never came (D2): it never saved, so it never vests.
                c.cancelledBeforeVesting("Not taken up: the first contribution was never paid");
                contracts.save(c);
            }
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
        // Died before vesting (D2 spec Q6): the account pays its balance through the account death
        // closing, as on any account policy. Nothing here moves money; the annuity is never bought.
        if (c.status() == ContractStatus.ACCUMULATING) {
            c.endedBeforeVesting("Died before vesting");
            contracts.save(c);
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
