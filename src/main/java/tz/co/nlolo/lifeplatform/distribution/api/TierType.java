package tz.co.nlolo.lifeplatform.distribution.api;

/** Matches {@code distribution.commission_rule.tier_type}'s and
 * {@code distribution.commission_accrual.tier_type}'s CHECK constraints exactly
 * (db-migrations/distribution/V1:48-49, V2:136-137).
 *
 * <p>{@code THRESHOLD_BONUS} is kept here because the DB CHECK allows it and a rule or accrual
 * row could legally carry it, but nothing on this platform computes it (M7 user decision 1):
 * {@code CommissionCalculator} (Task 6) must skip it explicitly with a comment, and
 * {@code CommissionRule}'s constructor (below) already rejects it outright, so no plan can ever
 * be authored with it. This is deliberate scope-narrowing -- four tiers implemented, one
 * deferred -- not an oversight, so do not remove it from the enum. */
public enum TierType {
    FIRST_YEAR,
    RENEWAL,
    OVERRIDE,
    SUPERVISOR_OVERRIDE,
    THRESHOLD_BONUS
}
