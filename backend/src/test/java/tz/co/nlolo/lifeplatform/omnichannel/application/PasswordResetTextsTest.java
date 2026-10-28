package tz.co.nlolo.lifeplatform.omnichannel.application;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.communication.api.NotificationApi;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyDetailView;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The platform's half of reset-by-text (2026-10-28): who is texted, what the log keeps, and how often. */
class PasswordResetTextsTest {

    private final PartyApi parties = mock(PartyApi.class);
    private final NotificationApi notifications = mock(NotificationApi.class);
    private final UUID party = UUID.randomUUID();
    private final PasswordResetTexts texts = new PasswordResetTexts(parties, notifications,
        Clock.fixed(Instant.parse("2026-10-28T08:00:00Z"), ZoneOffset.UTC));

    private void phoneOnFile(String phone) {
        PartyDetailView detail = mock(PartyDetailView.class);
        when(detail.phoneNumber()).thenReturn(phone);
        when(parties.getPartyDetail(party)).thenReturn(detail);
    }

    @Test
    void theCodeGoesToThePhoneOnFileAndTheLogKeepsItMasked() {
        phoneOnFile(" +255700000777 ");
        texts.send(party, "042917", "en");
        verify(notifications).sendUnloggedSms(party, "+255700000777", "PASSWORD_RESET_CODE",
            "Your password reset code is 042917. It works for 15 minutes. Do not share it: we will never ask you for it.",
            "Your password reset code is ******. It works for 15 minutes. Do not share it: we will never ask you for it.");
    }

    @Test
    void inSwahiliWhenThePersonSignsInInSwahili() {
        phoneOnFile("+255700000777");
        texts.send(party, "042917", "sw");
        verify(notifications).sendUnloggedSms(eq(party), eq("+255700000777"), eq("PASSWORD_RESET_CODE"),
            eq(PasswordResetTexts.text("042917", true)), eq(PasswordResetTexts.text("******", true)));
        assertThat(PasswordResetTexts.text("042917", true)).startsWith("Namba yako ya kubadilisha nenosiri ni 042917.");
    }

    @Test
    void nobodyIsTextedMoreThanThreeTimesAnHour() {
        phoneOnFile("+255700000777");
        for (int i = 0; i < 5; i++) texts.send(party, "04291" + i, "en");
        verify(notifications, times(3)).sendUnloggedSms(eq(party), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void noPhoneNoPartyOrABadCodeSendsNothingAndThrowsNothing() {
        phoneOnFile("  ");
        texts.send(party, "042917", "en");
        when(parties.getPartyDetail(party)).thenThrow(new IllegalArgumentException("not found"));
        texts.send(party, "042917", "en");
        texts.send(UUID.randomUUID(), "12ab56", "en");
        verify(notifications, never()).sendUnloggedSms(any(), any(), any(), any(), any());
    }
}
