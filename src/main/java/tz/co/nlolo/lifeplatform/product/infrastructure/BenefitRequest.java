package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.api.BenefitType;

public record BenefitRequest(BenefitType benefitType, String calculationMethod) {}
