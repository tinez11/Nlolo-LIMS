package tz.co.nlolo.lifeplatform.policy.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What may be claimed against this contract, for this life, on this date.
 *
 * <p>One answer for two very different contracts, and that is the point: {@code claims} must
 * not branch on product category. It cannot, in fact — {@code product::api} is not among its
 * allowed dependencies, so a {@code ProductCategory} anywhere in claims' bytecode fails
 * {@code ModularityTests}. Group-versus-individual is therefore decided here, in the module
 * that owns the member schedule.
 *
 * @param amount on a group scheme, the member's {@code covered_amount} in force on the date of
 *     event — already capped at the free cover limit wherever the limit bit, so it must NOT be
 *     capped again by a reader. On individual business, the policy's sum assured.
 * @param currencyCode the scheme's currency, or the policy's. An amount travelling without one
 *     is how a figure ends up rendered as the wrong money somewhere downstream.
 * @param policyMemberId echoed back on a group scheme, null on individual business.
 */
public record ClaimableCoverView(BigDecimal amount, String currencyCode, UUID policyMemberId) {}
