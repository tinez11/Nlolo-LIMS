package tz.co.nlolo.lifeplatform.benefitpayout.api;

/**
 * Where one dated amount stands.
 *
 * <p>{@code IN_DOUBT} is deliberately not {@code FAILED}: the money may or may not have moved, and
 * treating the two alike is how a payout gets made twice. It stays APPROVED-adjacent and waits for
 * a human to reconcile it against the provider's statement.
 */
public enum InstalmentStatus { SCHEDULED, DUE, ON_HOLD, REVIEWED, APPROVED, PAID, FAILED, IN_DOUBT, CANCELLED }
