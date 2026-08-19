package tz.co.nlolo.lifeplatform.finaccounting.api;

import java.math.BigDecimal;
import java.util.UUID;

/** Read view of one leg of a journal entry ({@code finaccounting.gl_posting}). */
public record GlPostingView(UUID postingId, UUID journalEntryId, String accountCode,
                             PostingDirection direction, BigDecimal amount, String currency,
                             String period, String policyNumber, String sourceEvent, String sourceRef) {}
