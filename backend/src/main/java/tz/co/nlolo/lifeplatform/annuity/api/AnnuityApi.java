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
}
