package tz.co.nlolo.lifeplatform.claims;

import tz.co.nlolo.lifeplatform.claims.api.ClaimStatus;
import tz.co.nlolo.lifeplatform.claims.api.ClaimType;
import tz.co.nlolo.lifeplatform.claims.api.ClaimValidationException;
import tz.co.nlolo.lifeplatform.claims.api.CriticalIllnessClaimDetails;
import tz.co.nlolo.lifeplatform.claims.api.DeathClaimDetails;
import tz.co.nlolo.lifeplatform.claims.api.DisabilityClaimDetails;
import tz.co.nlolo.lifeplatform.claims.api.InvalidClaimStateException;
import tz.co.nlolo.lifeplatform.claims.api.MaturityClaimDetails;
import tz.co.nlolo.lifeplatform.claims.domain.Claim;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Task 3: a plain unit test over {@link Claim} -- no Spring, no container, no database. The
 * state machine's correctness must be provable purely from the entity's transition methods, per
 * this plan's explicit requirement that this be fast and infrastructure-free.
 *
 * <p>Covers every legal transition per claim type (Step 4 of the task brief), plus a
 * representative sample of illegal ones: the full DEATH/DISABILITY/CRITICAL_ILLNESS happy path,
 * the MATURITY auto-approval shortcut and its exclusivity to MATURITY, markSettlementFailed's
 * reason-preserving revert to APPROVED, both reopen paths followed by REOPENED ->
 * UNDER_ASSESSMENT, idempotency of every terminal-transition method on repeat, and the
 * non-positive approved amount rejection.
 */
class ClaimStateMachineTest {

    private static final BigDecimal AMOUNT = new BigDecimal("1000000.00");
    private static final String CURRENCY = "TZS";

    private Claim newClaim(ClaimType type) {
        UUID tenantId = UUID.randomUUID();
        UUID claimantPartyId = UUID.randomUUID();
        return switch (type) {
            case DEATH -> new Claim(tenantId, "POL-0001", claimantPartyId, ClaimType.DEATH,
                LocalDate.of(2026, 1, 1),
                new DeathClaimDetails("Cardiac arrest", "Dar es Salaam", LocalDate.of(2026, 1, 1), "Dr. Juma"),
                "test-staff", null);
            case DISABILITY -> new Claim(tenantId, "POL-0002", claimantPartyId, ClaimType.DISABILITY,
                LocalDate.of(2026, 1, 1),
                new DisabilityClaimDetails("Loss of limb", LocalDate.of(2026, 1, 1), true, new BigDecimal("80")),
                "test-staff", null);
            case CRITICAL_ILLNESS -> new Claim(tenantId, "POL-0003", claimantPartyId, ClaimType.CRITICAL_ILLNESS,
                LocalDate.of(2026, 1, 1),
                new CriticalIllnessClaimDetails("Stage 3 carcinoma", LocalDate.of(2026, 1, 1), "C50"),
                "test-staff", null);
            case MATURITY -> new Claim(tenantId, "POL-0004", claimantPartyId, ClaimType.MATURITY,
                LocalDate.of(2026, 1, 1),
                new MaturityClaimDetails(LocalDate.of(2026, 1, 1)),
                "test-staff", null);
        };
    }

    // ---- Constructor invariant ----------------------------------------------------------

    @Test
    void constructorRejectsDetailsWhoseClaimTypeDoesNotMatch() {
        UUID tenantId = UUID.randomUUID();
        assertThrows(ClaimValidationException.class, () -> new Claim(tenantId, "POL-9999", UUID.randomUUID(),
            ClaimType.DEATH, LocalDate.now(),
            new MaturityClaimDetails(LocalDate.now()), "test-staff", null));
    }

    // ---- Happy paths per claim type ------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = ClaimType.class, names = {"DEATH", "DISABILITY", "CRITICAL_ILLNESS"})
    void happyPathRunsRegisteredThroughSettledViaAssessment(ClaimType type) {
        Claim claim = newClaim(type);
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.REGISTERED);

