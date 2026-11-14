package tz.co.nlolo.lifeplatform.omnichannel.application;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.communication.api.NotificationApi;
import tz.co.nlolo.lifeplatform.iam.api.StaffContacts;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** A staff member's sign-in code (2026-11-14): off, no phone, the editable wording, its fallback, a refusal. */
class StaffSignInCodesTest {

    private final StaffContacts staff = mock(StaffContacts.class);
    private final NotificationApi notifications = mock(NotificationApi.class);
    private final UUID user = UUID.randomUUID();
    private static final String PHONE = "+255755418369";

    private StaffSignInCodes on() {
        return new StaffSignInCodes(true, staff, notifications);
    }

    @Test
    void offSendsNothingAndLooksNothingUp() {
        assertThat(new StaffSignInCodes(false, staff, notifications).send(user, "123456", null))
            .isEqualTo(StaffSignInCodes.Result.OFF);
        verifyNoInteractions(staff, notifications);
    }

    @Test
    void noPhoneIsSaidAndNothingIsSent() {
        when(staff.phoneOf(user)).thenReturn(Optional.empty());
        assertThat(on().send(user, "123456", null)).isEqualTo(StaffSignInCodes.Result.NO_PHONE);
        verify(notifications, never()).sendSystemSms(anyString(), anyString(), anyString());
    }

    /** The first administrator, made on the server with --phone, has no staff record until they first sign in. */
    @Test
    void withNoStaffRecordPhoneTheLoginsOwnPhoneIsUsedAndTheRecordWinsWhenThereIsOne() {
        when(staff.phoneOf(user)).thenReturn(Optional.empty());
        when(staff.languageOf(user)).thenReturn("en");
        when(notifications.renderSystemTemplate(any(), any(), any()))
            .thenReturn(Optional.of("Your Nlolo sign-in code is 123456."));
        assertThat(on().send(user, "123456", " +255700000002 ")).isEqualTo(StaffSignInCodes.Result.SENT);
        verify(notifications).sendSystemSms("+255700000002", "STAFF_SIGN_IN_CODE", "Your Nlolo sign-in code is 123456.");

        when(staff.phoneOf(user)).thenReturn(Optional.of(PHONE));
        assertThat(on().send(user, "123456", "+255700000002")).isEqualTo(StaffSignInCodes.Result.SENT);
        verify(notifications).sendSystemSms(PHONE, "STAFF_SIGN_IN_CODE", "Your Nlolo sign-in code is 123456.");
        assertThat(on().send(user, "123456", "  ")).isEqualTo(StaffSignInCodes.Result.SENT);
    }

    @Test
    void theWordingInTheirLanguageCarriesTheCode() {
        when(staff.phoneOf(user)).thenReturn(Optional.of(PHONE));
        when(staff.languageOf(user)).thenReturn("sw");
        when(notifications.renderSystemTemplate("STAFF_SIGN_IN_CODE", "sw", Map.of("code", "123456")))
            .thenReturn(Optional.of("Namba yako ya kuingia Nlolo ni 123456."));
        when(notifications.sendSystemSms(any(), any(), any())).thenReturn(null);

        assertThat(on().send(user, "123456", null)).isEqualTo(StaffSignInCodes.Result.SENT);
        verify(notifications).sendSystemSms(PHONE, "STAFF_SIGN_IN_CODE", "Namba yako ya kuingia Nlolo ni 123456.");
    }

    @Test
    void aWordingThatLostTheCodeFallsBackToTheBuiltInText() {
        when(staff.phoneOf(user)).thenReturn(Optional.of(PHONE));
        when(staff.languageOf(user)).thenReturn("en");
        when(notifications.renderSystemTemplate(any(), any(), any())).thenReturn(Optional.of("Your code is on its way."));

        assertThat(on().send(user, "654321", null)).isEqualTo(StaffSignInCodes.Result.SENT);
        verify(notifications).sendSystemSms(PHONE, "STAFF_SIGN_IN_CODE",
            "Your Nlolo sign-in code is 654321. It expires in 5 minutes. Never share it.");
    }

    @Test
    void noTemplateAtAllFallsBackToo() {
        when(staff.phoneOf(user)).thenReturn(Optional.of(PHONE));
        when(staff.languageOf(user)).thenReturn("en");
        when(notifications.renderSystemTemplate(any(), any(), any())).thenReturn(Optional.empty());

        assertThat(on().send(user, "111222", null)).isEqualTo(StaffSignInCodes.Result.SENT);
        verify(notifications).sendSystemSms(PHONE, "STAFF_SIGN_IN_CODE", StaffSignInCodes.builtIn("111222"));
    }

    @Test
    void aGatewayRefusalIsNotSent() {
        when(staff.phoneOf(user)).thenReturn(Optional.of(PHONE));
        when(staff.languageOf(user)).thenReturn("en");
        when(notifications.renderSystemTemplate(any(), any(), any()))
            .thenReturn(Optional.of("Your Nlolo sign-in code is 123456."));
        when(notifications.sendSystemSms(any(), any(), any())).thenReturn("Recipient is not on the SMS testing allowlist");

        assertThat(on().send(user, "123456", null)).isEqualTo(StaffSignInCodes.Result.NOT_SENT);
    }
}
