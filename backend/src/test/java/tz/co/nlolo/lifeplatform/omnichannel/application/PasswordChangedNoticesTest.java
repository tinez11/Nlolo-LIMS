package tz.co.nlolo.lifeplatform.omnichannel.application;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.communication.api.NotificationApi;
import tz.co.nlolo.lifeplatform.iam.api.StaffContacts;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyDetailView;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "Your password was changed" (2026-11-06; Authentik cut-over ticket 06, 2026-11-19): who is texted, at which number,
 * in which language, stamped with when Authentik saw the change, once per event, and never on the caller's thread.
 */
class PasswordChangedNoticesTest {

    private final PartyApi parties = mock(PartyApi.class);
    private final StaffContacts staff = mock(StaffContacts.class);
    private final NotificationApi notifications = mock(NotificationApi.class);
    private final UUID user = UUID.randomUUID();
    private final UUID party = UUID.randomUUID();
    /** The background work, run when the test says so. */
    private final List<Runnable> queued = new ArrayList<>();
    private final PasswordChangedNotices notices =
        new PasswordChangedNotices(parties, staff, notifications, "+255 22 000 0000", queued::add);
    /** 11:32 UTC is 14:32 in Dar es Salaam. */
    private static final Instant AT = Instant.parse("2026-11-06T11:32:00Z");

    private static final String EXPECTED_EN =
        "Your Nlolo password was changed on 6 Nov at 14:32. If this wasn't you, call +255 22 000 0000.";

    /** The controller sets the person's tenant before calling; the background work must carry it over. */
    @org.junit.jupiter.api.BeforeEach
    void inTheirTenant() {
        tz.co.nlolo.lifeplatform.TenantContext.set(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenant() {
        tz.co.nlolo.lifeplatform.TenantContext.clear();
    }

    private void runQueued() {
        tz.co.nlolo.lifeplatform.TenantContext.clear(); // as on the background thread, until the work sets it
        queued.forEach(Runnable::run);
        queued.clear();
        inTheirTenant(); // back on the "request" thread
    }

    private void aCustomerWithPhone(String phone, String language) {
        PartyDetailView detail = mock(PartyDetailView.class);
        when(detail.phoneNumber()).thenReturn(phone);
        when(detail.preferredLanguage()).thenReturn(language);
        when(parties.getPartyDetail(party)).thenReturn(detail);
    }

    @Test
    void anAgentOrCustomerIsTextedAtThePhoneOnTheirRecordStampedWithTheChangesTime() {
        aCustomerWithPhone(" +255700000777 ", "en");

        assertThat(notices.passwordChanged("event-1", user, party, AT)).isTrue();
        verify(notifications, never()).sendUnloggedSms(any(), anyString(), anyString(), anyString(), anyString());
        runQueued();

        verify(notifications).sendUnloggedSms(party, "+255700000777", "PASSWORD_CHANGED", EXPECTED_EN, EXPECTED_EN);
        verify(staff, never()).phoneOf(any());
    }

    @Test
    void theirOwnLanguageComesFromTheirRecord() {
        aCustomerWithPhone("+255700000777", "sw");
        notices.passwordChanged("event-1", user, party, AT);
        runQueued();
        verify(notifications).sendUnloggedSms(any(), anyString(), anyString(),
            org.mockito.ArgumentMatchers.startsWith("Nenosiri lako la Nlolo"), anyString());

        when(staff.phoneOf(user)).thenReturn(Optional.of("+255700000123"));
        when(staff.languageOf(user)).thenReturn("sw");
        notices.passwordChanged("event-2", user, null, AT);
        runQueued();
        verify(notifications).sendSystemSms(anyString(), anyString(),
            org.mockito.ArgumentMatchers.startsWith("Nenosiri lako la Nlolo"));
    }

    @Test
    void aStaffMemberIsTextedAtThePhoneOnTheirStaffRecord() {
        when(staff.phoneOf(user)).thenReturn(Optional.of("+255700000123"));
        when(staff.languageOf(user)).thenReturn("en");

        notices.passwordChanged("event-1", user, null, AT);
        runQueued();

        verify(notifications).sendSystemSms("+255700000123", "PASSWORD_CHANGED", EXPECTED_EN);
        verify(parties, never()).getPartyDetail(any());
    }

    /** Authentik retries a notice up to five times; the same event texts once. */
    @Test
    void theSameEventDeliveredTwiceTextsOnce() {
        when(staff.phoneOf(user)).thenReturn(Optional.of("+255700000123"));
        when(staff.languageOf(user)).thenReturn("en");

        assertThat(notices.passwordChanged("event-1", user, null, AT)).isTrue();
        assertThat(notices.passwordChanged("event-1", user, null, AT)).isFalse();
        runQueued();

        verify(notifications, times(1)).sendSystemSms(anyString(), anyString(), anyString());
    }

    @Test
    void noPhoneMeansNoTextAndNoError() {
        when(staff.phoneOf(user)).thenReturn(Optional.empty());
        notices.passwordChanged("event-1", user, null, AT);
        runQueued();
        verify(notifications, never()).sendSystemSms(anyString(), anyString(), anyString());
    }

    @Test
    void aFailingLookupNeverEscapes() {
        when(parties.getPartyDetail(party)).thenThrow(new IllegalStateException("no such party"));
        notices.passwordChanged("event-1", user, party, AT);
        assertThatCode(this::runQueued).doesNotThrowAnyException();
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
