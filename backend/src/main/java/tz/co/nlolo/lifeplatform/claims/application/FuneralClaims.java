package tz.co.nlolo.lifeplatform.claims.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.claims.api.ClaimDeclineReason;
import tz.co.nlolo.lifeplatform.claims.api.ClaimStatus;
import tz.co.nlolo.lifeplatform.claims.api.ClaimType;
import tz.co.nlolo.lifeplatform.claims.api.ClaimValidationException;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.claims.api.InvalidClaimStateException;
import tz.co.nlolo.lifeplatform.claims.domain.Claim;
import tz.co.nlolo.lifeplatform.claims.domain.ExclusionPeriods;
import tz.co.nlolo.lifeplatform.claims.domain.ExclusionWindows;
import tz.co.nlolo.lifeplatform.claims.domain.FuneralClaim;
import tz.co.nlolo.lifeplatform.claims.infrastructure.ClaimRepository;
import tz.co.nlolo.lifeplatform.claims.infrastructure.FuneralClaimRepository;
import tz.co.nlolo.lifeplatform.policy.api.ExclusionPeriodsView;
import tz.co.nlolo.lifeplatform.policy.api.FuneralClaimFacts;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;

import java.util.Optional;
import java.util.UUID;

/**
 * A claim on a funeral plan (family funeral cover): the life it names, who may file it (R9), one death claim
 * per covered life, and the waiting period that refuses approval (R7). Its own component so ClaimsApiImpl
 * does not grow again; claims.funeral_claim (V10) is touched only once the policy says FUNERAL, so no class
 * that never sells a funeral plan needs that migration.
 */
@Component
class FuneralClaims {

    private final FuneralClaimRepository funeralClaims;
    private final ClaimRepository claims;
    private final PolicyApi policyApi;

    FuneralClaims(FuneralClaimRepository funeralClaims, ClaimRepository claims, PolicyApi policyApi) {
        this.funeralClaims = funeralClaims;
        this.claims = claims;
        this.policyApi = policyApi;
    }

    static boolean isFuneral(PolicyView policy) {
        return "FUNERAL".equals(policy.productCategory());
    }

    /**
     * R9: a dependant's death is filed by the main member, or by a party beneficiary of the policy, as the
     * version says. The main member's own death is filed as any individual policy's is.
     */
    void checkClaimant(ClaimsApi.RegisterClaimRequest request) {
        FuneralClaimFacts facts = policyApi.funeralClaimFacts(request.policyNumber(), request.coveredLifeId()).orElseThrow();
        if (facts.mainMember()) {
            return;
        }
        if (facts.groupScheme()) {
            checkSchemeClaimant(request, facts);
            return;
        }
        boolean allowed = "MAIN_MEMBER".equals(facts.dependantClaimPayee())
            ? facts.policyholderPartyId().equals(request.claimantPartyId())
            : facts.beneficiaryPartyIds().contains(request.claimantPartyId());
        if (!allowed) {
            throw new ClaimValidationException("MAIN_MEMBER".equals(facts.dependantClaimPayee())
                ? "A dependant's death on " + request.policyNumber() + " is claimed by the main member ("
                    + facts.policyholderPartyId() + "); this claim names " + request.claimantPartyId()
                : "A dependant's death on " + request.policyNumber() + " is claimed by a beneficiary the main member"
                    + " nominated; " + request.claimantPartyId() + " is not one");
        }
    }

    /**
     * A dependant's death on a group funeral scheme (2026-10-07). The association is the policyholder, so the payee
     * is the family's: the main member -- registered as a party at claim, as any name-only life is -- or, when the
     * version pays the beneficiary, the beneficiary the main member named, registered at claim and checked against
     * that name by the assessor.
     */
    private static void checkSchemeClaimant(ClaimsApi.RegisterClaimRequest request, FuneralClaimFacts facts) {
        if (!"MAIN_MEMBER".equals(facts.dependantClaimPayee())) {
            return;
        }
        if (facts.mainMemberPartyId() == null) {
            throw new ClaimValidationException("A dependant's death on " + request.policyNumber() + " is paid to the main"
                + " member, " + facts.mainMemberName() + ", who is not yet a registered party: register them from their"
                + " identity document (promote the covered life) and file the claim in their name");
        }
        if (!facts.mainMemberPartyId().equals(request.claimantPartyId())) {
            throw new ClaimValidationException("A dependant's death on " + request.policyNumber() + " is claimed by the"
                + " main member, " + facts.mainMemberName() + " (" + facts.mainMemberPartyId() + "); this claim names "
                + request.claimantPartyId());
        }
    }

