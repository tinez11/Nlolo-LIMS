package tz.co.nlolo.lifeplatform.policy.api;

import tz.co.nlolo.lifeplatform.product.api.BenefitType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CoverageStatusView(String policyNumber, LocalDate asOf, List<CoverageStatusView.ActiveCoverageView> activeCoverages) {
    public record ActiveCoverageView(BenefitType benefitType, BigDecimal sumAssuredAmount, String sumAssuredCurrency) {}
}
