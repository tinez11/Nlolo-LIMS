package tz.co.nlolo.lifeplatform.bonus.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** With-profits bonuses (product step 4): declarations, and what bonuses are worth at an exit. */
public interface BonusApi {

    /** In-process form; over HTTP the keyed form below is what runs. */
    BonusDeclarationView proposeDeclaration(UUID productId, LocalDate valuationDate, BigDecimal reversionaryRatePercent,
                                            BigDecimal terminalRatePercent, String proposedBy);

    /**
     * Once per Idempotency-Key: a retry after a timeout answers with the proposal the first request
     * made, and proposes nothing more. A key reused for a different request is refused.
     */
    BonusDeclarationView proposeDeclaration(UUID productId, LocalDate valuationDate, BigDecimal reversionaryRatePercent,
                                            BigDecimal terminalRatePercent, String proposedBy, String idempotencyKey);

    BonusDeclarationView approveDeclaration(UUID declarationId, String approvedBy);

    BonusDeclarationView withdrawDeclaration(UUID declarationId, String withdrawnBy);

    List<BonusDeclarationView> listDeclarations(UUID productId);

    /**
     * Whether this policy is on a with-profits version -- the cheap gate every caller asks first.
     * Answered from policy and product alone, so asking reads no bonus table.
     */
    boolean isParticipating(String policyNumber);

    /** Attached as at the date, plus interim (if eligible on it), plus terminal. Reads only. */
    BonusValuation valueAt(String policyNumber, LocalDate date);

    /** valueAt, recorded once for this exit. A second call for the same exit returns the record. */
    BonusValuation settle(String policyNumber, ExitType type, String exitRef, LocalDate exitDate);

    /** The policy's whole bonus history; empty when it is not with-profits -- an answer, not an error. */
    java.util.Optional<PolicyBonusView> policyBonuses(String policyNumber);
}
