package tz.co.nlolo.lifeplatform.billing.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import tz.co.nlolo.lifeplatform.approvals.api.ApprovalSource;
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.billing.api.FieldReceiptView;

import java.util.ArrayList;
import java.util.List;

/** Cash an agent collected and nobody has matched yet, overdue first (2026-10-27). */
@Configuration
class BillingApprovalSources {

    @Bean
    ApprovalSource fieldReceiptsToMatch(BillingApi billing) {
        return ApprovalSource.of("field-receipt", "Field receipts to match", "Match", "collections.manage", false,
            limit -> {
                PageRequest page = PageRequest.of(0, Math.max(1, limit));
                Page<FieldReceiptView> overdue = billing.searchFieldReceipts("RECONCILIATION_OVERDUE", page);
                Page<FieldReceiptView> pending = billing.searchFieldReceipts("PENDING_RECONCILIATION", page);
                List<ApprovalSource.Item> rows = new ArrayList<>();
                overdue.forEach(r -> rows.add(item(r, "Cash collected by an agent, past its matching deadline")));
                pending.forEach(r -> rows.add(item(r, "Cash collected by an agent")));
                return new ApprovalSource.Pending(overdue.getTotalElements() + pending.getTotalElements(),
                    rows.stream().limit(limit).toList());
            });
    }

    private static ApprovalSource.Item item(FieldReceiptView r, String what) {
        return new ApprovalSource.Item(r.receiptId().toString(), r.policyNumber(), what, r.amount(), r.currency(), null,
            r.capturedAtServer());
    }
}