    /**
     * One death claim per COVERED LIFE: siblings on one policy are different lives, so the policy-wide rule
     * individual business uses would refuse the second child's claim. A rejected claim does not count.
     *
     * @param self the claim being decided, excluded; null at registration (422), otherwise 409
     */
    void refuseASecondDeathClaim(UUID tenantId, String policyNumber, UUID coveredLifeId, UUID self) {
        for (FuneralClaim other : funeralClaims.findByTenantIdAndCoveredLifeId(tenantId, coveredLifeId)) {
            if (other.getClaimId().equals(self)) {
                continue;
            }
            Optional<Claim> claim = claims.findByClaimIdAndTenantId(other.getClaimId(), tenantId);
            if (claim.isPresent() && claim.get().getClaimType() == ClaimType.DEATH
                    && claim.get().getStatus() != ClaimStatus.REJECTED && claim.get().getPolicyNumber().equals(policyNumber)) {
                String message = "A death claim already exists for this life (covered life " + coveredLifeId + " of "
                    + policyNumber + "): claim " + other.getClaimId() + ", " + claim.get().getStatus()
                    + ". A life is paid for once -- reject one of them to proceed with the other.";
                if (self == null) {
                    throw new ClaimValidationException(message);
                }
                throw new InvalidClaimStateException(message);
            }
        }
    }

    void record(UUID tenantId, Claim claim, UUID coveredLifeId, boolean accidental, String recordedBy) {
        funeralClaims.save(new FuneralClaim(tenantId, claim.getClaimId(), coveredLifeId, accidental, recordedBy));
    }

    Optional<FuneralClaim> of(UUID claimId) {
        return funeralClaims.findById(claimId);
    }

    FuneralClaim recordAccidental(UUID claimId, boolean accidental, String recordedBy) {
        FuneralClaim funeral = funeralClaims.findById(claimId).orElseThrow(() -> new ClaimValidationException(
            "Claim " + claimId + " is not on a funeral plan, so whether its death was accidental is not recorded"));
        funeral.recordAccidental(accidental, recordedBy);
        return funeralClaims.save(funeral);
    }

    /** The windows for this claim's life: its own cover start, the exclusions, and the waiting period. */
    ExclusionPeriodsView periods(Claim claim, FuneralClaim funeral) {
        return policyApi.exclusionPeriodsFor(claim.getPolicyNumber(), null, funeral.getCoveredLifeId());
    }

    static ExclusionPeriods windows(ExclusionPeriodsView periods) {
        return new ExclusionPeriods(periods.suicideMonths(), periods.preExistingMonths(), periods.waitingMonths(),
            periods.accidentWaivesWaiting());
    }

    /**
     * R7: a natural death inside the life's waiting period cannot be APPROVED -- unlike the exclusions, the
     * waiting period is not an assessor's finding but the product's rule that cover has not started for it.
     */
    void refuseApprovalInsideTheWaitingPeriod(Claim claim, FuneralClaim funeral) {
        ExclusionPeriodsView periods = periods(claim, funeral);
        if (ExclusionWindows.openAt(periods.coverStart(), claim.getDateOfEvent(), windows(periods), funeral.isAccidental())
                .contains(ClaimDeclineReason.WITHIN_WAITING_PERIOD)) {
            throw new InvalidClaimStateException("Claim " + claim.getClaimId() + " cannot be approved: the death on "
                + claim.getDateOfEvent() + " was inside the waiting period, which runs to "
                + periods.coverStart().plusMonths(periods.waitingMonths()) + ". Decline it with WITHIN_WAITING_PERIOD,"
                + " or record the death as accidental if it was and the product waives accidents.");
        }
    }
}
