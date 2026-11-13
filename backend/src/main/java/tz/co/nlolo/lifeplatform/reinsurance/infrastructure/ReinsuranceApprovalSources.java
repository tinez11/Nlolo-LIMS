package tz.co.nlolo.lifeplatform.reinsurance.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tz.co.nlolo.lifeplatform.approvals.api.ApprovalSource;
import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceApi;

import java.math.BigDecimal;
import java.util.Comparator;

/** A quarter's reinsurance statement waiting for the finance approver (2026-10-27). */
@Configuration
class ReinsuranceApprovalSources {

    @Bean
    ApprovalSource reinsuranceStatementApprovals(ReinsuranceApi reinsurance) {
        return ApprovalSource.of("reinsurance-statement", "Reinsurance statements", "Approve and post",
            "reinsurance.approve", limit -> ApprovalSource.Pending.of(reinsurance.listStatements("SUBMITTED", null).stream()
                .sorted(Comparator.comparing(s -> s.submittedAt() == null ? s.preparedAt() : s.submittedAt()))
                .map(s -> {
                    boolean weOwe = s.owedByUs() != null && s.owedByUs().signum() > 0;
                    BigDecimal balance = weOwe ? s.owedByUs() : s.owedToUs();
                    return new ApprovalSource.Item(s.statementId().toString(), null,
                        s.reinsurerName() + ", " + s.quarter() + (weOwe ? " · we owe them" : " · they owe us"),
                        balance, s.currency(), s.preparer(), s.submittedAt() == null ? s.preparedAt() : s.submittedAt());
                })
                .toList(), limit));
    }

    /** A new treaty waiting for a second person before it cedes anything (2026-11-12, finance finding 170). */
    @Bean
    ApprovalSource treatyApprovals(ReinsuranceApi reinsurance) {
        return ApprovalSource.of("treaty", "New treaties", "Approve", "reinsurance.approve",
            limit -> ApprovalSource.Pending.of(reinsurance.listTreaties(
                    tz.co.nlolo.lifeplatform.reinsurance.api.TreatyStatus.PROPOSED).stream()
                .map(t -> new ApprovalSource.Item(t.treatyId().toString(), null,
                    t.reinsurerName() + ", " + t.treatyType().name().replace('_', ' ').toLowerCase() + " from "
                        + ApprovalSource.day(t.effectiveFrom()),
                    null, null, t.createdBy(), null))
                .toList(), limit));
    }

    /** A settlement of an approved statement waiting for a second finance person (2026-11-08). */
    @Bean
    ApprovalSource reinsuranceSettlementApprovals(tz.co.nlolo.lifeplatform.reinsurance.application.StatementSettlements settlements) {
        return ApprovalSource.of("reinsurance-settlement", "Reinsurance settlements", "Confirm",
            "reinsurance.approve", limit -> ApprovalSource.Pending.of(settlements.waiting().stream()
                .map(s -> new ApprovalSource.Item(s.statementId().toString(), null,
                    s.reinsurerName() + ", " + s.quarter() + " · " + SETTLEMENT_WORDS.getOrDefault(s.kind(), s.kind()),
                    s.amount(), s.currency(), s.recordedBy(), s.recordedAt()))
                .toList(), limit));
    }

    private static final java.util.Map<String, String> SETTLEMENT_WORDS = java.util.Map.of(
        "RECEIVED", "received from them", "PAID", "to pay them", "PROFIT_COMMISSION", "profit commission received",
        "WITHHELD_RELEASED", "funds withheld released");
}
