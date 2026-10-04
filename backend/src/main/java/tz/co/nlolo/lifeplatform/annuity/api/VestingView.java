package tz.co.nlolo.lifeplatform.annuity.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A deferred annuity's vesting (product step 5 D2): when and into what it vests, any hold, and once
 * vested, the balance and lump sum.
 *
 * @param vestingDate      the current instruction's date, else the target
 * @param formCode         the current instruction's form, else the default
 * @param frequency        the current instruction's frequency, else the default
 * @param lumpSumPercent   the current instruction's lump sum; zero with no instruction (spec Q4)
 * @param instructed       whether a staff instruction stands, or the defaults apply
 * @param contributions    CONTINUE or STOP on a deferral; null otherwise
 */
public record VestingView(String policyNumber, LocalDate targetDate, LocalDate earliestVestingDate,
                          LocalDate latestVestingDate, LocalDate vestingDate, String formCode, String frequency,
                          UUID jointLifePartyId, BigDecimal lumpSumPercent, BigDecimal maxCommutationPercent,
                          boolean instructed, String contributions,
                          String holdReason, Instant heldAt, LocalDate vestedOn, BigDecimal vestedBalance,
                          BigDecimal lumpSum, String ageConfirmedBy, LocalDate confirmedDateOfBirth, String confirmedSex,
                          String currency) {}
