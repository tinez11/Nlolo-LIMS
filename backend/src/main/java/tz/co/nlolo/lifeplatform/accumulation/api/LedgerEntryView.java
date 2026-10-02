package tz.co.nlolo.lifeplatform.accumulation.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LedgerEntryView(UUID entryId, UUID postingId, int seq, EntryType type, BigDecimal amount,
                              BigDecimal balanceAfter, LocalDate effectiveDate, Instant postedAt,
                              String sourceType, String sourceRef, UUID reversesEntryId, String reason,
                              String createdBy, String approvedBy) {}
