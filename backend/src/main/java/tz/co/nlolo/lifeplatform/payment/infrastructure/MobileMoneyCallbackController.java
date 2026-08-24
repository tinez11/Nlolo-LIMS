package tz.co.nlolo.lifeplatform.payment.infrastructure;

import tz.co.nlolo.lifeplatform.payment.application.PaymentApiImpl;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Set;

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

    /** Review fix (Important 3): a real, alertable signal distinct from ordinary NOT_FOUND
     * logging -- an AMBIGUOUS outcome is a genuine cross-tenant gateway_reference collision that
     * this platform correctly refuses to guess on, not a data-entry mistake, and without a
     * separate counter it was invisible to anyone watching dashboards/alerts rather than reading
     * logs line-by-line. Mirrors MobileMoneyGatewayAdapter's own counter naming convention. */
    private static final String AMBIGUOUS_COUNTER = "lifeplatform_payment_callback_ambiguous_total";

    /** Review fix (C2, second half): an alertable signal for a callback whose {@code status} this
     * platform does not recognize at all. Previously such a status caused a definitive FAILED
     * transition; now it causes NOTHING, which means it needs its own signal or an aggregator that
     * changed its dialect would silently stop being able to confirm anything. */
    private static final String UNKNOWN_STATUS_COUNTER = "lifeplatform_payment_callback_unknown_status_total";

    /**
     * The recognized dialect, spelled out rather than left as "SUCCESS vs everything else".
     *
     * <p>Review fix (C2): {@code isSuccess} used to be {@code "SUCCESS".equalsIgnoreCase(status)}
     * and the caller treated its {@code false} as a terminal FAILURE. So EVERY other value on an
     * unvalidated {@code @NotBlank String} -- a non-terminal {@code PENDING}/{@code PROCESSING}
     * status poll, a vendor-specific code, a typo, a renamed field in an aggregator's next API
     * version -- silently drove a real financial state transition to FAILED, which for a
     * disbursement publishes {@code DisbursementFailed} and makes policyloan write a REVERSAL and
     * release the encumbrance. An unrecognized string from an external system must never be able to
     * do that.
     *
     * <p>Three buckets, deliberately, because two is not enough to be honest here:
     * <ul>
     *   <li>{@link #SUCCESS_STATUSES} -> apply success.</li>
     *   <li>{@link #DECLINE_STATUSES} -> apply failure. A real business rejection; the requesting
     *       module should compensate. (Note this is the CALLBACK dialect, which is why it includes
     *       both the {@code FAILED} the tests and stub mappings use and the {@code REJECTED} the
     *       synchronous {@code /disburse} response uses -- an aggregator that reuses one vocabulary
     *       for both is the common case, and accepting both costs nothing.)</li>
     *   <li>{@link #NON_TERMINAL_STATUSES} -> apply nothing, and do NOT alert. These are recognized,
     *       expected, informational notifications ("still processing"); the row correctly stays where
     *       it is until a terminal notification arrives. Alerting on them would generate noise on
     *       normal traffic, which is how an alert gets muted and then stops working for the case it
     *       was written for.</li>
     * </ul>
     * Anything outside all three: apply nothing, log ERROR, increment {@link #UNKNOWN_STATUS_COUNTER}.
     */
    private static final Set<String> SUCCESS_STATUSES = Set.of("SUCCESS", "SUCCESSFUL", "COMPLETED", "CONFIRMED");
    private static final Set<String> DECLINE_STATUSES = Set.of("FAILED", "FAILURE", "REJECTED", "DECLINED", "CANCELLED");
    private static final Set<String> NON_TERMINAL_STATUSES = Set.of("PENDING", "PROCESSING", "ACCEPTED", "IN_PROGRESS");

    private final PaymentApiImpl paymentApiImpl;
    private final MeterRegistry meterRegistry;

    public MobileMoneyCallbackController(PaymentApiImpl paymentApiImpl, MeterRegistry meterRegistry) {
        this.paymentApiImpl = paymentApiImpl;
        this.meterRegistry = meterRegistry;
    }

    @PostMapping(MobileMoneyHmacFilter.CALLBACK_PATH)
    public ResponseEntity<Void> handleCallback(@Valid @RequestBody MobileMoneyCallbackRequestDto callback) {
        try {
            Boolean succeeded = classify(callback);
            if (succeeded == null) {
                // Not applied at all -- see the dialect constants' javadoc. Still a 200, for the
                // same reason every other outcome here is: an identical retry cannot fix an
                // unrecognized status either, so a non-200 would only provoke a retry storm.
                return ResponseEntity.ok().build();
            }
            // C1: `reference` -- the merchant reference WE generated and the aggregator echoed
            // back -- is now a resolution INPUT, not just a log field. It is the only handle that
            // exists for a row whose gateway_reference is null (transport failure /
            // ACCEPTED-without-reference, i.e. C2's IN_DOUBT rows).
            PaymentApiImpl.GatewayCallbackOutcome outcome = paymentApiImpl.applyGatewayCallback(
                callback.gatewayReference(), callback.reference(), succeeded, callback.reason());
            switch (outcome) {
                case AMBIGUOUS -> {
                    // Review fix (Important 3): deliberately NOT the same log line as NOT_FOUND --
                    // this is the fail-closed safety mechanism working (refusing to guess which
                    // tenant owns a gateway_reference more than one tenant's row shares), and it
                    // needs to read as an operational alert, not a shrug-worthy "unknown reference".
                    log.error("Mobile-money callback gatewayReference={} is AMBIGUOUS across more than "
                        + "one tenant -- refusing to guess, no row was touched. Requires manual "
                        + "reconciliation (own reference={})", callback.gatewayReference(), callback.reference());
                    meterRegistry.counter(AMBIGUOUS_COUNTER).increment();
                }
                case NOT_FOUND -> log.warn("Mobile-money callback referenced an unknown gatewayReference={} "
                    + "(own reference={})", callback.gatewayReference(), callback.reference());
                case APPLIED -> { /* nothing to log -- the normal case */ }
            }
        } catch (Exception e) {
            // Logged, not rethrown -- see class javadoc on why this always acks 200 regardless.
            log.error("Mobile-money callback processing failed for gatewayReference={}",
                callback.gatewayReference(), e);
        }
        return ResponseEntity.ok().build();
    }

    /**
     * @return {@code TRUE} for a recognized success, {@code FALSE} for a recognized decline, and
     *         {@code null} for "apply nothing" -- either a recognized non-terminal notification
     *         (logged quietly) or a status this platform does not recognize at all (logged ERROR
     *         and counted, because it means an aggregator's dialect has drifted away from ours and
     *         confirmations are silently no longer landing). A three-valued return rather than a
     *         boolean is the point: with a boolean there is no way to express "do not touch this
     *         row", which is exactly the expressiveness whose absence caused the bug.
     */
    private Boolean classify(MobileMoneyCallbackRequestDto callback) {
        String status = callback.status();
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (SUCCESS_STATUSES.contains(normalized)) {
            return Boolean.TRUE;
        }
        if (DECLINE_STATUSES.contains(normalized)) {
            return Boolean.FALSE;
        }
        if (NON_TERMINAL_STATUSES.contains(normalized)) {
            log.info("Mobile-money callback reported non-terminal status={} for gatewayReference={} -- "
                + "no state change applied, awaiting a terminal notification", status, callback.gatewayReference());
            return null;
        }
        log.error("Mobile-money callback carried an UNRECOGNIZED status={} (gatewayReference={}, own "
            + "reference={}) -- NO state change was applied. Never let an unknown status string drive a "
            + "financial transition. If the aggregator changed its vocabulary, this platform's "
            + "recognized dialect must be updated deliberately.",
            status, callback.gatewayReference(), callback.reference());
        meterRegistry.counter(UNKNOWN_STATUS_COUNTER).increment();
        return null;
    }
}
