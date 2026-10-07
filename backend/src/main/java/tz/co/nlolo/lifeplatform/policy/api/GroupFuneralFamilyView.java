package tz.co.nlolo.lifeplatform.policy.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One family on a group funeral scheme (2026-10-07): the main member's member row -- what the bill counts -- with the
 * association's number for them, the beneficiary they named, the family's cover in all, and every life, the main
 * member first.
 */
public record GroupFuneralFamilyView(UUID policyMemberId, String memberReference, String mainMemberName,
                                     MemberStatus status, LocalDate joinedOn, LocalDate leftOn,
                                     String beneficiaryName, String beneficiaryRelationship, String beneficiaryPhone,
                                     BigDecimal familyCover, List<CoveredLifeView> lives) {}
