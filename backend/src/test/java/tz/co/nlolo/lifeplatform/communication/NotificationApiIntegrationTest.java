package tz.co.nlolo.lifeplatform.communication;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.communication.api.NotificationApi;
import tz.co.nlolo.lifeplatform.communication.domain.NotificationDispatch;
import tz.co.nlolo.lifeplatform.communication.domain.NotificationTemplate;
import tz.co.nlolo.lifeplatform.communication.infrastructure.NotificationDispatchRepository;
import tz.co.nlolo.lifeplatform.communication.infrastructure.NotificationTemplateRepository;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static tz.co.nlolo.lifeplatform.communication.NextSmsStubs.NEXTSMS_ACCEPTED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The send path end to end against real Postgres and a real HTTP aggregator: resolve a party's
 * channels, look up and render the seeded template, send, and record what happened.
 *
 * <p>Email is not asserted to leave the process. There is no SMTP server in this test and
 * {@code EmailSenderAdapter} correctly records a FAILED dispatch when it cannot connect — which
 * is itself worth pinning, because it is the same code path a real outage takes. What matters
 * here is that the EMAIL channel is ATTEMPTED and recorded whenever an address is on file;
 * Mailpit proves actual delivery in the e2e.
 */
@Testcontainers
// Live against the LOCAL WireMock below, never the real aggregator: sending defaults off,
// so without this the adapter would refuse and every SENT assertion here would fail.
@SpringBootTest(classes = Application.class, properties = "communication.sms-gateway.live=true")
class NotificationApiIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static WireMockServer smsGateway;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("communication.sms-gateway-url", () -> smsGateway.baseUrl());
    }

    @BeforeAll
    static void startEverything() throws Exception {
        smsGateway = new WireMockServer(options().dynamicPort());
        smsGateway.start();
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/party/V2__individual_person_record.sql",
            "db-migrations/communication/V1__create_communication_schema.sql",
            "db-migrations/communication/V2__template_identity.sql",
            "db-migrations/communication/V3__seed_offer_templates.sql",
            "db-migrations/communication/V4__dispatch_reason_and_policy.sql",
            "db-migrations/audit/V1__create_audit_schema.sql");
    }

    @AfterAll
    static void stopGateway() {
        smsGateway.stop();
    }

    /**
     * The tenant communication/V3 seeds its templates for. Hard-coded in the migration rather
     * than minted per test, so this must match it -- a random tenant here would find no template
     * and every test would pass through the "nothing seeded" branch instead of the real one.
     */
    private static final UUID SEEDED_TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired private NotificationApi notificationApi;
    @Autowired private PartyApi partyApi;
    @Autowired private NotificationDispatchRepository dispatchRepository;
    @Autowired private NotificationTemplateRepository templateRepository;

    @BeforeEach
    void acceptEverySms() {
        smsGateway.resetAll();
        smsGateway.stubFor(post(urlPathEqualTo("/api/sms/v1/text/single"))
            .willReturn(okJson(NEXTSMS_ACCEPTED)));
        TenantContext.set(SEEDED_TENANT);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private UUID registerParty(String tag, String phone, String email) {
        TenantContext.set(SEEDED_TENANT);
        PartyView party = partyApi.registerIndividual("Notify Test " + tag, LocalDate.of(1990, 1, 1),
            phone, email, "test-staff");
        return party.partyId();
    }

    private Map<String, String> offerValues() {
        return Map.of("policyNumber", "POL-NOTIFY01", "premium", "TZS 50,000.00", "expiryDate", "2026-10-09");
    }

    @Test
    void sendsAnSmsAndRecordsTheDispatch() {
        UUID partyId = registerParty("SMS", "+255713000101", null);

        notificationApi.notify(UUID.randomUUID(), partyId, "POL-NOTIFY01", "OFFER_MADE", offerValues());

        List<NotificationDispatch> rows = dispatchRepository
            .findByTenantIdAndPartyIdOrderByCreatedAtDesc(SEEDED_TENANT, partyId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getChannel()).isEqualTo("SMS");
        assertThat(rows.get(0).getStatus()).isEqualTo("SENT");
        assertThat(rows.get(0).getTemplateKey()).isEqualTo("OFFER_MADE");
        assertThat(rows.get(0).getPolicyNumber()).isEqualTo("POL-NOTIFY01");
        assertThat(rows.get(0).getFailureReason()).isNull();
    }

    @Test
    void aPartyWithBothAPhoneAndAnEmailGetsBoth() {
        // Email is additional, not alternative. An address is a second chance at telling somebody
        // their cover has not started, not a reason to skip the channel they actually read.
        UUID partyId = registerParty("BOTH", "+255713000102", "both@example.test");

        notificationApi.notify(UUID.randomUUID(), partyId, "POL-NOTIFY01", "OFFER_MADE", offerValues());

        assertThat(dispatchRepository.findByTenantIdAndPartyIdOrderByCreatedAtDesc(SEEDED_TENANT, partyId))
            .extracting(NotificationDispatch::getChannel)
            .containsExactlyInAnyOrder("SMS", "EMAIL");
    }

    /**
     * The reason {@code processed_event} exists, and the one behaviour that separates this module
     * from every other consumer on the platform. Elsewhere a redelivered event is absorbed
     * silently because the write is idempotent; here a second SMS is a second SMS on somebody's
     * phone.
     */
    @Test
    void aRedeliveredEventSendsNothingASecondTime() {
        UUID partyId = registerParty("DEDUP", "+255713000103", null);
        UUID eventId = UUID.randomUUID();

        notificationApi.notify(eventId, partyId, "POL-NOTIFY01", "OFFER_MADE", offerValues());
        notificationApi.notify(eventId, partyId, "POL-NOTIFY01", "OFFER_MADE", offerValues());

        assertThat(dispatchRepository.findByTenantIdAndPartyIdOrderByCreatedAtDesc(SEEDED_TENANT, partyId))
            .hasSize(1);
        // Belt and braces on the thing that actually reaches a customer: one HTTP call, not two.
        smsGateway.verify(1, com.github.tomakehurst.wiremock.client.WireMock
            .postRequestedFor(urlPathEqualTo("/api/sms/v1/text/single")));
    }

    @Test
    void aDifferentEventForTheSamePartySendsAgain() {
        // The negative half of dedup: the key is the EVENT, not the party or the message. An
        // offer made and later expiring are two things the customer must hear about.
        UUID partyId = registerParty("TWICE", "+255713000104", null);

        notificationApi.notify(UUID.randomUUID(), partyId, "POL-NOTIFY01", "OFFER_MADE", offerValues());
        notificationApi.notify(UUID.randomUUID(), partyId, "POL-NOTIFY01", "OFFER_EXPIRED",
            Map.of("policyNumber", "POL-NOTIFY01"));

        assertThat(dispatchRepository.findByTenantIdAndPartyIdOrderByCreatedAtDesc(SEEDED_TENANT, partyId))
            .hasSize(2);
    }

    /**
     * Both contact fields are optional on a party ({@code ContactInfo} constrains their formats
     * but requires neither), so "nobody to tell" is a real state rather than bad data. It is
     * recorded, because an unreachable customer is something the desk has to chase and silence
     * would hide it completely.
     */
    @Test
    void aPartyWithNoPhoneAndNoEmailGetsAFailedDispatchNamingWhy() {
        UUID partyId = registerParty("UNREACHABLE", null, null);

        notificationApi.notify(UUID.randomUUID(), partyId, "POL-NOTIFY01", "OFFER_MADE", offerValues());

        List<NotificationDispatch> rows = dispatchRepository
            .findByTenantIdAndPartyIdOrderByCreatedAtDesc(SEEDED_TENANT, partyId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo("FAILED");
        assertThat(rows.get(0).getFailureReason()).contains("No phone number or email address");
    }

    @Test
    void aMissingPlaceholderIsAFailedDispatchAndNotAHalfRenderedMessage() {
        UUID partyId = registerParty("HOLE", "+255713000105", null);

        // OFFER_MADE declares premium and expiryDate; this supplies neither.
        notificationApi.notify(UUID.randomUUID(), partyId, "POL-NOTIFY01", "OFFER_MADE",
            Map.of("policyNumber", "POL-NOTIFY01"));

        List<NotificationDispatch> rows = dispatchRepository
            .findByTenantIdAndPartyIdOrderByCreatedAtDesc(SEEDED_TENANT, partyId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo("FAILED");
        assertThat(rows.get(0).getFailureReason()).contains("premium").contains("expiryDate");
        // Nothing left the platform. A message with a visible {{hole}} is worse than none.
        smsGateway.verify(0, com.github.tomakehurst.wiremock.client.WireMock
            .postRequestedFor(urlPathEqualTo("/api/sms/v1/text/single")));
    }

    @Test
    void anUnseededTemplateIsAFailedDispatchNamingTheLookupThatMissed() {
        UUID partyId = registerParty("NOTEMPLATE", "+255713000106", null);

        notificationApi.notify(UUID.randomUUID(), partyId, "POL-NOTIFY01", "NO_SUCH_TEMPLATE", Map.of());

        List<NotificationDispatch> rows = dispatchRepository
            .findByTenantIdAndPartyIdOrderByCreatedAtDesc(SEEDED_TENANT, partyId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo("FAILED");
        assertThat(rows.get(0).getFailureReason()).contains("NO_SUCH_TEMPLATE");
    }

    @Test
    void theSeededTemplatesAreAddressableByChannelAndLanguage() {
        // The regression guard for communication/V2. Before it, template_key was the sole primary
        // key, so exactly ONE of these sixteen rows could have existed -- and the failure would
        // have been an insert error at deploy time, not anything a send-path test would notice.
        for (String key : List.of("OFFER_MADE", "OFFER_CLOSING", "COVER_STARTED", "OFFER_EXPIRED")) {
            for (String channel : List.of("SMS", "EMAIL")) {
                for (String language : List.of("sw", "en")) {
                    assertThat(templateRepository.findByTenantIdAndTemplateKeyAndChannelAndLanguage(
                        SEEDED_TENANT, key, channel, language))
                        .as("%s/%s/%s must be seeded", key, channel, language)
                        .isPresent();
                }
            }
        }
        assertThat(templateRepository.findByTenantIdOrderByTemplateKeyAscChannelAscLanguageAsc(SEEDED_TENANT))
            .extracting(NotificationTemplate::getTemplateKey)
            .hasSize(16);
    }
}
