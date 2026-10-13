package tz.co.nlolo.lifeplatform.communication.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.communication.api.NotificationChannel;
import tz.co.nlolo.lifeplatform.communication.domain.NotificationDispatch;
import tz.co.nlolo.lifeplatform.communication.infrastructure.NotificationDispatchRepository;
import tz.co.nlolo.lifeplatform.communication.infrastructure.NotificationSender;
import tz.co.nlolo.lifeplatform.communication.infrastructure.NotificationTemplateRepository;
import tz.co.nlolo.lifeplatform.communication.infrastructure.ProcessedEventRepository;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** A message carrying a secret is sent as written and logged as masked (2026-10-13, deployment pilot §6). */
class SendUnloggedSmsTest {

    static class Recording implements NotificationSender {
        final List<String> sent = new ArrayList<>();
        boolean accept = true;
        @Override public boolean supports(NotificationChannel channel) { return channel == NotificationChannel.SMS; }
        @Override public SendResult send(String destination, String body) {
            sent.add(destination + "|" + body);
            return accept ? SendResult.ok()
                : SendResult.failed("Recipient 255700000001 is not on the SMS testing allowlist");
        }
    }

    private final NotificationDispatchRepository dispatches = mock(NotificationDispatchRepository.class);
    private final Recording sms = new Recording();
    private final NotificationApiImpl api = new NotificationApiImpl(mock(PartyApi.class),
        mock(NotificationTemplateRepository.class), dispatches, mock(ProcessedEventRepository.class), List.of(sms));
    private final UUID party = UUID.randomUUID();

    @BeforeEach void tenant() { TenantContext.set(UUID.randomUUID()); }
    @AfterEach void clear() { TenantContext.clear(); }

    private NotificationDispatch saved() {
        ArgumentCaptor<NotificationDispatch> row = ArgumentCaptor.forClass(NotificationDispatch.class);
        verify(dispatches).save(row.capture());
        return row.getValue();
    }

    @Test
    void theGatewayGetsTheSecretAndTheLogGetsTheMask() {
        String failure = api.sendUnloggedSms(party, "+255700000001", "PORTAL_INVITE",
            "one-time password K7QMx4PAb9cd", "one-time password ••••••••");

        assertThat(failure).isNull();
        assertThat(sms.sent).containsExactly("+255700000001|one-time password K7QMx4PAb9cd");
        NotificationDispatch row = saved();
        assertThat(row.getBody()).isEqualTo("one-time password ••••••••").doesNotContain("K7QMx4PAb9cd");
        assertThat(row.getStatus()).isEqualTo("SENT");
        assertThat(row.getTemplateKey()).isEqualTo("PORTAL_INVITE");
        assertThat(row.getChannel()).isEqualTo("SMS");
        assertThat(row.getEventId()).isNotNull();
    }

    @Test
    void aRefusalIsReturnedAndRecorded() {
        sms.accept = false;

        String failure = api.sendUnloggedSms(party, "+255700000001", "PORTAL_INVITE", "secret", "masked");

        assertThat(failure).isEqualTo("Recipient 255700000001 is not on the SMS testing allowlist");
        NotificationDispatch row = saved();
        assertThat(row.getStatus()).isEqualTo("FAILED");
        assertThat(row.getBody()).isEqualTo("masked");
    }
}
