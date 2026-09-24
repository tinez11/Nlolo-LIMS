package tz.co.nlolo.lifeplatform.claims.api;

import java.math.BigDecimal;

/**
 * The ceiling on a claim, as a number a person can be shown BEFORE they type.
 *
 * <p>This figure already existed and was only ever used to refuse: {@code Claim.approve} takes it
 * as a bound and throws "approved amount N exceeds the M this claim is covered for". Nothing
 * published it, so the only way to learn M was to guess wrong once.
 *
 * <p>A claims-owned record rather than {@code policy.api.ClaimableCoverView} passed through.
 * {@code claims} may depend on {@code policy::api}, so re-exporting it would compile — but every
 * consumer of {@code claims::api} would then be reading a policy type, and the member id that
 * view echoes back is something the caller of THIS method already knows from the claim.
 *
 * @param amount on a group scheme the member's covered amount in force on the date of event —
 *     for credit life, the declining loan balance, so it falls every month and a figure quoted
 *     last week is not the figure today. On individual business, the policy's sum assured.
 * @param currencyCode the scheme's currency or the policy's. Never assumed by a reader.
 */
public record ClaimCoverView(BigDecimal amount, String currencyCode) {}
