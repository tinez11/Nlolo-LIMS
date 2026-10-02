package tz.co.nlolo.lifeplatform.bonus.infrastructure;

import tz.co.nlolo.lifeplatform.bonus.api.BonusEntryView;
import tz.co.nlolo.lifeplatform.bonus.api.BonusOutcomeView;
import tz.co.nlolo.lifeplatform.bonus.api.BonusSettlementView;
import tz.co.nlolo.lifeplatform.bonus.api.PolicyBonusView;

import java.math.BigDecimal;
import java.util.List;

/** A policy's bonus history on the wire. Money is a decimal string and a currency; rates have no trailing zeros. */
public record PolicyBonusResponse(String policyNumber, MoneyResponse attachedTotal, List<Entry> entries,
                                  List<Outcome> outcomes, List<Settlement> settlements) {

    public record Entry(String entryId, int seq, String type, MoneyResponse amount, MoneyResponse totalAfter,
                        String effectiveDate, String declarationId, MoneyResponse basis, String ratePercent,
                        String reversesEntryId, String reason, String createdBy, String createdAt) {
        static Entry from(BonusEntryView e, String currency) {
            return new Entry(e.entryId().toString(), e.seq(), e.type().name(), MoneyResponse.of(e.amount(), currency),
                MoneyResponse.of(e.totalAfter(), currency), e.effectiveDate().toString(),
                e.declarationId() != null ? e.declarationId().toString() : null,
                e.basisAmount() != null ? MoneyResponse.of(e.basisAmount(), currency) : null, rate(e.ratePercent()),
                e.reversesEntryId() != null ? e.reversesEntryId().toString() : null, e.reason(), e.createdBy(),
                e.createdAt().toString());
        }
    }

    public record Outcome(String declarationId, String valuationDate, String outcome, String reason, String decidedAt) {
        static Outcome from(BonusOutcomeView o) {
            return new Outcome(o.declarationId().toString(), o.valuationDate() != null ? o.valuationDate().toString() : null,
                o.outcome().name(), o.reason(), o.decidedAt().toString());
        }
    }

    public record Settlement(String exitType, String exitRef, String exitDate, BonusValuationResponse value,
                             String recordedAt) {
        static Settlement from(BonusSettlementView s, String currency) {
            return new Settlement(s.exitType().name(), s.exitRef(), s.exitDate().toString(),
                BonusValuationResponse.from(s.valuation(), currency), s.recordedAt().toString());
        }
    }

    public static PolicyBonusResponse from(PolicyBonusView v) {
        String c = v.currency();
        return new PolicyBonusResponse(v.policyNumber(), MoneyResponse.of(v.attachedTotal(), c),
            v.entries().stream().map(e -> Entry.from(e, c)).toList(),
            v.outcomes().stream().map(Outcome::from).toList(),
            v.settlements().stream().map(s -> Settlement.from(s, c)).toList());
    }

    static String rate(BigDecimal rate) {
        return rate != null ? rate.stripTrailingZeros().toPlainString() : null;
    }
}
