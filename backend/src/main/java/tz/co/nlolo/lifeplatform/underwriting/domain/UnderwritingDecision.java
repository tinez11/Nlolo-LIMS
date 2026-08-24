package tz.co.nlolo.lifeplatform.underwriting.domain;

import java.math.BigDecimal;

public record UnderwritingDecision(Outcome outcome, BigDecimal loadingPercent, String reason) {
    public enum Outcome { ACCEPT, LOADED, DECLINED, POSTPONED }
}
