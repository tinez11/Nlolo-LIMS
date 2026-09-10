package tz.co.nlolo.lifeplatform.underwriting.api;

/**
 * How each member's benefit is arrived at, as proposed.
 *
 * <p>Same three values as {@code policy.api.BenefitBasis}, and deliberately a SEPARATE enum.
 * {@code underwriting} may not depend on {@code policy} — policy already depends on
 * underwriting and the cycle would be immediate — so the two are mapped at the boundary by
 * {@code policy.application.UnderwritingDecisionEventListener}, which sits on the policy side
 * and is therefore the one place allowed to see both.
 *
 * <p>Exactly the arrangement {@code underwriting.api.BeneficiaryNomination} and
 * {@code policy.api.BeneficiaryInput} already have, and for the same reason. Two identical
 * records here are a module boundary, not duplication to be tidied away.
 */
public enum GroupBenefitBasis { FLAT, SALARY_MULTIPLE, GRADED }
