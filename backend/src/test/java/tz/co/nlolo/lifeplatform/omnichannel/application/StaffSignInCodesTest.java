package tz.co.nlolo.lifeplatform.omnichannel.application;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.communication.api.NotificationApi;
import tz.co.nlolo.lifeplatform.iam.api.StaffContacts;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A staff member's sign-in code (2026-11-14; Authentik cut-over ticket 05, 2026-11-19): no phone, the editable wording,
 * its fallback, a refusal, and the caps that moved here from Keycloak. Whether to ask for a code at all is Authentik's
 * flow now, so there is no "off" answer.
 */
class StaffSignInCodesTest {

    private final StaffContacts staff = mock(StaffContacts.class);
    private final NotificationApi notifications = mock(NotificationApi.class);
    private final UUID user = UUID.randomUUID();
    private static final String PHONE = "+255755418369";
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-19T08:00:00Z"));

    private StaffSignInCodes codes() {
        return new StaffSignInCodes(staff, notifications, clock);
    }

    private void aStaffMemberWithAPhone() {
        when(staff.phoneOf(user)).thenReturn(Optional.of(PHONE));
        when(staff.languageOf(user)).thenReturn("en");
        when(notifications.renderSystemTemplate(any(), any(), any())).thenAnswer(i ->
            Optional.of("Your Nlolo sign-in code is " + ((Map<?, ?>) i.getArgument(2)).get("code") + "."));
    }

    @Test
    void noPhoneIsSaidAndNothingIsSent() {
        when(staff.phoneOf(user)).thenReturn(Optional.empty());
        assertThat(codes().send(user, "123456", null)).isEqualTo(StaffSignInCodes.Result.NO_PHONE);
        verify(notifications, never()).sendSystemSms(anyString(), anyString(), anyString());
    }

    /** The first administrator, made on the server with --phone, has no staff record until they first sign in. */
    @Test
    void withNoStaffRecordPhoneTheLoginsOwnPhoneIsUsedAndTheRecordWinsWhenThereIsOne() {
        when(staff.phoneOf(user)).thenReturn(Optional.empty());
        when(staff.languageOf(user)).thenReturn("en");
        when(notifications.renderSystemTemplate(any(), any(), any()))
            .thenReturn(Optional.of("Your Nlolo sign-in code is 123456."));
        assertThat(codes().send(user, "123456", " +255700000002 ")).isEqualTo(StaffSignInCodes.Result.SENT);
        verify(notifications).sendSystemSms("+255700000002", "STAFF_SIGN_IN_CODE", "Your Nlolo sign-in code is 123456.");

        when(staff.phoneOf(user)).thenReturn(Optional.of(PHONE));
        clock.advance(Duration.ofMinutes(2));
        assertThat(codes().send(user, "123456", "+255700000002")).isEqualTo(StaffSignInCodes.Result.SENT);
        verify(notifications).sendSystemSms(PHONE, "STAFF_SIGN_IN_CODE", "Your Nlolo sign-in code is 123456.");
    }

    /** The login's phone is only trusted when it is one: a username or other key must never be texted. */
    @Test
    void aLoginPhoneThatIsNotAPhoneNumberIsNoPhone() {
        when(staff.phoneOf(user)).thenReturn(Optional.empty());
        assertThat(codes().send(user, "123456", "staff.underwriter")).isEqualTo(StaffSignInCodes.Result.NO_PHONE);
        assertThat(codes().send(user, "123456", "   ")).isEqualTo(StaffSignInCodes.Result.NO_PHONE);
        verify(notifications, never()).sendSystemSms(anyString(), anyString(), anyString());
    }

    @Test
    void theWordingInTheirLanguageCarriesTheCode() {
        when(staff.phoneOf(user)).thenReturn(Optional.of(PHONE));
        when(staff.languageOf(user)).thenReturn("sw");
        when(notifications.renderSystemTemplate("STAFF_SIGN_IN_CODE", "sw", Map.of("code", "123456")))
            .thenReturn(Optional.of("Namba yako ya kuingia Nlolo ni 123456."));

        assertThat(codes().send(user, "123456", null)).isEqualTo(StaffSignInCodes.Result.SENT);
        verify(notifications).sendSystemSms(PHONE, "STAFF_SIGN_IN_CODE", "Namba yako ya kuingia Nlolo ni 123456.");
    }

