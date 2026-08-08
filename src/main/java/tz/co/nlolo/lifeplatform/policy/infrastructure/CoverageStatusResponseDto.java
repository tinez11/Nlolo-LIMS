package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.api.CoverageStatusView;
import tz.co.nlolo.lifeplatform.product.api.BenefitType;

import java.time.LocalDate;
import java.util.List;

public record CoverageStatusResponseDto(String policyNumber, LocalDate asOf, List<CoverageStatusResponseDto.ActiveCoverageDto> activeCoverages) {
    public record ActiveCoverageDto(BenefitType benefitType, MoneyDto sumAssured) {}

    public static CoverageStatusResponseDto from(CoverageStatusView view) {
        return new CoverageStatusResponseDto(view.policyNumber(), view.asOf(),
            view.activeCoverages().stream()
                .map(c -> new ActiveCoverageDto(c.benefitType(), new MoneyDto(c.sumAssuredAmount().toPlainString(), c.sumAssuredCurrency())))
                .toList());
    }
}
