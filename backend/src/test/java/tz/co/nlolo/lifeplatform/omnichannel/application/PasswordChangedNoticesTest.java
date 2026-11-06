package tz.co.nlolo.lifeplatform.omnichannel.application;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.communication.api.NotificationApi;
import tz.co.nlolo.lifeplatform.iam.api.StaffContacts;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyDetailView;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** "Your password was changed" (2026-11-06): who is texted, at which number, and what it says. */
class PasswordChangedNoticesTest {

    private final PartyApi parties = mock(PartyApi.class);
    private final StaffContacts staff = mock(StaffContacts.class);
    private final NotificationApi notifications = mock(NotificationApi.class);
    private final UUID user = UUID.randomUUID();
    private final UUID party = UUID.randomUUID();
    /** 11:32 UTC is 14:32 in Dar es Salaam. */
    private final PasswordChangedNotices notices = new PasswordChangedNotices(parties, staff, notifications,
        "+255 22 000 0000", Clock.fixed(Instant.parse("2026-11-06T11:32:00Z"), ZoneOffset.UTC));

    private static final String EXPECTED_EN =
        "Your Nlolo password was changed on 6 Nov at 14:32. If this wasn't you, call +255 22 000 0000.";

    @Test
    void anAgentOrCustomerIsTextedAtThePhoneOnTheirRecord() {
        PartyDetailView detail = mock(PartyDetailView.class);
        when(detail.phoneNumber()).thenReturn(" +255700000777 ");
        when(parties.getPartyDetail(party)).thenReturn(detail);

        notices.passwordChanged(user, party, "en");

        verify(notifications).sendUnloggedSms(party, "+255700000777", "PASSWORD_CHANGED", EXPECTED_EN, EXPECTED_EN);
        verify(staff, never()).phoneOf(any());
    }

    @Test
    void aStaffMemberIsTextedAtThePhoneOnTheirStaffRecord() {
        when(staff.phoneOf(user)).thenReturn(Optional.of("+255700000123"));

        notices.passwordChanged(user, null, "en");

        verify(notifications).sendSystemSms("+255700000123", "PASSWORD_CHANGED", EXPECTED_EN);
        verify(parties, never()).getPartyDetail(any());
    }

    @Test
    void noPhoneMeansNoTextAndNoError() {
        when(staff.phoneOf(user)).thenReturn(Optional.empty());
        notices.passwordChanged(user, null, "en");
        verify(notifications, never()).sendSystemSms(anyString(), anyString(), anyString());
    }

    @Test
    void aFailingLookupNeverReachesKeycloak() {
        when(parties.getPartyDetail(party)).thenThrow(new IllegalStateException("no such party"));
        assertThatCode(() -> notices.passwordChanged(user, party, "en")).doesNotThrowAnyException();
    }

    @Test
    void swahiliAndNoSupportNumber() {
        ZonedDateTime at = ZonedDateTime.parse("2026-11-06T14:32:00+03:00[Africa/Dar_es_Salaam]");
        assertThat(PasswordChangedNotices.text(at, "+255 22 000 0000", true))
            .startsWith("Nenosiri lako la Nlolo limebadilishwa tarehe 6 ")
            .contains("saa 14:32")
            .endsWith("Kama si wewe, piga +255 22 000 0000.");
        assertThat(PasswordChangedNotices.text(at, "", false))
            .isEqualTo("Your Nlolo password was changed on 6 Nov at 14:32. If this wasn't you, contact us straight away.");
    }
}
