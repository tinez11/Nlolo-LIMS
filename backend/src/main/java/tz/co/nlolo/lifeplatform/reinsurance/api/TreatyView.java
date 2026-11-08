package tz.co.nlolo.lifeplatform.reinsurance.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Read view of {@code reinsurance.domain.ReinsuranceTreaty}. {@code cessionPercent} is non-null
 * exactly for QUOTA_SHARE (V2's {@code treaty_cession_percent_required_for_quota_share}). */
public record TreatyView(UUID treatyId, String reinsurerName, TreatyType treatyType, TreatyStatus status,
                          BigDecimal retentionLimitAmount, String retentionLimitCurrency,
                          BigDecimal cessionPercent, LocalDate effectiveFrom, LocalDate effectiveTo,
                          BigDecimal commissionPercent, BigDecimal xolAnnualPremium,
                          /** Where it stands today, from its dates -- see {@link TreatyStanding}. */
                          TreatyStanding standing,
                          /** How many policies are ceded to it (2026-11-08); null where the view does not count them. */
                          Long cededPolicies,
                          /** Whether a policy issued today is ceded to it: false for one in force while another, later
                           *  one is too -- every policy goes to one treaty, so this one takes no new business. */
                          boolean takesNewPolicies) {}
