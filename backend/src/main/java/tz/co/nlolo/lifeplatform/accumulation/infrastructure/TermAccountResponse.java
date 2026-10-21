package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import tz.co.nlolo.lifeplatform.accumulation.api.TermAccountView;

import java.math.BigDecimal;

/** A term account on the wire (2026-10-21). Its balance and movements are the account's own endpoints'. */
public record TermAccountResponse(String policyNumber, BigDecimal ratePercent, String maturityDate, String status,
                                  String defaultPayeeRef, String maturedOn) {

    static TermAccountResponse from(TermAccountView t) {
        return new TermAccountResponse(t.policyNumber(), t.ratePercent(), t.maturityDate().toString(), t.status().name(),
            t.defaultPayeeRef(), t.maturedOn() != null ? t.maturedOn().toString() : null);
    }
}
