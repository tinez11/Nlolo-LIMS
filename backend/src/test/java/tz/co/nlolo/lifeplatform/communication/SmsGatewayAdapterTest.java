package tz.co.nlolo.lifeplatform.communication;

import tz.co.nlolo.lifeplatform.communication.api.NotificationChannel;
import tz.co.nlolo.lifeplatform.communication.infrastructure.NotificationSender;
import tz.co.nlolo.lifeplatform.communication.infrastructure.SmsGatewayAdapter;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The outbound ACL, against a real HTTP server speaking the real NextSMS contract.
 *
 * <p><b>Never against the live aggregator.</b> Every test here points at a local WireMock; the
 * real gateway is reached only by a deliberately-configured deployment, and even then only for
 * allowlisted numbers. A test suite that could text a customer would be a test suite nobody
 * dares run.
 *
 * <p>No Spring context: this is one adapter and an HTTP call, and booting the application to
 * exercise it would make a fast test slow and hide which layer failed.
 *
 * <p>The failure and suppression cases carry the weight. {@code NotificationSender}'s contract is
 * that a delivery failure is a returned value and never an exception, because every caller sits
 * downstream of an AFTER_COMMIT listener reacting to a policy that already exists.
 */
class SmsGatewayAdapterTest {

    private static final String SEND_PATH = "/api/sms/v1/text/single";
    /** What NextSMS answers when it ACCEPTS a message. PENDING is success, not a refusal. */
    private static final String ACCEPTED = """
        {"messages":[{"to":"255700000000","status":{"groupId":1,"groupName":"PENDING","id":7,
        "name":"PENDING_ENROUTE","description":"Message sent to next instance"},"smsCount":1}]}""";

    private static WireMockServer wireMock;

    @BeforeAll
    static void startGateway() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopGateway() {
        wireMock.stop();
    }

    @BeforeEach
    void reset() {
        wireMock.resetAll();
    }

    /** Live, no allowlist — the shape a production deployment runs. */
    private SmsGatewayAdapter liveAdapter() {
        return new SmsGatewayAdapter(new SimpleMeterRegistry(), wireMock.baseUrl(), 2000, 2000,
            "AMC", "Basic dGVzdDp0ZXN0", true, "");
    }

    @Test
    void handlesOnlySms() {
        assertThat(liveAdapter().supports(NotificationChannel.SMS)).isTrue();
        assertThat(liveAdapter().supports(NotificationChannel.EMAIL)).isFalse();
    }

    @Test
    void sendsTheAggregatorsOwnRequestShape() {
        wireMock.stubFor(post(urlPathEqualTo(SEND_PATH)).willReturn(okJson(ACCEPTED)));

        NotificationSender.SendResult result =
            liveAdapter().send("+255700000000", "Ofa yako ya bima iko tayari.");

        assertThat(result.sent()).isTrue();
        // The body is asserted field by field, not just the call: the aggregator's field names
        // ARE the contract this class owns, and an earlier draft of this adapter invented three
        // of them. `from` is the registered sender ID and a message without it is rejected.
        wireMock.verify(postRequestedFor(urlPathEqualTo(SEND_PATH))
            .withHeader("Authorization", com.github.tomakehurst.wiremock.client.WireMock.equalTo("Basic dGVzdDp0ZXN0"))
            .withRequestBody(matchingJsonPath("$.from", com.github.tomakehurst.wiremock.client.WireMock.equalTo("AMC")))
            .withRequestBody(matchingJsonPath("$.to", com.github.tomakehurst.wiremock.client.WireMock.equalTo("255700000000")))
            .withRequestBody(matchingJsonPath("$.text",
                com.github.tomakehurst.wiremock.client.WireMock.equalTo("Ofa yako ya bima iko tayari.")))
            .withRequestBody(matchingJsonPath("$.reference")));
    }

    /**
     * The plus is stripped, and this is not cosmetic.
     *
     * <p>This platform stores E.164 with a leading plus ({@code TZ_PHONE_PATTERN} requires it);
     * NextSMS wants it without. A number sent with the plus is accepted by the API and then never
     * arrives — the worst kind of failure, because the outbox would read SENT.
     */
    @Test
    void stripsThePlusThatThePlatformStoresAndTheAggregatorRejects() {
        assertThat(SmsGatewayAdapter.toMsisdn("+255700000000")).isEqualTo("255700000000");
        assertThat(SmsGatewayAdapter.toMsisdn("255700000000")).isEqualTo("255700000000");
        assertThat(SmsGatewayAdapter.toMsisdn("  +255700000000  ")).isEqualTo("255700000000");
    }

    /**
     * The regression this whole rewrite exists for.
     *
     * <p>The first version of this adapter was written against an invented mock and looked for a
     * literal {@code "ACCEPTED"}. NextSMS never says that: it says PENDING/PENDING_ENROUTE when it
     * takes a message. That adapter would have passed every test and then refused every message
     * the gateway actually accepted.
     */
    @Test
    void treatsPendingAsAcceptedBecauseThatIsWhatTheAggregatorSaysOnSuccess() {
        wireMock.stubFor(post(urlPathEqualTo(SEND_PATH)).willReturn(okJson(ACCEPTED)));

        assertThat(liveAdapter().send("+255700000000", "Test").sent()).isTrue();
    }

