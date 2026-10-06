package tz.co.nlolo.lifeplatform.finaccounting.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read view of a journal entry and its legs. An entry always has exactly two legs in M9, and
 * their DR and CR totals are always equal -- see {@code JournalEntry}'s own invariant. */
public record JournalEntryView(UUID journalEntryId, String sourceEvent, String sourceRef,
                                String period, String policyNumber, Instant postedAt,
                                List<GlPostingView> postings, JournalSource sourceType, int policyRegisterVersion,
                                String ruleVersion) {}
