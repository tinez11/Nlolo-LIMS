package tz.co.nlolo.lifeplatform.accumulation.application;

import tz.co.nlolo.lifeplatform.accumulation.api.AccountView;
import tz.co.nlolo.lifeplatform.accumulation.api.LedgerEntryView;
import tz.co.nlolo.lifeplatform.accumulation.domain.Account;
import tz.co.nlolo.lifeplatform.accumulation.domain.LedgerEntry;
import tz.co.nlolo.lifeplatform.accumulation.domain.Posting;

final class Views {
    private Views() {}

    static AccountView of(Account a) {
        return new AccountView(a.getPolicyNumber(), a.getProductId(), a.getProductVersionId(), a.status(),
            a.getBalance(), a.getCurrency(), a.getOpenedOn(), a.getClosedReason(), a.getClosedOn());
    }

    static LedgerEntryView of(LedgerEntry e, Posting p) {
        return new LedgerEntryView(e.getEntryId(), e.getPostingId(), e.getSeq(), e.type(), e.getAmount(),
            e.getBalanceAfter(), e.getEffectiveDate(), e.getPostedAt(), p.getSourceType(), p.getSourceRef(),
            e.getReversesEntryId(), e.getReason(), e.getCreatedBy(), e.getApprovedBy());
    }
}