        claim.beginAssessment();
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.UNDER_ASSESSMENT);

        claim.approve(AMOUNT, CURRENCY);
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.APPROVED);
        assertThat(claim.getApprovedAmount()).isEqualTo(AMOUNT);
        assertThat(claim.getApprovedCurrency()).isEqualTo(CURRENCY);

        claim.markSettlementRequested("idem-key-1");
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.SETTLEMENT_REQUESTED);
        assertThat(claim.getSettlementIdempotencyKey()).isEqualTo("idem-key-1");

        claim.markSettled();
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.SETTLED);
    }

    @Test
    void maturityAutoApprovesDirectlyFromRegisteredWithNoAssessment() {
        Claim claim = newClaim(ClaimType.MATURITY);
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.REGISTERED);

        claim.approve(AMOUNT, CURRENCY);

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.APPROVED);
        assertThat(claim.getApprovedAmount()).isEqualTo(AMOUNT);
    }

    @ParameterizedTest
    @EnumSource(value = ClaimType.class, names = {"DEATH", "DISABILITY", "CRITICAL_ILLNESS"})
    void nonMaturityCannotApproveDirectlyFromRegistered(ClaimType type) {
        Claim claim = newClaim(type);
        assertThrows(InvalidClaimStateException.class, () -> claim.approve(AMOUNT, CURRENCY));
    }

    // ---- Illegal transitions --------------------------------------------------------------

    @Test
    void beginAssessmentRejectsAnApprovedClaim() {
        Claim claim = newClaim(ClaimType.DEATH);
        claim.beginAssessment();
        claim.approve(AMOUNT, CURRENCY);
        assertThrows(InvalidClaimStateException.class, claim::beginAssessment);
    }

    @Test
    void rejectRequiresUnderAssessment() {
        Claim claim = newClaim(ClaimType.DEATH);
        assertThrows(InvalidClaimStateException.class, claim::reject);
    }

    @Test
    void markSettlementRequestedRequiresApproved() {
        Claim claim = newClaim(ClaimType.DEATH);
        claim.beginAssessment();
        assertThrows(InvalidClaimStateException.class, () -> claim.markSettlementRequested("idem-key"));
    }

    @Test
    void markSettledRequiresSettlementRequested() {
        Claim claim = newClaim(ClaimType.DEATH);
        claim.beginAssessment();
        claim.approve(AMOUNT, CURRENCY);
        assertThrows(InvalidClaimStateException.class, claim::markSettled);
    }

    @Test
    void reopenRequiresRejectedOrSettled() {
        Claim claim = newClaim(ClaimType.DEATH);
        assertThrows(InvalidClaimStateException.class, claim::reopen);
    }

    // ---- reject path ------------------------------------------------------------------------

    @Test
    void rejectMovesUnderAssessmentToRejected() {
        Claim claim = newClaim(ClaimType.DISABILITY);
        claim.beginAssessment();
        claim.reject();
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.REJECTED);
    }

    // ---- markSettlementFailed ---------------------------------------------------------------

    @Test
    void markSettlementFailedReturnsToApprovedAndPreservesReason() {
        Claim claim = newClaim(ClaimType.DEATH);
        claim.beginAssessment();
        claim.approve(AMOUNT, CURRENCY);
        claim.markSettlementRequested("idem-key-2");

        claim.markSettlementFailed("gateway timeout");

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.APPROVED);
        assertThat(claim.getSettlementFailureReason()).isEqualTo("gateway timeout");
        // the prior decision still stands
        assertThat(claim.getApprovedAmount()).isEqualTo(AMOUNT);
    }

    @Test
    void markSettlementFailedIsANoOpOutsideSettlementRequested() {
        Claim claim = newClaim(ClaimType.DEATH);
        claim.beginAssessment();
        claim.approve(AMOUNT, CURRENCY);

        claim.markSettlementFailed("should be ignored");

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.APPROVED);
        assertThat(claim.getSettlementFailureReason()).isNull();
    }

    @Test
    void freshSettlementRequestClearsThePreviousFailureReason() {
        Claim claim = newClaim(ClaimType.DEATH);
        claim.beginAssessment();
        claim.approve(AMOUNT, CURRENCY);
        claim.markSettlementRequested("idem-key-3");
        claim.markSettlementFailed("first failure");
        assertThat(claim.getSettlementFailureReason()).isEqualTo("first failure");

        claim.markSettlementRequested("idem-key-4");

        assertThat(claim.getSettlementFailureReason()).isNull();
        assertThat(claim.getSettlementIdempotencyKey()).isEqualTo("idem-key-4");
    }

    // ---- reopen paths -------------------------------------------------------------------

    @Test
    void reopenFromRejectedThenBeginAssessmentAgain() {
        Claim claim = newClaim(ClaimType.DISABILITY);
        claim.beginAssessment();
        claim.reject();

        claim.reopen();
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.REOPENED);

        claim.beginAssessment();
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.UNDER_ASSESSMENT);
    }

    @Test
    void reopenFromSettledThenBeginAssessmentAgainPreservesPriorApprovedAmount() {
        Claim claim = newClaim(ClaimType.CRITICAL_ILLNESS);
        claim.beginAssessment();
        claim.approve(AMOUNT, CURRENCY);
        claim.markSettlementRequested("idem-key-5");
        claim.markSettled();

        claim.reopen();
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.REOPENED);
        // reopen deliberately does NOT clear the prior decision
        assertThat(claim.getApprovedAmount()).isEqualTo(AMOUNT);

        claim.beginAssessment();
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.UNDER_ASSESSMENT);
    }

    // ---- Idempotency: each terminal-transition method is a silent no-op on repeat -------

    @Test
    void beginAssessmentIsIdempotent() {
        Claim claim = newClaim(ClaimType.DEATH);
        claim.beginAssessment();
        claim.beginAssessment();
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.UNDER_ASSESSMENT);
    }

    @Test
    void approveIsIdempotent() {
        Claim claim = newClaim(ClaimType.DEATH);
        claim.beginAssessment();
        claim.approve(AMOUNT, CURRENCY);
        // second call, even with a different amount, is a silent no-op -- it must not overwrite
        claim.approve(new BigDecimal("1"), "USD");
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.APPROVED);
        assertThat(claim.getApprovedAmount()).isEqualTo(AMOUNT);
        assertThat(claim.getApprovedCurrency()).isEqualTo(CURRENCY);
    }

    @Test
    void rejectIsIdempotent() {
        Claim claim = newClaim(ClaimType.DEATH);
        claim.beginAssessment();
        claim.reject();
        claim.reject();
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.REJECTED);
    }

    @Test
    void markSettlementRequestedIsIdempotent() {
        Claim claim = newClaim(ClaimType.DEATH);
        claim.beginAssessment();
        claim.approve(AMOUNT, CURRENCY);
        claim.markSettlementRequested("idem-key-6");
        // second call, even with a different key, is a silent no-op
        claim.markSettlementRequested("idem-key-7");
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.SETTLEMENT_REQUESTED);
        assertThat(claim.getSettlementIdempotencyKey()).isEqualTo("idem-key-6");
    }

    @Test
    void markSettledIsIdempotent() {
        Claim claim = newClaim(ClaimType.DEATH);
        claim.beginAssessment();
        claim.approve(AMOUNT, CURRENCY);
        claim.markSettlementRequested("idem-key-8");
        claim.markSettled();
        claim.markSettled();
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.SETTLED);
    }

    @Test
    void reopenIsIdempotent() {
        Claim claim = newClaim(ClaimType.DEATH);
        claim.beginAssessment();
        claim.reject();
        claim.reopen();
        claim.reopen();
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.REOPENED);
    }

    // ---- Validation ------------------------------------------------------------------------

    @Test
    void approveRejectsZeroAmount() {
        Claim claim = newClaim(ClaimType.DEATH);
        claim.beginAssessment();
        assertThrows(ClaimValidationException.class, () -> claim.approve(BigDecimal.ZERO, CURRENCY));
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.UNDER_ASSESSMENT);
    }

    @Test
    void approveRejectsNegativeAmount() {
        Claim claim = newClaim(ClaimType.DEATH);
        claim.beginAssessment();
        assertThrows(ClaimValidationException.class, () -> claim.approve(new BigDecimal("-1"), CURRENCY));
    }

    @Test
    void approveRejectsNullAmount() {
        Claim claim = newClaim(ClaimType.DEATH);
        claim.beginAssessment();
        assertThrows(ClaimValidationException.class, () -> claim.approve(null, CURRENCY));
    }

    @Test
    void markSettlementRequestedRejectsBlankIdempotencyKey() {
        Claim claim = newClaim(ClaimType.DEATH);
        claim.beginAssessment();
        claim.approve(AMOUNT, CURRENCY);
        assertThrows(ClaimValidationException.class, () -> claim.markSettlementRequested("   "));
        assertThrows(ClaimValidationException.class, () -> claim.markSettlementRequested(null));
    }
}
