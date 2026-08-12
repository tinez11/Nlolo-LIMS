package tz.co.nlolo.lifeplatform.payment.infrastructure;

import tz.co.nlolo.lifeplatform.payment.domain.GatewayException;
import tz.co.nlolo.lifeplatform.payment.domain.PaymentGatewayPort;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * The one place in the platform that speaks to an external money rail. Emits the Micrometer
 * counter observability/alert-rules.yml:52-57 already alerts on:
 * lifeplatform_payment_gateway_requests_total{status="SUCCESS"|"FAILED"} -- the name and the
 * FAILED tag value are fixed by that shipped alert expression, not free choices.
 *
 * <p>No retry here, deliberately. A retried disbursement that the rail actually accepted the
 * first time is a double payout, and this adapter cannot distinguish "never arrived" from
 * "arrived, response lost". Recovery is the caller's PENDING row plus the idempotency registry,
 * which makes a *redelivered event* safe -- that is a different and safe kind of retry.
 */
@Component
public class MobileMoneyGatewayAdapter implements PaymentGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(MobileMoneyGatewayAdapter.class);
    private static final String COUNTER = "lifeplatform_payment_gateway_requests_total";

    private final RestClient restClient;
    private final MeterRegistry meterRegistry;

    public MobileMoneyGatewayAdapter(MeterRegistry meterRegistry,
                                      @Value("${mobile-money.base-url}") String baseUrl,
                                      @Value("${mobile-money.connect-timeout-ms}") long connectTimeoutMs,
                                      @Value("${mobile-money.read-timeout-ms}") long readTimeoutMs) {
        this.meterRegistry = meterRegistry;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) connectTimeoutMs);
        requestFactory.setReadTimeout((int) readTimeoutMs);
        this.restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(requestFactory)
            .build();
    }

    @Override
    public GatewayResult submitDisbursement(GatewayDisbursementRequest request) {
        return call("/disburse", Map.of(
            "payeeRef", request.payeeRef(),
            "amount", request.amount().toPlainString(),
            "currency", request.currency(),
            "reference", request.reference()));
    }

    @Override
    public GatewayResult submitCollection(GatewayCollectionRequest request) {
        return call("/collect", Map.of(
            "payerRef", request.payerRef(),
            "amount", request.amount().toPlainString(),
            "currency", request.currency(),
            "reference", request.reference()));
    }

    private GatewayResult call(String path, Map<String, String> body) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
            if (response == null) {
                meterRegistry.counter(COUNTER, "status", "FAILED").increment();
                throw new GatewayException("Mobile money gateway returned an empty body for " + path);
            }
            // ACL translation: the rail's own vocabulary ends here.
            boolean accepted = "ACCEPTED".equals(response.get("status"));
            String gatewayReference = (String) response.get("gatewayReference");
            String failureReason = (String) response.get("reason");
            if (accepted && gatewayReference == null) {
                // An ACCEPTED with nothing to reconcile against is untrustworthy, not a success --
                // the caller's completeDisbursement/confirmCollection persists gatewayReference as
                // the durable proof the rail took the money, so a null here can never be returned
                // as a genuine accept. Treated as INDETERMINATE (same family as a 5xx or an empty
                // body above), never as GatewayResult(accepted=true, null, ...).
                //
                // Review fix (C2): this is the case that most obviously must not be recorded as
                // definitive FAILED -- the rail has just SAID it accepted the payout. Raising
                // GatewayException routes it through PaymentRequestListener's
                // catch(GatewayException), which now records IN_DOUBT and publishes no *Failed
                // event, instead of triggering policyloan's REVERSAL + encumbrance release for a
                // payout the rail claims it took. Note the counter tag below stays "FAILED": its
                // name and tag values are fixed by the already-shipped alert expression in
                // observability/alert-rules.yml:52-57, and "this gateway REQUEST did not produce a
                // usable result" is still true and still exactly what that error-rate alert is
                // about. The IN_DOUBT distinction lives on the ledger row and on its own separate
                // counter (lifeplatform_payment_in_doubt_total), not by retagging this one.
                meterRegistry.counter(COUNTER, "status", "FAILED").increment();
                throw new GatewayException("Mobile money gateway returned ACCEPTED with no gatewayReference for " + path);
            }
            meterRegistry.counter(COUNTER, "status", accepted ? "SUCCESS" : "FAILED").increment();
            return new GatewayResult(accepted, gatewayReference, accepted ? null : failureReason);
        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            meterRegistry.counter(COUNTER, "status", "FAILED").increment();
            log.error("Mobile money gateway call to {} failed at transport level", path, e);
            throw new GatewayException("Mobile money gateway call to " + path + " failed", e);
        }
    }
}
