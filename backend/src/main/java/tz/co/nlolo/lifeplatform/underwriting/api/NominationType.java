package tz.co.nlolo.lifeplatform.underwriting.api;

/**
 * How a beneficiary is designated on a proposal.
 *
 * <p>Mirrors {@code policy.api.BeneficiaryType}. Separate for the same reason
 * {@link BeneficiaryNomination} is: underwriting does not depend on policy, and must not.
 */
public enum NominationType { PARTY, FREEFORM }
