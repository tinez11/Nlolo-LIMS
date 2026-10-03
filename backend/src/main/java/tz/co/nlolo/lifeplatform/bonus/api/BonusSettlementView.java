package tz.co.nlolo.lifeplatform.bonus.api;

import java.time.Instant;
import java.time.LocalDate;

/** What bonuses were worth at one exit -- recorded once, at the exit, and never revalued. */
public record BonusSettlementView(ExitType exitType, String exitRef, LocalDate exitDate, BonusValuation valuation,
                                  Instant recordedAt) {}
