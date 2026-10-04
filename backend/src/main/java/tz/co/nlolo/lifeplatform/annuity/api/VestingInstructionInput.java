package tz.co.nlolo.lifeplatform.annuity.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A staff instruction for a pension's vesting (product step 5 D2).
 *
 * @param vestingDate   null means the target date
 * @param contributions CONTINUE or STOP on a deferral (a date after the target); null otherwise
 */
public record VestingInstructionInput(LocalDate vestingDate, String formCode, String frequency, UUID jointLifePartyId,
                                      BigDecimal lumpSumPercent, String contributions) {}
