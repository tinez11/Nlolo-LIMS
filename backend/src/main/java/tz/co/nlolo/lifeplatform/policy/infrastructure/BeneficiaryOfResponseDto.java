package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.api.BeneficiaryOfView;

import java.math.BigDecimal;

/**
 * Wire shape for {@code GET /beneficiaries?partyId=}. A thin rename layer over
 * {@link BeneficiaryOfView}, kept for the same reason every other {@code *ResponseDto} here is: the
 * HTTP contract is allowed to move independently of the module's own view.
 */
public record BeneficiaryOfResponseDto(
    String policyNumber,
    String policyStatus,
    BigDecimal sharePercent,
    boolean revocable) {

    public static BeneficiaryOfResponseDto from(BeneficiaryOfView view) {
        return new BeneficiaryOfResponseDto(view.policyNumber(), view.policyStatus(),
            view.sharePercent(), view.revocable());
    }
}
