package tz.co.nlolo.lifeplatform.payment.infrastructure;

import tz.co.nlolo.lifeplatform.payment.application.PaymentApiImpl;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ACL boundary openapi-payment.yaml describes: "each aggregator has its own callback payload
 * shape; this endpoint ... translat[es] that shape into PaymentConfirmed/PaymentFailed
 * internally." Reached only after {@link MobileMoneyHmacFilter} has already verified the
 * request's signature and replay window (SecurityConfig wires the filter in ahead of this
 * controller and {@code permitAll()}s exactly this method+path) -- there is no
 * {@code @PreAuthorize} here because Keycloak authentication never applies to this path at all,
 * by design, not by omission.
 *
 * <p>Deliberately thin: all tenant-resolution and transactional orchestration lives in
 * {@link PaymentApiImpl#applyGatewayCallback} (see its javadoc for why that method is public on
 * the concrete impl rather than added to the read-only {@code payment.api.PaymentApi}). This
 * class injects the concrete impl, not the interface, for the same reason
 * {@code PaymentRequestListener} does.
 *
 * <p>Always acks HTTP 200 for a well-formed, signature-verified callback, matching the task's
 * own framing: a gateway that receives a non-200 will retry, and every status transition this
 * reaches is already idempotent on redelivery (DisbursementInstruction/PaymentTransaction's
 * markCompleted/markConfirmed/markFailed) or, for a genuinely unrecognized or conflicting
 * outcome, is not something an identical retry could ever fix either -- so returning anything
 * other than 200 here would only provoke a retry storm without changing the outcome. A malformed
 * body (missing status/gatewayReference) still 400s via {@code @Valid} before reaching this
 * method at all, and a bad signature still 401s in the filter before reaching this controller at
 * all -- neither of those is swallowed, only genuine post-authentication processing outcomes are.
 */
@RestController
public class MobileMoneyCallbackController {

    private static final Logger log = LoggerFactory.getLogger(MobileMoneyCallbackController.class);

    private final PaymentApiImpl paymentApiImpl;

    public MobileMoneyCallbackController(PaymentApiImpl paymentApiImpl) {
        this.paymentApiImpl = paymentApiImpl;
    }

    @PostMapping(MobileMoneyHmacFilter.CALLBACK_PATH)
    public ResponseEntity<Void> handleCallback(@Valid @RequestBody MobileMoneyCallbackRequestDto callback) {
        try {
            boolean matched = paymentApiImpl.applyGatewayCallback(
                callback.gatewayReference(), isSuccess(callback.status()), callback.reason());
            if (!matched) {
                log.warn("Mobile-money callback referenced an unknown gatewayReference={} (own reference={})",
                    callback.gatewayReference(), callback.reference());
            }
        } catch (Exception e) {
            // Logged, not rethrown -- see class javadoc on why this always acks 200 regardless.
            log.error("Mobile-money callback processing failed for gatewayReference={}",
                callback.gatewayReference(), e);
        }
        return ResponseEntity.ok().build();
    }

    private static boolean isSuccess(String status) {
        return "SUCCESS".equalsIgnoreCase(status);
    }
}
