package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import tz.co.nlolo.lifeplatform.accumulation.api.AccountView;
import tz.co.nlolo.lifeplatform.accumulation.api.LedgerEntryView;

import java.util.List;

/** A savings account and its whole ledger, in sequence order. */
public record AccountResponse(String policyNumber, String status, MoneyResponse balance, String openedOn,
                              String closedReason, String closedOn, List<EntryResponse> entries) {

    public record EntryResponse(String entryId, int seq, String type, MoneyResponse amount, MoneyResponse balanceAfter,
                                String effectiveDate, String postedAt, String sourceType, String sourceRef,
                                String reversesEntryId, String reason, String createdBy, String approvedBy) {
        static EntryResponse from(LedgerEntryView e, String currency) {
            return new EntryResponse(e.entryId().toString(), e.seq(), e.type().name(), MoneyResponse.of(e.amount(), currency),
                MoneyResponse.of(e.balanceAfter(), currency), e.effectiveDate().toString(), e.postedAt().toString(),
                e.sourceType(), e.sourceRef(), e.reversesEntryId() != null ? e.reversesEntryId().toString() : null,
                e.reason(), e.createdBy(), e.approvedBy());
        }
    }

    static AccountResponse from(AccountView a, List<LedgerEntryView> entries) {
        return new AccountResponse(a.policyNumber(), a.status().name(), MoneyResponse.of(a.balance(), a.currency()),
            a.openedOn().toString(), a.closedReason(), a.closedOn() != null ? a.closedOn().toString() : null,
            entries.stream().map(e -> EntryResponse.from(e, a.currency())).toList());
    }
}
