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
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The outbound ACL, against a real HTTP server rather than a mocked client.
 *
 * <p>No Spring context: this class is one adapter and an HTTP call, and booting the application
 * to exercise it would make a fast test slow and hide which layer failed.
 *
 * <p>The failure cases carry the weight. {@code NotificationSender}'s contract is that a delivery
 * failure is a returned value and never an exception, because every caller sits downstream of an
 * AFTER_COMMIT listener reacting to a policy that already exists — an unreachable aggregator must
 * leave a row somebody can act on, not unwind a committed transaction. A test that only proved
 * the happy path would let a future refactor turn that contract inside out unnoticed.
 */
class SmsGatewayAdapterTest {

    private static WireMockServer wireMock;
    private SmsGatewayAdapter adapter;

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
    void resetAndBuildAdapter() {
        wireMock.resetAll();
        adapter = new SmsGatewayAdapter(new SimpleMeterRegistry(), wireMock.baseUrl(), 2000, 2000);
    }

    @Test
    void handlesOnlySms() {
        assertThat(adapter.supports(NotificationChannel.SMS)).isTrue();
        assertThat(adapter.supports(NotificationChannel.EMAIL)).isFalse();
    }

    @Test
    void sendsTheMessageToTheAggregatorAndReportsSuccess() {
        wireMock.stubFor(post(urlPathEqualTo("/send"))
            .willReturn(okJson("{\"status\":\"ACCEPTED\",\"messageId\":\"SMS-0001\"}")));

        NotificationSender.SendResult result = adapter.send("+255713000001", "Your cover offer is ready.");

        assertThat(result.sent()).isTrue();
        assertThat(result.detail()).isNull();
        // The body is asserted, not just the call: an adapter that posted an empty document would
        // otherwise pass, and the aggregator's field names are the contract this class owns.
        wireMock.verify(postRequestedFor(urlPathEqualTo("/send"))
            .withRequestBody(equalToJson(
                "{\"to\":\"+255713000001\",\"message\":\"Your cover offer is ready.\"}")));
    }

    @Test
    void aRejectedMessageIsAFailedResultRatherThanAnException() {
        wireMock.stubFor(post(urlPathEqualTo("/send"))
            .willReturn(okJson("{\"status\":\"REJECTED\",\"reason\":\"unknown MSISDN\"}")));

        NotificationSender.SendResult result = adapter.send("+255713000002", "Your cover offer is ready.");

        assertThat(result.sent()).isFalse();
        assertThat(result.detail())
            .as("an operator reading the outbox needs the aggregator's own reason, not a generic failure")
            .contains("unknown MSISDN");
    }

    @Test
    void aDeadGatewayIsAFailedResultRatherThanAnException() {
        wireMock.stubFor(post(urlPathEqualTo("/send")).willReturn(aResponse().withStatus(500)));

        // The assertion that protects the contract: this must not throw, because the policy this
        // message is about has already been committed by the time the adapter is reached.
        assertThatCode(() -> {
            NotificationSender.SendResult result = adapter.send("+255713000003", "Your cover offer is ready.");
            assertThat(result.sent()).isFalse();
            assertThat(result.detail()).contains("unreachable");
        }).doesNotThrowAnyException();
    }

    @Test
    void anUnparseableSuccessIsStillAFailure() {
        // 200 with a body that says nothing. Treated as a failure rather than a success, because
        // "the aggregator answered but did not accept" is not delivery, and recording SENT here
        // would put a message in the outbox that nobody ever received.
        wireMock.stubFor(post(urlPathEqualTo("/send")).willReturn(okJson("{}")));

        NotificationSender.SendResult result = adapter.send("+255713000004", "Your cover offer is ready.");

        assertThat(result.sent()).isFalse();
    }
}
