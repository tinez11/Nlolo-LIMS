package tz.co.nlolo.lifeplatform.annuity.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.accumulation.api.AccumulationApi;
import tz.co.nlolo.lifeplatform.annuity.api.ContractStatus;
import tz.co.nlolo.lifeplatform.annuity.domain.AnnuityContract;
import tz.co.nlolo.lifeplatform.annuity.domain.CommutationSplit;
import tz.co.nlolo.lifeplatform.annuity.domain.Vesting;
import tz.co.nlolo.lifeplatform.annuity.domain.VestingInstruction;
import tz.co.nlolo.lifeplatform.annuity.domain.VestingRules;
import tz.co.nlolo.lifeplatform.annuity.infrastructure.AnnuityContractRepository;
import tz.co.nlolo.lifeplatform.annuity.infrastructure.VestingInstructionRepository;
import tz.co.nlolo.lifeplatform.annuity.infrastructure.VestingRepository;
import tz.co.nlolo.lifeplatform.benefitpayout.api.BenefitPayoutApi;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyDetailView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import tz.co.nlolo.lifeplatform.product.api.AnnuityForm;
import tz.co.nlolo.lifeplatform.product.api.AnnuityFrequencies;
import tz.co.nlolo.lifeplatform.product.api.AnnuityPlan;
import tz.co.nlolo.lifeplatform.product.api.AnnuityPrice;
import tz.co.nlolo.lifeplatform.product.api.AnnuityPricingInput;
import tz.co.nlolo.lifeplatform.product.api.AnnuityPricingRefusedException;
import tz.co.nlolo.lifeplatform.product.api.AnnuityTiming;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * The vesting of one pension (product step 5 D2). Everything that can refuse is asked FIRST and
 * touches nothing -- a reported death, a changed date of birth or sex, a date outside the window, a
 * purchase the current version cannot price -- and becomes a hold with its reason. Only then does it
 * close the account, schedule the lump sum and lock the annuity, in one transaction: a failure there
 * rolls all of it back, and the sweep records the failure as a hold in a transaction of its own.
 */
@Service
public class AnnuityVesting {

    private static final Logger log = LoggerFactory.getLogger(AnnuityVesting.class);

    static final String DEATH_REPORTED = "A death has been reported on this policy; it vests only if that claim is rejected";
    static final String AGE_CHANGED =
        "Age re-confirmation needed: the date of birth or sex on record has changed since it was confirmed";

    private final AnnuityContractRepository contracts;
    private final VestingRepository vestings;
    private final VestingInstructionRepository instructions;
    private final ProductApi productApi;
    private final PolicyApi policyApi;
    private final PartyApi partyApi;
    private final AccumulationApi accumulationApi;
    private final BenefitPayoutApi payouts;

    public AnnuityVesting(AnnuityContractRepository contracts, VestingRepository vestings,
                          VestingInstructionRepository instructions, ProductApi productApi, PolicyApi policyApi,
                          PartyApi partyApi, AccumulationApi accumulationApi, BenefitPayoutApi payouts) {
        this.contracts = contracts;
        this.vestings = vestings;
        this.instructions = instructions;
        this.productApi = productApi;
        this.policyApi = policyApi;
        this.partyApi = partyApi;
        this.accumulationApi = accumulationApi;
        this.payouts = payouts;
    }

    /** What the vesting will buy: the current instruction's choice, else the sold version's defaults and no lump sum. */
    private record Choice(LocalDate vestingDate, String formCode, String frequency, UUID jointLifePartyId,
                          BigDecimal lumpSumPercent) {}

