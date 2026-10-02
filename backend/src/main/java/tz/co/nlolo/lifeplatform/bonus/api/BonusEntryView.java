package tz.co.nlolo.lifeplatform.bonus.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One movement in a policy's attached bonuses, as the ledger holds it. */
public record BonusEntryView(UUID entryId, int seq, BonusEntryType type, BigDecimal amount, BigDecimal totalAfter,
                             LocalDate effectiveDate, UUID declarationId, BigDecimal basisAmount, BigDecimal ratePercent,
                             UUID reversesEntryId, String reason, String createdBy, Instant createdAt) {}
