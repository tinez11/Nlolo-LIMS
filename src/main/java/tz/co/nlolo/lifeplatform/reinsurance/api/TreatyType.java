package tz.co.nlolo.lifeplatform.reinsurance.api;

/**
 * Matches {@code reinsurance_treaty.treaty_type}'s CHECK (reinsurance/V1).
 *
 * <p><b>XOL is accepted by the schema and by treaty authoring, but cedes NOTHING at
 * issuance</b> -- excess-of-loss is a claim-level treaty, so "automatic cession calculation on
 * new business" does not apply to it. It participates only in recovery, where it recovers the
 * excess of a loss over retention. See {@code CessionCalculator} and {@code RecoveryCalculator}.
 */
public enum TreatyType { QUOTA_SHARE, SURPLUS, XOL }
