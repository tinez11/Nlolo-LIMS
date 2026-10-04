package tz.co.nlolo.lifeplatform.annuity.api;

import tz.co.nlolo.lifeplatform.product.api.AnnuityPrice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Immediate annuities (product step 5). */
public interface AnnuityApi {

    /** Whether this policy is an annuity: product first, then the contract -- cheap for every other policy. */
    boolean isAnnuity(String policyNumber);

    Optional<AnnuityContractView> contract(String policyNumber);

    /**
     * Price a purchase without storing anything: the case form's live quote. Lives are party ids, so
     * the dates of birth and sexes are the recorded ones.
     *
     * @throws tz.co.nlolo.lifeplatform.product.api.AnnuityPricingRefusedException in words a person can act on
     */
    AnnuityPrice quote(UUID productVersionId, String formCode, String frequency, BigDecimal purchasePrice,
                       UUID annuitantPartyId, UUID jointLifePartyId);

    /**
     * What a death claim may pay (Task 6): the capital refund on the last death of a capital-protected
     * annuity, net of any overpayment; zero otherwise.
     *
     * @param deceasedPartyId the life that died; null means the annuitant
     */
    DeathValue deathValue(String policyNumber, UUID deceasedPartyId, LocalDate dateOfDeath);

    /**
     * Whether a death on this policy is the annuity's to value and settle (D2): false while a deferred
     * annuity is still saving, so claims values the account as on any account policy.
     */
    boolean decidesDeath(String policyNumber);

    /** A deferred annuity's vesting; empty for every other policy. */
    Optional<VestingView> vesting(String policyNumber);

    /**
     * Record when and into what a pension vests (D2): early, on its target, or deferred inside the
     * sold version's window; the form and frequency from the product's CURRENT version; a lump sum up
     * to the cap. A deferral with contributions continuing extends the premium-paying term. Replaces any
     * earlier instruction and clears a hold. Refusals are {@link VestingRefusedException}.
     */
    VestingView recordVestingInstruction(String policyNumber, VestingInstructionInput input, String recordedBy);

    /**
     * Re-confirm proof of age after the party record changed (spec Q8): the date of birth and sex now on
     * record become the confirmed pair, and the target and window move with the date of birth. Only
     * while the age hold stands.
     */
    VestingView reconfirmAge(String policyNumber, String confirmedBy);

    /** Every pension held from vesting, with its reason, oldest first. */
    java.util.List<VestingView> listHeldVestings();
}
