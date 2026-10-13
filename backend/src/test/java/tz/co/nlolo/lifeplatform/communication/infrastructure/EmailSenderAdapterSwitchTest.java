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
        var adapter = new EmailSenderAdapter(mail, new SimpleMeterRegistry(), "no-reply@example.tz", false);

        var result = adapter.send("client@example.tz", "Your cover has started.");

        assertThat(result.sent()).isFalse();
        assertThat(result.detail()).isEqualTo("Email is switched off (communication.email.enabled=false)");
        verify(mail, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void switchedOnItSends() {
        JavaMailSender mail = mock(JavaMailSender.class);
        var adapter = new EmailSenderAdapter(mail, new SimpleMeterRegistry(), "no-reply@example.tz", true);

        assertThat(adapter.send("client@example.tz", "Your cover has started.").sent()).isTrue();
        verify(mail).send(any(SimpleMailMessage.class));
    }
}
