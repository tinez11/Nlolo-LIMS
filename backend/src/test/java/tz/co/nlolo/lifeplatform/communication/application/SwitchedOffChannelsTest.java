package tz.co.nlolo.lifeplatform.communication.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.communication.api.NotificationChannel;
import tz.co.nlolo.lifeplatform.communication.domain.NotificationDispatch;
import tz.co.nlolo.lifeplatform.communication.domain.NotificationTemplate;
import tz.co.nlolo.lifeplatform.communication.infrastructure.NotificationDispatchRepository;
import tz.co.nlolo.lifeplatform.communication.infrastructure.NotificationSender;
import tz.co.nlolo.lifeplatform.communication.infrastructure.NotificationTemplateRepository;
import tz.co.nlolo.lifeplatform.communication.infrastructure.ProcessedEventRepository;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyDetailView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-11-08, the user: in production without email, would messages still go to Mailpit? No -- and a channel that is
 * switched off is a setting, not a fault: its messages are NOT_SENT, and while email is off no email row is written.
 */
class SwitchedOffChannelsTest {

    /** A channel as configured: answers in its mode, and records what it was asked to send. */
    static final class Channel implements NotificationSender {
        final NotificationChannel channel;
        final Mode mode;
        final List<String> asked = new ArrayList<>();

        Channel(NotificationChannel channel, Mode mode) {
            this.channel = channel;
            this.mode = mode;
        }

        @Override public boolean supports(NotificationChannel c) { return c == channel; }
        @Override public Mode mode() { return mode; }
        @Override public SendResult send(String destination, String body) {
            asked.add(destination);
            return mode == Mode.OFF ? SendResult.notSent(channel + " is switched off") : SendResult.ok();
        }
    }

    private final PartyApi parties = mock(PartyApi.class);
    private final NotificationTemplateRepository templates = mock(NotificationTemplateRepository.class);
    private final NotificationDispatchRepository dispatches = mock(NotificationDispatchRepository.class);
    private final UUID tenant = UUID.randomUUID();
    private final UUID party = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.set(tenant);
        when(templates.findByTenantIdAndTemplateKeyAndChannelAndLanguage(any(), eq("COVER_STARTED"), anyString(), anyString()))
            .thenAnswer(i -> Optional.of(new NotificationTemplate(tenant, "COVER_STARTED", i.getArgument(2), "sw",
                "Bima {{policyNumber}} imeanza.")));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private NotificationApiImpl api(Channel sms, Channel email) {
        return new NotificationApiImpl(parties, templates, dispatches, mock(ProcessedEventRepository.class), List.of(sms, email));
    }

    private void partyWith(String phone, String email) {
        PartyDetailView view = mock(PartyDetailView.class);
        when(view.phoneNumber()).thenReturn(phone);
        when(view.email()).thenReturn(email);
        when(parties.getPartyDetail(party)).thenReturn(view);
    }

    private List<NotificationDispatch> saved() {
        ArgumentCaptor<NotificationDispatch> rows = ArgumentCaptor.forClass(NotificationDispatch.class);
        verify(dispatches, atLeastOnce()).save(rows.capture());
        return rows.getAllValues();
    }

    @Test
    void whileEmailIsOffNoEmailRowIsWrittenAndASwitchedOffSmsIsNotSentRatherThanFailed() {
        Channel sms = new Channel(NotificationChannel.SMS, NotificationSender.Mode.OFF);
        Channel email = new Channel(NotificationChannel.EMAIL, NotificationSender.Mode.OFF);
        partyWith("+255700000001", "client@example.tz");

        api(sms, email).notify(UUID.randomUUID(), party, "POL-1", "COVER_STARTED", Map.of("policyNumber", "POL-1"));

        assertThat(email.asked).as("email is not even tried").isEmpty();
        assertThat(saved()).singleElement().satisfies(row -> {
            assertThat(row.getChannel()).isEqualTo("SMS");
            assertThat(row.getStatus()).isEqualTo("NOT_SENT");
            assertThat(row.getBody()).as("the inbox still has the text").isEqualTo("Bima POL-1 imeanza.");
        });
    }

    @Test
    void aCustomerWithOnlyAnEmailWhileEmailIsOffIsNotSentSayingWhy() {
        partyWith(null, "client@example.tz");

        api(new Channel(NotificationChannel.SMS, NotificationSender.Mode.LIVE),
            new Channel(NotificationChannel.EMAIL, NotificationSender.Mode.OFF))
            .notify(UUID.randomUUID(), party, "POL-1", "COVER_STARTED", Map.of("policyNumber", "POL-1"));

        assertThat(saved()).singleElement().satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo("NOT_SENT");
            assertThat(row.getFailureReason()).isEqualTo("No phone number on file, and email is not in use");
        });
    }

    @Test
    void eachChannelSaysHowItIsSetUp() {
        var status = api(new Channel(NotificationChannel.SMS, NotificationSender.Mode.LIMITED),
            new Channel(NotificationChannel.EMAIL, NotificationSender.Mode.TEST)).channels();
        assertThat(status).extracting(s -> s.channel() + "=" + s.mode()).containsExactly("SMS=LIMITED", "EMAIL=TEST");
    }
}
