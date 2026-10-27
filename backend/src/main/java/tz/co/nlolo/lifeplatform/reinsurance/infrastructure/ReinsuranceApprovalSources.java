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
}
