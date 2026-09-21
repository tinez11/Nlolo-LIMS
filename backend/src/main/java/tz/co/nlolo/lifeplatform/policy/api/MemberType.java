package tz.co.nlolo.lifeplatform.policy.api;

/**
 * Whether an insured life is a registered party or just a name on a schedule.
 *
 * <p>FREEFORM exists because group premium is not individually rated: a name plus the
 * scheme's basis values a member completely. Registering every life as a party would fill
 * the KYC queue with people nobody needs to identify -- 500 employees' dependants, or 400
 * borrowers a month -- and a queue full of rows nobody will ever work is a queue nobody
 * reads.
 *
 * <p>A freeform life is promoted to a real party at claim, when there is a death
 * certificate and an identity worth verifying.
 *
 * <p>See docs/superpowers/specs/2026-09-21-credit-life-design.md §2.2.
 */
public enum MemberType { PARTY, FREEFORM }
