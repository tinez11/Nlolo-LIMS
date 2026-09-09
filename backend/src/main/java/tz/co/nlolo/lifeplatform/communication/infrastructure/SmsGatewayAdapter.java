package tz.co.nlolo.lifeplatform.communication.infrastructure;

import tz.co.nlolo.lifeplatform.communication.api.NotificationChannel;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The one place in the platform that speaks to an SMS aggregator.
 *
 * <p>Speaks NextSMS ({@code messaging-service.co.tz}), the Tanzanian aggregator this platform
 * sends through. Mechanics copied from {@code payment.infrastructure.MobileMoneyGatewayAdapter}:
 * a {@link RestClient} with explicit connect and read timeouts, and an ACL translation where the
 * aggregator's vocabulary stops. The timeouts are not decoration — this runs inside an
 * {@code AFTER_COMMIT} listener, so a gateway that accepts a connection and then says nothing
 * would hold a thread that has already committed a policy.
 *
 * <p><b>Three details of the real contract that an invented one got wrong</b>, all of which would
 * have passed every test and then failed on the first real message:
 *
 * <ul>
 *   <li><b>PENDING is success.</b> NextSMS answers {@code status.groupName = "PENDING"} /
 *       {@code name = "PENDING_ENROUTE"} when it ACCEPTS a message and queues it for delivery.
 *       An adapter looking for a literal "ACCEPTED" rejects every message the gateway takes.</li>
 *   <li><b>No plus sign.</b> The aggregator wants {@code 255700000000}; this platform stores
 *       {@code +255700000000}, because {@code TZ_PHONE_PATTERN} requires the plus. A number sent
 *       with it is silently undelivered, so {@link #toMsisdn} strips it.</li>
 *   <li><b>The sender name is a field, not a header.</b> {@code from} carries the registered
 *       sender ID, and a message sent without it is rejected.</li>
 * </ul>
 *
 * <p><b>Sending is off unless somebody turns it on.</b> {@code communication.sms-gateway.live}
 * defaults false and the base URL defaults to the local mock, so neither a stray credential nor a
 * misconfigured environment can text a real person. When live, an optional allowlist narrows
 * delivery to named numbers — the dev database is full of real-looking numbers on seeded parties,
 * and one live run against it would text strangers. Refusing a non-allowlisted recipient is
 * recorded as a FAILED dispatch, the same as any other undeliverable message.
 *
 * <p>No retry, and unlike the money rail that is not a hard call: a resent SMS is a duplicate on
 * somebody's phone rather than a duplicate payout. But a retry policy still needs a backoff and a
 * give-up rule, and burying them in an adapter would hide them. A FAILED row is visible in the
 * outbox; a silent retry loop is not.
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
    private static final String SEND_PATH = "/api/sms/v1/text/single";

    /** NextSMS group names that mean "we have taken this message". */
    private static final List<String> ACCEPTED_GROUPS = List.of("PENDING", "DELIVERED");

    private final RestClient restClient;
    private final MeterRegistry meterRegistry;
    private final String senderName;
    private final boolean live;
    private final List<String> allowedRecipients;

    public SmsGatewayAdapter(MeterRegistry meterRegistry,
                              @Value("${communication.sms-gateway-url}") String baseUrl,
                              @Value("${communication.connect-timeout-ms}") long connectTimeoutMs,
                              @Value("${communication.read-timeout-ms}") long readTimeoutMs,
                              @Value("${communication.sms-gateway.sender-name:AMC}") String senderName,
                              @Value("${communication.sms-gateway.authorization:}") String authorization,
                              @Value("${communication.sms-gateway.live:false}") boolean live,
                              @Value("${communication.sms-gateway.allowed-recipients:}") String allowedRecipients) {
        this.meterRegistry = meterRegistry;
        this.senderName = senderName;
        this.live = live;
        this.allowedRecipients = allowedRecipients.isBlank()
            ? List.of()
            : List.of(allowedRecipients.split("\\s*,\\s*"));

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) connectTimeoutMs);
        requestFactory.setReadTimeout((int) readTimeoutMs);
        RestClient.Builder builder = RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(requestFactory);
        if (!authorization.isBlank()) {
            builder = builder.defaultHeader(HttpHeaders.AUTHORIZATION, authorization);
        }
        this.restClient = builder.build();

        if (live) {
            log.warn("SMS gateway is LIVE against {} as sender {}{}", baseUrl, senderName,
                this.allowedRecipients.isEmpty()
                    ? " with NO recipient allowlist -- every notification will be delivered for real"
                    : " restricted to " + this.allowedRecipients);
        }
    }

    @Override
    public boolean supports(NotificationChannel channel) {
        return channel == NotificationChannel.SMS;
    }

    @Override
    public SendResult send(String destination, String body) {
        String msisdn = toMsisdn(destination);

        if (!live) {
            // The default. Recorded as a real outcome rather than a silent success, so an outbox
            // row can never claim a customer was contacted when nothing left the building.
            meterRegistry.counter(COUNTER, "status", "SUPPRESSED").increment();
            log.info("SMS suppressed (communication.sms-gateway.live=false) for {}", msisdn);
            return SendResult.failed("SMS sending is switched off (communication.sms-gateway.live=false)");
        }
        if (!allowedRecipients.isEmpty() && !allowedRecipients.contains(msisdn)) {
            // The seeded dev database carries real-looking numbers on real-looking people. This
            // is what stops a live run against it texting strangers.
            meterRegistry.counter(COUNTER, "status", "SUPPRESSED").increment();
            log.warn("SMS to {} suppressed: not in communication.sms-gateway.allowed-recipients", msisdn);
            return SendResult.failed("Recipient " + msisdn + " is not on the SMS testing allowlist");
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                .uri(SEND_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(Map.of(
                    "from", senderName,
                    "to", msisdn,
                    "text", body,
                    // Ours, echoed back on delivery reports. A dispatch id would be better still,
                    // but the sender port does not carry one -- see NotificationSender.
                    "reference", UUID.randomUUID().toString()))
                .retrieve()
                .body(Map.class);

            // ACL translation: the aggregator's own vocabulary ends on these lines.
            String group = firstMessageGroup(response);
            if (group == null) {
                meterRegistry.counter(COUNTER, "status", "FAILED").increment();
                return SendResult.failed("SMS gateway returned no message status");
            }
            if (!ACCEPTED_GROUPS.contains(group)) {
                meterRegistry.counter(COUNTER, "status", "FAILED").increment();
                return SendResult.failed("SMS gateway refused the message: " + group);
            }
            meterRegistry.counter(COUNTER, "status", "SUCCESS").increment();
            return SendResult.ok();
        } catch (Exception e) {
            // Caught deliberately and completely, including a timeout. See NotificationSender's
            // javadoc: the caller has already committed the thing this message is about, and an
            // unreachable aggregator must not be able to unwind it.
            meterRegistry.counter(COUNTER, "status", "FAILED").increment();
            log.warn("SMS gateway did not accept a message for {}", msisdn, e);
            return SendResult.failed("SMS gateway unreachable: " + e.getMessage());
        }
    }

    /**
     * {@code +255700000000} to {@code 255700000000}.
     *
     * <p>Not cosmetic. The platform stores E.164 with the plus ({@code TZ_PHONE_PATTERN} is
     * {@code ^\+255\d{9}$}) and NextSMS wants it without; a number sent with the plus is accepted
     * by the API and then never arrives, which is the worst kind of failure — the outbox would
     * read SENT.
     */
    public static String toMsisdn(String phoneNumber) {
        String trimmed = phoneNumber.trim();
        return trimmed.startsWith("+") ? trimmed.substring(1) : trimmed;
    }

    @SuppressWarnings("unchecked")
    private static String firstMessageGroup(Map<String, Object> response) {
        if (response == null) {
            return null;
        }
        Object messages = response.get("messages");
        if (!(messages instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        if (!(list.get(0) instanceof Map<?, ?> first)) {
            return null;
        }
        Object status = ((Map<String, Object>) first).get("status");
        if (!(status instanceof Map<?, ?> statusMap)) {
            return null;
        }
        Object groupName = ((Map<String, Object>) statusMap).get("groupName");
        return groupName == null ? null : String.valueOf(groupName);
    }
}
