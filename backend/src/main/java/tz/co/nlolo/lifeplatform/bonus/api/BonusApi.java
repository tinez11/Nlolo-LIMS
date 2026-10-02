package tz.co.nlolo.lifeplatform.bonus.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** With-profits bonuses (product step 4). Tasks 5-7 add attaching, exits and the policy reads. */
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
}
