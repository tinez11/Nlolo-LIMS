package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import org.springframework.data.domain.Page;
import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutInstalmentView;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The wire shape. Money is a decimal string plus a currency code, never a JSON number -- this
 * platform's rule everywhere, so no reader has to wonder what a float did to a shilling.
 */
public record PayoutInstalmentResponse(UUID instalmentId, String policyNumber, String kind, String dueDate,
                                       Map<String, String> originalAmount, Map<String, String> currentAmount,
                                       String restatementReason, String status, String statusReason, UUID streamId,
                                       String payeeRef, String proofOfLifeMethod, String reviewedBy,
                                       String approvedBy, UUID paymentRunId, int attempts,
                                       Map<String, String> grossAmount, Map<String, String> withheldAmount,
                                       Map<String, String> netAmount) {

    public static PayoutInstalmentResponse from(PayoutInstalmentView v) {
        return new PayoutInstalmentResponse(v.instalmentId(), v.policyNumber(), v.kind().name(), v.dueDate().toString(),
            money(v.originalAmount(), v.currency()), money(v.currentAmount(), v.currency()), v.restatementReason(),
            v.status().name(), v.statusReason(), v.streamId(), v.payeeRef(),
            v.proofOfLifeMethod() != null ? v.proofOfLifeMethod().name() : null,
            v.reviewedBy(), v.approvedBy(), v.paymentRunId(), v.attempts(),
            money(v.grossAmount(), v.currency()), money(v.withheldAmount(), v.currency()), money(v.netAmount(), v.currency()));
    }

    /** Null, not a zero: a premium return genuinely has no amount until it falls due. */
    static Map<String, String> money(BigDecimal amount, String currency) {
        return amount == null ? null : Map.of("amount", amount.toPlainString(), "currencyCode", currency);
    }

    public record PageMetaDto(int page, int pageSize, int totalElements) {}

    public record PageResponse(List<PayoutInstalmentResponse> items, PageMetaDto page) {

        public static PageResponse from(Page<PayoutInstalmentView> p) {
            return new PageResponse(p.getContent().stream().map(PayoutInstalmentResponse::from).toList(),
                new PageMetaDto(p.getNumber(), p.getSize(), (int) p.getTotalElements()));
        }
    }
}
