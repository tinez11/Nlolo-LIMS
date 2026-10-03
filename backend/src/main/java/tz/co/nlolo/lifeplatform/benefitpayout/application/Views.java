package tz.co.nlolo.lifeplatform.benefitpayout.application;

import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutInstalmentView;
import tz.co.nlolo.lifeplatform.benefitpayout.api.ProofOfLifeMethod;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.PayoutInstalment;

/** Entity to view, in one place so every read answers with the same shape. */
final class Views {

    private Views() {}

    /** {@code proofOfLifeRequired} is the caller's: it needs the stream, which this mapper does not load. */
    static PayoutInstalmentView of(PayoutInstalment i, boolean proofOfLifeRequired) {
        return new PayoutInstalmentView(i.getInstalmentId(), i.getPolicyNumber(), i.kind(), i.getDueDate(),
            i.getOriginalAmount(), i.getCurrentAmount(), i.getCurrency(), i.getRestatementReason(), i.status(),
            i.getStatusReason(), i.getStreamId(), i.getPayeeRef(),
            i.getProofOfLifeMethod() != null ? ProofOfLifeMethod.valueOf(i.getProofOfLifeMethod()) : null,
            i.getReviewedBy(), i.getApprovedBy(), i.getPaymentRunId(), i.getAttempts(),
            i.getGrossAmount(), i.getWithheldAmount(), i.getNetAmount(), proofOfLifeRequired);
    }
}
