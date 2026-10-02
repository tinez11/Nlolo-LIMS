package tz.co.nlolo.lifeplatform.bonus.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Why a declaration did or did not attach -- the answer to "why did this policy get nothing?" */
public record BonusOutcomeView(UUID declarationId, LocalDate valuationDate, OutcomeKind outcome, String reason,
                               Instant decidedAt) {}
