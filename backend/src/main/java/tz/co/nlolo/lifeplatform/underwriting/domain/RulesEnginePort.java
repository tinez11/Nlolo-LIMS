package tz.co.nlolo.lifeplatform.underwriting.domain;

/**
 * Port abstracting the underwriting decision engine. The current implementation
 * (SimpleRulesEngine) is an explicitly-flagged PLACEHOLDER algorithm -- see its
 * javadoc. This interface exists so a real Drools/DRL-backed engine can be
 * substituted later with zero change to callers (Task 5's UnderwritingApiImpl).
 */
public interface RulesEnginePort {
    UnderwritingDecision evaluate(RiskProfile riskProfile);
}
