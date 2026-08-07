package tz.co.nlolo.lifeplatform.product.infrastructure;

import java.math.BigDecimal;

public record FundDefinitionRequest(String fundCode, BigDecimal currentNav) {}
