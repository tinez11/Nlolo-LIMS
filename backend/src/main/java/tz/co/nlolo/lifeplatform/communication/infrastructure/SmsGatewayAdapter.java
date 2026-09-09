package tz.co.nlolo.lifeplatform.communication.infrastructure;

import tz.co.nlolo.lifeplatform.communication.api.NotificationChannel;
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
 * The one place in the platform that speaks to an SMS aggregator.
 *
 * <p>Mechanics copied from {@code payment.infrastructure.MobileMoneyGatewayAdapter}: a
 * {@link RestClient} with explicit connect and read timeouts, and an ACL translation where the
 * aggregator's vocabulary stops. The timeouts are not decoration — this runs inside an
 * {@code AFTER_COMMIT} listener, so a gateway that accepts a connection and then says nothing
 * would hold a thread that has already committed a policy.
 *
 * <p><b>No retry, and unlike the money rail that is not a hard call.</b> A resent SMS is a
 * duplicate on somebody's phone rather than a duplicate payout, so the stakes are lower — but a
 * retry policy still needs a backoff and a give-up rule, and inventing them here would bury them
 * in an adapter. A FAILED dispatch row is visible in the outbox; a silent retry loop is not.
 *
 * <p>The counter deliberately does not reuse {@code lifeplatform_payment_gateway_requests_total}.
 * That metric name and its tag values are fixed by a shipped alert expression in
 * observability/alert-rules.yml, and folding a second, unrelated rail into it would make that
 * alert fire for something it does not describe.
 */
@Component
public class SmsGatewayAdapter implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(SmsGatewayAdapter.class);
    private static final String COUNTER = "lifeplatform_communication_sms_requests_total";

    private final RestClient restClient;
    private final MeterRegistry meterRegistry;

    public SmsGatewayAdapter(MeterRegistry meterRegistry,
                              @Value("${communication.sms-gateway-url}") String baseUrl,
                              @Value("${communication.connect-timeout-ms}") long connectTimeoutMs,
                              @Value("${communication.read-timeout-ms}") long readTimeoutMs) {
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
    public boolean supports(NotificationChannel channel) {
        return channel == NotificationChannel.SMS;
    }

    @Override
    public SendResult send(String destination, String body) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                .uri("/send")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("to", destination, "message", body))
                .retrieve()
                .body(Map.class);

            // ACL translation: the aggregator's own vocabulary ends on this line.
            if (response == null || !"ACCEPTED".equals(response.get("status"))) {
                String reason = response == null ? "empty response body" : String.valueOf(response.get("reason"));
                meterRegistry.counter(COUNTER, "status", "FAILED").increment();
                return SendResult.failed("SMS gateway refused the message: " + reason);
            }
            meterRegistry.counter(COUNTER, "status", "SUCCESS").increment();
            return SendResult.ok();
        } catch (Exception e) {
            // Caught deliberately and completely, including a timeout. See NotificationSender's
            // javadoc: the caller has already committed the thing this message is about, and an
            // unreachable aggregator must not be able to unwind it.
            meterRegistry.counter(COUNTER, "status", "FAILED").increment();
            log.warn("SMS gateway did not accept a message for {}", destination, e);
            return SendResult.failed("SMS gateway unreachable: " + e.getMessage());
        }
    }
}
