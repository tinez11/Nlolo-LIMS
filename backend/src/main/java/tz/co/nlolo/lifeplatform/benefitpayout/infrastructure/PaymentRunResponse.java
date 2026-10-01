package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import tz.co.nlolo.lifeplatform.benefitpayout.api.PaymentRunView;

import java.util.Map;
import java.util.UUID;

/** The wire shape of a payment run. Money as {@code {amount, currencyCode}}, never a JSON number. */
public record PaymentRunResponse(UUID paymentRunId, String runDate, String status, String approvedBy,
                                 int instalmentCount, Map<String, String> total) {

    public static PaymentRunResponse from(PaymentRunView v) {
        return new PaymentRunResponse(v.paymentRunId(), v.runDate().toString(), v.status(), v.approvedBy(),
            v.instalmentCount(),
            Map.of("amount", v.total().setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString(),
                   "currencyCode", v.currency()));
    }
}
