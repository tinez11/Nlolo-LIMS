package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import tz.co.nlolo.lifeplatform.accumulation.api.StatementView;

import java.util.List;

/** A period of an account: opening, each kind of movement with its entries, closing -- reconciled. */
public record StatementResponse(String policyNumber, String periodFrom, String periodTo, int lastSeq,
                                MoneyResponse openingBalance, List<Group> groups, MoneyResponse closingBalance) {

    public record Group(String type, MoneyResponse total, List<AccountResponse.EntryResponse> entries) {}

    static StatementResponse from(StatementView s) {
        return new StatementResponse(s.policyNumber(), s.periodFrom().toString(), s.periodTo().toString(), s.lastSeq(),
            MoneyResponse.of(s.openingBalance(), s.currency()),
            s.groups().stream().map(g -> new Group(g.type().name(), MoneyResponse.of(g.total(), s.currency()),
                g.entries().stream().map(e -> AccountResponse.EntryResponse.from(e, s.currency())).toList())).toList(),
            MoneyResponse.of(s.closingBalance(), s.currency()));
    }
}