    /**
     * Vest one policy, or hold it with a reason. A hold returns normally, in this transaction; a
     * failure after the account has been touched throws, and this transaction rolls back whole.
     * Returns the outcome, for the sweep's log.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String vest(String policyNumber, LocalDate today) {
        AnnuityContract contract = contracts.findById(policyNumber).orElse(null);
        Vesting vesting = vestings.findById(policyNumber).orElse(null);
        if (contract == null || vesting == null || contract.status() != ContractStatus.ACCUMULATING) {
            return "not saving";
        }
        Choice due = choiceOf(vesting);
        if (due.vestingDate().isAfter(today)) {
            return "not due until " + due.vestingDate();
        }
        // A pension held past its date -- or whose target a re-confirmed date of birth moved behind
        // today -- vests the day the sweep can vest it: the account closes, and the annuity is priced,
        // on that day, never on a date already gone.
        Choice choice = due.vestingDate().isBefore(today)
            ? new Choice(today, due.formCode(), due.frequency(), due.jointLifePartyId(), due.lumpSumPercent())
            : due;

        // 1-3: everything that can refuse, touching nothing.
        if (vesting.getDeathReportedClaimId() != null) {
            return held(vesting, DEATH_REPORTED);
        }
        PartyDetailView annuitant = partyApi.getPartyDetail(contract.getAnnuitantPartyId());
        if (!vesting.ageStillConfirmed(annuitant.dateOfBirth(), annuitant.sex() != null ? annuitant.sex().name() : null)) {
            return held(vesting, AGE_CHANGED);
        }
        try {
            VestingRules.checkWindow(choice.vestingDate(), vesting.getEarliestVestingDate(), vesting.getLatestVestingDate());
        } catch (IllegalArgumentException e) {
            return held(vesting, e.getMessage());
        }
        PolicyView policy = policyApi.getPolicy(policyNumber);
        // Spec Q7: the annuity terms are the product's CURRENT version's on the vesting date.
        UUID currentVersion = productApi.getActiveSnapshot(policy.productId(), choice.vestingDate()).productVersionId();
        BigDecimal balance = accumulationApi.findAccount(policyNumber).orElseThrow().balance();
        BigDecimal provisionalPrice = CommutationSplit.of(balance, choice.lumpSumPercent()).purchasePrice();
        try {
            productApi.priceAnnuity(currentVersion, input(choice, contract, provisionalPrice));
        } catch (AnnuityPricingRefusedException e) {
            return held(vesting, e.getMessage());
        }

        // 4-6: the account closes, the lump sum is scheduled, the annuity locks -- or none of it.
        BigDecimal vestedBalance = accumulationApi.closeForVesting(policyNumber, choice.vestingDate());
        CommutationSplit.Split split = CommutationSplit.of(vestedBalance, choice.lumpSumPercent());
        if (split.lumpSum().signum() > 0) {
            payouts.scheduleCommutation(policyNumber, choice.vestingDate(), split.lumpSum(), contract.getCurrency());
        }
        AnnuityPlan plan = productApi.resolveAnnuityPlan(currentVersion);
        AnnuityForm form = plan.form(choice.formCode()).orElseThrow();
        AnnuityPrice price = productApi.priceAnnuity(currentVersion, input(choice, contract, split.purchasePrice()));
        LocalDate firstDue = plan.timing() == AnnuityTiming.ADVANCE
            ? choice.vestingDate() : choice.vestingDate().plusMonths(AnnuityFrequencies.monthsPer(choice.frequency()));
        LocalDate guaranteeEnd = form.guaranteeYears() > 0 ? firstDue.plusYears(form.guaranteeYears()) : null;
        contract.vest(form, plan.timing().name(), plan.proofOfLifeIntervalMonths(), choice.frequency(),
            choice.jointLifePartyId(), split.purchasePrice(), price, choice.vestingDate(), firstDue, guaranteeEnd, currentVersion);
        contracts.save(contract);
        payouts.openAnnuityStream(policyNumber, firstDue, choice.frequency(), price.instalment(), contract.getCurrency(),
            form.escalationPercent(), plan.proofOfLifeIntervalMonths());
        policyApi.recordVesting(policyNumber, choice.vestingDate());
        vesting.vested(vestedBalance, split.lumpSum());
        vestings.save(vesting);
        log.info("Pension {} vested on {}: balance {}, lump sum {}, annuity instalment {}", policyNumber,
            choice.vestingDate(), vestedBalance, split.lumpSum(), price.instalment());
        return "vested";
    }

    /** A hold written after {@link #vest} rolled back -- its own transaction, so it survives that rollback. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void hold(String policyNumber, String reason) {
        vestings.findById(policyNumber).ifPresent(v -> held(v, reason));
    }

    private String held(Vesting vesting, String reason) {
        vesting.hold(reason);
        vestings.save(vesting);
        return "held: " + reason;
    }

    private Choice choiceOf(Vesting v) {
        Optional<VestingInstruction> i = instructions.findByPolicyNumberAndCurrentTrue(v.getPolicyNumber());
        return i.map(x -> new Choice(x.getVestingDate(), x.getFormCode(), x.getFrequency(), x.getJointLifePartyId(),
                x.getLumpSumPercent()))
            .orElseGet(() -> new Choice(v.getTargetDate(), v.getDefaultFormCode(), v.getDefaultFrequency(), null,
                BigDecimal.ZERO));
    }

    /** Priced on the vesting date, on the lives' RECORDED dates of birth -- which the age check just matched. */
    private AnnuityPricingInput input(Choice choice, AnnuityContract contract, BigDecimal price) {
        PartyDetailView a = partyApi.getPartyDetail(contract.getAnnuitantPartyId());
        PartyDetailView j = choice.jointLifePartyId() != null ? partyApi.getPartyDetail(choice.jointLifePartyId()) : null;
        return new AnnuityPricingInput(choice.formCode(), choice.frequency(), price, a.dateOfBirth(),
            a.sex() != null ? a.sex().name() : null, j != null ? j.dateOfBirth() : null,
            j != null && j.sex() != null ? j.sex().name() : null, choice.vestingDate());
    }
}
