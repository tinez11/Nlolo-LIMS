package tz.co.nlolo.lifeplatform.policy.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One insured life on a scheme, with the benefit currently in force for them.
 *
 * @param benefitAmount what the scheme's basis says this member is worth
 * @param coveredAmount what they are ACTUALLY insured for, which is the benefit capped at
 *     the free cover limit while evidence is outstanding or after a decline. This is the
 *     figure a claim pays, and the only one on this record that should ever be quoted to
 *     a member as "your cover".
 * @param benefitEffectiveFrom the date the in-force benefit row took effect. Present so a
 *     reader can tell a benefit set at inception from one restated at renewal without
 *     opening the history.
 * @param salaryAmount the input the benefit came from. Null on any scheme that is not
 *     SALARY_MULTIPLE, where no salary was ever collected.
 * @param currency the scheme's currency, carried here so the amounts above are never
 *     handed to a caller without one. A scheme has exactly one, and a member cannot have
 *     a different one -- but an amount travelling without its currency is how a figure
 *     ends up rendered as the wrong money somewhere down the line.
 */
public record PolicyMemberView(UUID policyMemberId, UUID memberPartyId, String gradeCode,
                                LocalDate joinedOn, LocalDate leftOn, MemberStatus status,
                                MemberUnderwritingStatus underwritingStatus, UUID underwritingCaseId,
                                BigDecimal salaryAmount, BigDecimal benefitAmount,
                                BigDecimal coveredAmount, String currency,
                                LocalDate benefitEffectiveFrom) {}
