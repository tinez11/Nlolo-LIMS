package tz.co.nlolo.lifeplatform.policy.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A master group policy read as a scheme: how it values its members, and where it stands.
 *
 * @param policyholderPartyId the employer or association that owns the contract. Not a
 *     life assured -- the lives are the member schedule, which is why a GROUP_LIFE policy
 *     carries no {@code lifeAssuredPartyId} (see migration V8).
 * @param fclAmount the free cover limit, or null for a scheme that has none. Null is a
 *     real scheme design and is <b>not</b> the same as a limit of zero, which would send
 *     every member to underwriting.
 * @param activeMemberCount how many lives are on the scheme right now.
 * @param totalCoveredAmount the sum of what every active member is actually covered for
 *     -- <b>derived on read</b>, never stored on this record. It equals the master
 *     policy's sum assured, which the service restates inside the same transaction as any
 *     membership change; {@code GroupSchemeTotalsTest} asserts the two agree.
 * @param membersRequiringEvidence how many members are over the limit with underwriting
 *     still outstanding. The number a scheme administrator chases, and the reason it sits
 *     on the summary rather than being counted by eye down a 500-row schedule.
 */
public record GroupSchemeView(String policyNumber, UUID policyholderPartyId, PolicyStatus status,
                               LocalDate commencementDate, Integer policyTermMonths,
                               BenefitBasis benefitBasis, BigDecimal flatBenefitAmount,
                               BigDecimal salaryMultiple, BigDecimal fclAmount, String currency,
                               long activeMemberCount, BigDecimal totalCoveredAmount,
                               long membersRequiringEvidence,
                               List<GroupSchemeGradeView> grades) {}
