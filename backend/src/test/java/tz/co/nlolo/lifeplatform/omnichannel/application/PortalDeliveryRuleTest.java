package tz.co.nlolo.lifeplatform.omnichannel.application;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.omnichannel.domain.PortalAccess.Delivery;

import static org.assertj.core.api.Assertions.assertThat;

/** The channel table, row by row: SMS first wherever there is a phone (2026-11-08, the user). */
class PortalDeliveryRuleTest {

    @Test
    void emailOn() {
        assertThat(PortalAccessService.deliveryFor("a@b.tz", "+255700000001", true)).isEqualTo(Delivery.SMS_PASSWORD);
        assertThat(PortalAccessService.deliveryFor("a@b.tz", null, true)).isEqualTo(Delivery.EMAIL_LINK);
        assertThat(PortalAccessService.deliveryFor(null, "+255700000001", true)).isEqualTo(Delivery.SMS_PASSWORD);
    }

    @Test
    void emailOff() {
        assertThat(PortalAccessService.deliveryFor("a@b.tz", "+255700000001", false)).isEqualTo(Delivery.SMS_PASSWORD);
        assertThat(PortalAccessService.deliveryFor("a@b.tz", null, false)).isEqualTo(Delivery.TEMPORARY_PASSWORD);
        assertThat(PortalAccessService.deliveryFor(null, "+255700000001", false)).isEqualTo(Delivery.SMS_PASSWORD);
    }

    /** 2026-11-16, the user: "the email is not used" -- so a login is never named after an address nobody uses. */
    @Test
    void usernameWithEmailOffIsThePhoneTheTextGoesTo() {
        assertThat(PortalAccessService.usernameFor("A@B.tz", "+255700000001", false)).isEqualTo("+255700000001");
        assertThat(PortalAccessService.usernameFor(null, "+255700000001", false)).isEqualTo("+255700000001");
        assertThat(PortalAccessService.usernameFor("A@B.tz", null, false)).isEqualTo("a@b.tz");
    }

    @Test
    void usernameWithEmailOnIsTheEmailAsBefore() {
        assertThat(PortalAccessService.usernameFor("A@B.tz", "+255700000001", true)).isEqualTo("a@b.tz");
        assertThat(PortalAccessService.usernameFor(null, "+255700000001", true)).isEqualTo("+255700000001");
    }
}
