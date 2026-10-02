package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import tz.co.nlolo.lifeplatform.accumulation.api.StatementRecordView;

/**
 * A filed statement. The storage key is left out on purpose: it is not the console's business, and
 * the PDF downloads by statement id.
 */
public record StatementRecordResponse(String statementId, String policyNumber, String periodFrom, String periodTo,
                                      int lastSeq, String generatedBy, String generatedAt) {
    static StatementRecordResponse from(StatementRecordView v) {
        return new StatementRecordResponse(v.statementId().toString(), v.policyNumber(), v.periodFrom().toString(),
            v.periodTo().toString(), v.lastSeq(), v.generatedBy(), v.generatedAt().toString());
    }
}
