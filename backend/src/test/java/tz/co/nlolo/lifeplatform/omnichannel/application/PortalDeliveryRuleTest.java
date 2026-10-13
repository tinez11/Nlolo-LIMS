package tz.co.nlolo.lifeplatform.omnichannel.application;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.omnichannel.domain.PortalAccess.Delivery;

import static org.assertj.core.api.Assertions.assertThat;

/** The channel table of the deployment pilot design §6, row by row. */
class PortalDeliveryRuleTest {

    @Test
    void emailOn() {
        assertThat(PortalAccessService.deliveryFor("a@b.tz", "+255700000001", true)).isEqualTo(Delivery.EMAIL_LINK);
        assertThat(PortalAccessService.deliveryFor("a@b.tz", null, true)).isEqualTo(Delivery.EMAIL_LINK);
        assertThat(PortalAccessService.deliveryFor(null, "+255700000001", true)).isEqualTo(Delivery.SMS_PASSWORD);
    }

    @Test
    void emailOff() {
        assertThat(PortalAccessService.deliveryFor("a@b.tz", "+255700000001", false)).isEqualTo(Delivery.SMS_PASSWORD);
        assertThat(PortalAccessService.deliveryFor("a@b.tz", null, false)).isEqualTo(Delivery.TEMPORARY_PASSWORD);
        assertThat(PortalAccessService.deliveryFor(null, "+255700000001", false)).isEqualTo(Delivery.SMS_PASSWORD);
    }
}
