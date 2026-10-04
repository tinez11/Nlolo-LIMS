package tz.co.nlolo.lifeplatform.annuity.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** An annuity contract and its locked figures (product step 5). Lock fields are null until the lock. */
public record AnnuityContractView(String policyNumber, ContractStatus status, String formCode, Integer guaranteeYears,
                                  boolean joint, BigDecimal survivorPercent, BigDecimal escalationPercent,
                                  boolean capitalProtected, String timing, String frequency,
                                  UUID annuitantPartyId, UUID jointLifePartyId, BigDecimal purchasePrice, String currency,
                                  LocalDate lockedOn, Integer annuitantAge, Integer jointAge, String rateSex,
                                  BigDecimal annualRatePerMille, BigDecimal factor, BigDecimal annualIncome,
                                  BigDecimal instalment, LocalDate firstDueDate, LocalDate guaranteeEndDate,
                                  UUID firstDeathPartyId, LocalDate firstDeathDate, LocalDate lastDeathDate,
                                  BigDecimal overpaymentOwed, String lockFailureReason) {}