    @Test
    void aWordingThatLostTheCodeFallsBackToTheBuiltInText() {
        when(staff.phoneOf(user)).thenReturn(Optional.of(PHONE));
        when(staff.languageOf(user)).thenReturn("en");
        when(notifications.renderSystemTemplate(any(), any(), any())).thenReturn(Optional.of("Your code is on its way."));

        assertThat(codes().send(user, "654321", null)).isEqualTo(StaffSignInCodes.Result.SENT);
        verify(notifications).sendSystemSms(PHONE, "STAFF_SIGN_IN_CODE",
            "Your Nlolo sign-in code is 654321. It expires in 5 minutes. Never share it.");
    }

    @Test
    void aGatewayRefusalIsNotSent() {
        aStaffMemberWithAPhone();
        when(notifications.sendSystemSms(any(), any(), any())).thenReturn("Recipient is not on the SMS testing allowlist");

        assertThat(codes().send(user, "123456", null)).isEqualTo(StaffSignInCodes.Result.NOT_SENT);
    }

    @Test
    void oneCodeAMinute() {
        aStaffMemberWithAPhone();
        StaffSignInCodes codes = codes();

        assertThat(codes.send(user, "111111", null)).isEqualTo(StaffSignInCodes.Result.SENT);
        clock.advance(Duration.ofSeconds(30));
        assertThat(codes.send(user, "222222", null)).isEqualTo(StaffSignInCodes.Result.TOO_MANY);
        clock.advance(Duration.ofSeconds(31));
        assertThat(codes.send(user, "333333", null)).isEqualTo(StaffSignInCodes.Result.SENT);
        verify(notifications, times(2)).sendSystemSms(anyString(), anyString(), anyString());
    }

    @Test
    void fiveCodesAnHourThenNoneUntilTheOldestIsAnHourOld() {
        aStaffMemberWithAPhone();
        StaffSignInCodes codes = codes();

        for (int i = 0; i < 5; i++) {
            assertThat(codes.send(user, "10000" + i, null)).as("code %d", i + 1).isEqualTo(StaffSignInCodes.Result.SENT);
            clock.advance(Duration.ofMinutes(2));
        }
        assertThat(codes.send(user, "100005", null)).isEqualTo(StaffSignInCodes.Result.TOO_MANY);
        clock.advance(Duration.ofMinutes(51)); // the first code is now an hour old
        assertThat(codes.send(user, "100006", null)).isEqualTo(StaffSignInCodes.Result.SENT);
    }

    /**
     * Asked before the code step (2026-11-19, the user's choice), so Authentik can show the reason: Authentik's own pages
     * do not show a refused send. Asking never counts as a send.
     */
    @Test
    void askingWhetherACodeCanBeSentSaysWhyNotAndSpendsNothing() {
        when(staff.phoneOf(user)).thenReturn(Optional.empty());
        StaffSignInCodes codes = codes();
        assertThat(codes.refusal(user, null)).contains(StaffSignInCodes.Result.NO_PHONE);
        assertThat(codes.refusal(user, "+255700000002")).isEmpty();

        aStaffMemberWithAPhone();
        assertThat(codes.refusal(user, null)).isEmpty();
        assertThat(codes.refusal(user, null)).isEmpty(); // asking twice is not two sends
        assertThat(codes.send(user, "111111", null)).isEqualTo(StaffSignInCodes.Result.SENT);
        assertThat(codes.refusal(user, null)).contains(StaffSignInCodes.Result.TOO_MANY);
        clock.advance(Duration.ofSeconds(61));
        assertThat(codes.refusal(user, null)).isEmpty();
        verify(notifications, times(1)).sendSystemSms(anyString(), anyString(), anyString());
    }

    @Test
    void theCapsArePerPerson() {
        aStaffMemberWithAPhone();
        UUID other = UUID.randomUUID();
        when(staff.phoneOf(other)).thenReturn(Optional.of("+255700000003"));
        when(staff.languageOf(other)).thenReturn("en");
        StaffSignInCodes codes = codes();

        assertThat(codes.send(user, "111111", null)).isEqualTo(StaffSignInCodes.Result.SENT);
        assertThat(codes.send(other, "222222", null)).isEqualTo(StaffSignInCodes.Result.SENT);
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
