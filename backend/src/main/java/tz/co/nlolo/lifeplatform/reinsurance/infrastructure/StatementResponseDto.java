package tz.co.nlolo.lifeplatform.reinsurance.infrastructure;

import tz.co.nlolo.lifeplatform.reinsurance.api.StatementView;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A quarterly statement on the wire (IFRS 17 I3d): amounts as decimal STRINGS in {@code currency}, never JSON numbers. */
public record StatementResponseDto(UUID statementId, UUID treatyId, String reinsurerName, String quarter, String currency,
                                   String status, String premium, String commission, String recoveries,
                                   String fundsWithheld, String profitCommission, String owedToUs, String owedByUs,
                                   String reason, List<String> documentRefs, String preparer, Instant preparedAt,
                                   Instant submittedAt, String decidedBy, Instant decidedAt, String decisionReason,
                                   List<ItemDto> items, List<JournalLineDto> journal) {

    public record ItemDto(String type, UUID id, String label, String amount) {}

    public record JournalLineDto(String entry, String account, String side, String amount) {}

    public static StatementResponseDto from(StatementView v) {
        return new StatementResponseDto(v.statementId(), v.treatyId(), v.reinsurerName(), v.quarter(), v.currency(),
            v.status(), plain(v.premium()), plain(v.commission()), plain(v.recoveries()), plain(v.fundsWithheld()),
            plain(v.profitCommission()), plain(v.owedToUs()), plain(v.owedByUs()), v.reason(), v.documentRefs(),
            v.preparer(), v.preparedAt(), v.submittedAt(), v.decidedBy(), v.decidedAt(), v.decisionReason(),
            v.items().stream().map(i -> new ItemDto(i.type(), i.id(), i.label(), plain(i.amount()))).toList(),
            v.journal().stream().map(l -> new JournalLineDto(l.entry(), l.account(), l.side(), plain(l.amount()))).toList());
    }

    /** Two decimals always: "0.00", never "0" -- a computed max(..., 0) has scale 0. */
    private static String plain(BigDecimal v) {
        return v == null ? null : v.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
    }
}
