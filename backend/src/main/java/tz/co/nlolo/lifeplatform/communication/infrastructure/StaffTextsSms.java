package tz.co.nlolo.lifeplatform.communication.infrastructure;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.communication.api.NotificationApi;
import tz.co.nlolo.lifeplatform.iam.api.StaffTexts;

/**
 * Texts a staff member through the platform's SMS gateway (2026-11-06) -- iam's {@link StaffTexts} port, implemented
 * here so iam never depends on this module. Through {@code sendSystemSms}: staff have no client record, and the body
 * may hold a one-time password, so no message-log row is kept.
 */
@Component
class StaffTextsSms implements StaffTexts {

    private final NotificationApi notifications;

    StaffTextsSms(NotificationApi notifications) {
        this.notifications = notifications;
    }

    @Override
    public String send(String phoneNumber, String body) {
        return notifications.sendSystemSms(phoneNumber, "STAFF_LOGIN", body);
    }
}
