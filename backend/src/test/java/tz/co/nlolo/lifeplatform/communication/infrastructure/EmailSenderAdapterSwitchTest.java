package tz.co.nlolo.lifeplatform.communication.infrastructure;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Email off (the pilot, before a provider exists) is a recorded outcome, not an attempt (2026-10-13). */
class EmailSenderAdapterSwitchTest {

    @Test
    void switchedOffItTriesNothingAndSaysWhy() {
        JavaMailSender mail = mock(JavaMailSender.class);
        var adapter = new EmailSenderAdapter(mail, new SimpleMeterRegistry(), "no-reply@example.tz", false,
            "smtp.example.tz");

        var result = adapter.send("client@example.tz", "Your cover has started.");

        assertThat(result.sent()).isFalse();
        // Not in use is a setting, not a fault (2026-11-08): NOT_SENT, and the channel says it is OFF.
        assertThat(result.notSent()).isTrue();
        assertThat(result.detail()).isEqualTo("Email is not in use");
        assertThat(adapter.mode()).isEqualTo(NotificationSender.Mode.OFF);
        verify(mail, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void switchedOnItSends() {
        JavaMailSender mail = mock(JavaMailSender.class);
        var adapter = new EmailSenderAdapter(mail, new SimpleMeterRegistry(), "no-reply@example.tz", true,
            "smtp.example.tz");

        assertThat(adapter.send("client@example.tz", "Your cover has started.").sent()).isTrue();
        assertThat(adapter.mode()).isEqualTo(NotificationSender.Mode.LIVE);
        verify(mail).send(any(SimpleMailMessage.class));
    }
}
