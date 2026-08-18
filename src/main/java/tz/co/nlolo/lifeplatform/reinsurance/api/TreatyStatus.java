package tz.co.nlolo.lifeplatform.reinsurance.api;

/** Matches {@code reinsurance_treaty.status}'s CHECK (reinsurance/V2 section 7). Only ACTIVE
 * treaties are eligible for cession selection. */
public enum TreatyStatus { ACTIVE, EXPIRED }