    @Test
    void aRejectedMessageIsAFailedResultRatherThanAnException() {
        wireMock.stubFor(post(urlPathEqualTo(SEND_PATH)).willReturn(okJson("""
            {"messages":[{"to":"255700000000","status":{"groupId":5,"groupName":"REJECTED",
            "id":51,"name":"REJECTED_DESTINATION","description":"Unknown destination"},"smsCount":0}]}""")));

        NotificationSender.SendResult result = liveAdapter().send("+255700000000", "Test");

        assertThat(result.sent()).isFalse();
        assertThat(result.detail())
            .as("an operator reading the outbox needs the aggregator's own word for it")
            .contains("REJECTED");
    }

    @Test
    void aDeadGatewayIsAFailedResultRatherThanAnException() {
        wireMock.stubFor(post(urlPathEqualTo(SEND_PATH)).willReturn(aResponse().withStatus(500)));

        // The assertion that protects the contract: this must not throw, because the policy this
        // message is about has already been committed by the time the adapter is reached.
        assertThatCode(() -> {
            NotificationSender.SendResult result = liveAdapter().send("+255700000000", "Test");
            assertThat(result.sent()).isFalse();
            assertThat(result.detail()).contains("unreachable");
        }).doesNotThrowAnyException();
    }

    @Test
    void anAnswerWithNoMessageStatusIsAFailure() {
        // 200 with a body that says nothing. Treated as failure rather than success: recording
        // SENT here would put a message in the outbox that nobody ever received.
        wireMock.stubFor(post(urlPathEqualTo(SEND_PATH)).willReturn(okJson("{}")));

        assertThat(liveAdapter().send("+255700000000", "Test").sent()).isFalse();
    }

    /**
     * Off by default, and provably so.
     *
     * <p>The single most important test in this class. Sending is a real-world side effect with a
     * cost and a person on the other end, so the default configuration must not be capable of it
     * — and "must not" is worth an assertion rather than a comment.
     */
    @Test
    void sendsNothingAtAllWhenTheGatewayIsNotSwitchedLive() {
        wireMock.stubFor(post(urlPathEqualTo(SEND_PATH)).willReturn(okJson(ACCEPTED)));
        SmsGatewayAdapter notLive = new SmsGatewayAdapter(new SimpleMeterRegistry(), wireMock.baseUrl(),
            2000, 2000, "AMC", "Basic dGVzdDp0ZXN0", false, "");

        NotificationSender.SendResult result = notLive.send("+255700000000", "Test");

        assertThat(result.sent()).isFalse();
        assertThat(result.detail()).contains("switched off");
        wireMock.verify(0, postRequestedFor(urlPathEqualTo(SEND_PATH)));
    }

    /**
     * The allowlist, which exists because the dev database is full of real-looking numbers on
     * seeded parties. One live run against it would text strangers.
     */
    @Test
    void aRecipientOffTheAllowlistIsRefusedRatherThanTexted() {
        wireMock.stubFor(post(urlPathEqualTo(SEND_PATH)).willReturn(okJson(ACCEPTED)));
        SmsGatewayAdapter restricted = new SmsGatewayAdapter(new SimpleMeterRegistry(), wireMock.baseUrl(),
            2000, 2000, "AMC", "Basic dGVzdDp0ZXN0", true, "255700000000");

        NotificationSender.SendResult stranger = restricted.send("+255713000999", "Test");
        assertThat(stranger.sent()).isFalse();
        assertThat(stranger.detail()).contains("allowlist");
        wireMock.verify(0, postRequestedFor(urlPathEqualTo(SEND_PATH)));

        // And the allowlisted number still goes through, or the guard would be indistinguishable
        // from the gateway simply being broken.
        assertThat(restricted.send("+255700000000", "Test").sent()).isTrue();
        wireMock.verify(1, postRequestedFor(urlPathEqualTo(SEND_PATH)));
    }

    @Test
    void anAllowlistIsMatchedAfterNormalisingTheNumber() {
        // The allowlist is configured without a plus, matching what the aggregator wants; the
        // platform passes numbers with one. A guard that compared the raw strings would refuse
        // every legitimate recipient while looking correct.
        wireMock.stubFor(post(urlPathEqualTo(SEND_PATH)).willReturn(okJson(ACCEPTED)));
        SmsGatewayAdapter restricted = new SmsGatewayAdapter(new SimpleMeterRegistry(), wireMock.baseUrl(),
            2000, 2000, "AMC", "Basic dGVzdDp0ZXN0", true, "255700000000");

        assertThat(restricted.send("+255700000000", "Test").sent()).isTrue();
    }

    @Test
    void noAuthorizationHeaderIsSentWhenNoneIsConfigured() {
        // The local mock needs no credential, and sending an empty Authorization header to it
        // would be noise. More to the point: an adapter that always sends the header would make
        // "are we configured?" invisible.
        wireMock.stubFor(post(urlPathEqualTo(SEND_PATH)).willReturn(okJson(ACCEPTED)));
        SmsGatewayAdapter unauthenticated = new SmsGatewayAdapter(new SimpleMeterRegistry(),
            wireMock.baseUrl(), 2000, 2000, "AMC", "", true, "");

        unauthenticated.send("+255700000000", "Test");

        wireMock.verify(postRequestedFor(urlPathEqualTo(SEND_PATH))
            .withoutHeader("Authorization"));
    }
}
