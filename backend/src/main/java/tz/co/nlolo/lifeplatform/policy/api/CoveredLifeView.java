package tz.co.nlolo.lifeplatform.policy.api;

import tz.co.nlolo.lifeplatform.product.api.FuneralRole;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One life on a funeral policy.
 *
 * @param partyId           the main member's party; a dependant's once promoted at claim, else null
 * @param waitingPeriodEnds the first day a natural death is paid; null when the version has no waiting period
 * @param coverEnd          a scheduled end (a removal, or free cover after the main member's death); the
 *                          life is covered up to, not on, this date
 * @param endReason         DECEASED, REMOVED, AGED_OUT, MAIN_MEMBER_DIED, FREE_COVER_ENDED or POLICY_ENDED
 */
public record CoveredLifeView(UUID coveredLifeId, FuneralRole role, String fullName, LocalDate dateOfBirth, String sex,
                              String idNumber, boolean student, UUID partyId, BigDecimal benefit, BigDecimal yearlyPremium,
                              int pricedAtAge, LocalDate coverStart, LocalDate waitingPeriodEnds, LocalDate coverEnd,
                              String status, String endReason, LocalDate endedOn) {}
