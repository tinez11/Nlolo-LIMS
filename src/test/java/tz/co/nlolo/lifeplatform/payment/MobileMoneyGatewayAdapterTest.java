package tz.co.nlolo.lifeplatform.payment;

import tz.co.nlolo.lifeplatform.payment.domain.GatewayException;
import tz.co.nlolo.lifeplatform.payment.domain.PaymentGatewayPort;
import tz.co.nlolo.lifeplatform.payment.infrastructure.MobileMoneyGatewayAdapter;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Roadmap acceptance criterion 1 ("Integration tests against the mock-mobile-money WireMock
 * instance from Deliverable 7"), run in-process rather than against the compose service — see
 * pom.xml's dependency comment for why.
 */
class MobileMoneyGatewayAdapterTest {

    static WireMockServer wireMock;
    static MobileMoneyGatewayAdapter adapter;
    static SimpleMeterRegistry meterRegistry;

    @BeforeAll
    static void startGateway() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        meterRegistry = new SimpleMeterRegistry();
        adapter = new MobileMoneyGatewayAdapter(meterRegistry, wireMock.baseUrl(), 2000, 5000);
    }

    @AfterAll
    static void stopGateway() { wireMock.stop(); }

    @Test
    void acceptedDisbursementReturnsTheGatewayReferenceAndCountsASuccess() {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-ABC123\"}")));

        double before = meterRegistry.counter("lifeplatform_payment_gateway_requests_total", "status", "SUCCESS").count();
        PaymentGatewayPort.GatewayResult result = adapter.submitDisbursement(
            new PaymentGatewayPort.GatewayDisbursementRequest("MPESA-0712345678", new BigDecimal("50000.00"), "TZS", "ref-1"));

        assertThat(result.accepted()).isTrue();
        assertThat(result.gatewayReference()).isEqualTo("MM-ABC123");
        assertThat(result.failureReason()).isNull();
        // Falsifiable: the counter must have actually moved, and by exactly one.
        assertThat(meterRegistry.counter("lifeplatform_payment_gateway_requests_total", "status", "SUCCESS").count())
            .isEqualTo(before + 1);
    }

    @Test
    void rejectedDisbursementIsTranslatedToAFailureResultNotAnException() {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"REJECTED\",\"reason\":\"INSUFFICIENT_FLOAT\"}")));

        double before = meterRegistry.counter("lifeplatform_payment_gateway_requests_total", "status", "FAILED").count();
        PaymentGatewayPort.GatewayResult result = adapter.submitDisbursement(
            new PaymentGatewayPort.GatewayDisbursementRequest("MPESA-0000000000", new BigDecimal("50000.00"), "TZS", "ref-2"));

        // A rail declining is a normal domain outcome, NOT a transport failure.
        assertThat(result.accepted()).isFalse();
        assertThat(result.failureReason()).isEqualTo("INSUFFICIENT_FLOAT");
        assertThat(meterRegistry.counter("lifeplatform_payment_gateway_requests_total", "status", "FAILED").count())
            .isEqualTo(before + 1);
    }

    @Test
    void aGateway500IsATransportFailureAndCountsAsFailed() {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(serverError()));

        double before = meterRegistry.counter("lifeplatform_payment_gateway_requests_total", "status", "FAILED").count();
        assertThatThrownBy(() -> adapter.submitDisbursement(
                new PaymentGatewayPort.GatewayDisbursementRequest("MPESA-0712345678", new BigDecimal("50000.00"), "TZS", "ref-3")))
            .isInstanceOf(GatewayException.class);
        assertThat(meterRegistry.counter("lifeplatform_payment_gateway_requests_total", "status", "FAILED").count())
            .isEqualTo(before + 1);
    }

    @Test
    void aCollectionUsesTheCollectPathAndSendsThePayerRef() {
        wireMock.stubFor(post(urlPathEqualTo("/collect")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-COLLECT-9\"}")));

        PaymentGatewayPort.GatewayResult result = adapter.submitCollection(
            new PaymentGatewayPort.GatewayCollectionRequest("MPESA-0755555555", new BigDecimal("15000.00"), "TZS", "ref-4"));

        assertThat(result.accepted()).isTrue();
        assertThat(result.gatewayReference()).isEqualTo("MM-COLLECT-9");
        // Proves the adapter actually hit /collect with the payer field, not /disburse.
        wireMock.verify(postRequestedFor(urlPathEqualTo("/collect"))
            .withRequestBody(matchingJsonPath("$[?(@.payerRef == 'MPESA-0755555555')]")));
    }
}
