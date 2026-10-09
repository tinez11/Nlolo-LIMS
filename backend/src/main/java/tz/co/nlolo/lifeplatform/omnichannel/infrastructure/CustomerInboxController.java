package tz.co.nlolo.lifeplatform.omnichannel.infrastructure;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.communication.api.InboxMessageView;
import tz.co.nlolo.lifeplatform.communication.api.NotificationApi;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The customer's messages (2026-10-08, the customer portal design step 7): what we sent them by SMS and email, readable
 * in the portal whether or not it reached their phone. Always the token's own party.
 */
@RestController
public class CustomerInboxController {

    /** A message in the inbox: a title in plain words, the text as sent (null for old messages), and whether opened. */
    public record CustomerMessage(UUID messageId, String title, String policyNumber, String body, Instant sentAt,
                                  boolean read) {}

    /** What each kind of message is about, for its title -- and the whole message, where the text was not kept. */
    static final Map<String, String> TITLES = Map.ofEntries(
        Map.entry("OFFER_MADE", "Your policy offer is ready"),
        Map.entry("OFFER_CLOSING", "Your policy offer closes soon"),
        Map.entry("OFFER_EXPIRED", "Your policy offer has closed"),
        Map.entry("COVER_STARTED", "Your cover has started"),
        Map.entry("PAYMENT_RECEIVED", "Payment received"),
        Map.entry("ACCOUNT_STATEMENT", "Your account statement"),
        Map.entry("PENSION_VESTING_REMINDER", "Your pension starts soon"),
        Map.entry("FUNERAL_LIFE_ADDED", "Someone was added to your funeral cover"),
        Map.entry("FUNERAL_LIFE_ENDED", "Cover ended for someone on your funeral plan"),
        Map.entry("FUNERAL_PREMIUM_CHANGED", "Your funeral premium has changed"),
        Map.entry("UNIT_LINKED_ALLOCATED", "Your payment was invested"),
        Map.entry("UNIT_LINKED_STATEMENT", "Your investment statement"),
        Map.entry("UNIT_LINKED_LOW_FUND", "Your investment fund is running low"),
        Map.entry("UNIT_LINKED_LAPSED_EXHAUSTED", "Your investment plan has lapsed"),
        Map.entry("UNIT_LINKED_PROCEEDS", "Your investment proceeds"));

    private final NotificationApi notificationApi;

    public CustomerInboxController(NotificationApi notificationApi) {
        this.notificationApi = notificationApi;
    }

    @GetMapping("/customer/messages")
    @PreAuthorize("hasRole('REALM_CUSTOMERS')")
    public ResponseEntity<List<CustomerMessage>> messages(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(notificationApi.inbox(CustomerPortalController.customer(jwt)).stream()
            .map(CustomerInboxController::toMessage).toList());
    }

    @PostMapping("/customer/messages/{messageId}/read")
    @PreAuthorize("hasRole('REALM_CUSTOMERS')")
    public ResponseEntity<CustomerMessage> read(@PathVariable UUID messageId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(toMessage(notificationApi.markRead(CustomerPortalController.customer(jwt), messageId)));
    }

    static CustomerMessage toMessage(InboxMessageView m) {
        return new CustomerMessage(m.messageId(), TITLES.getOrDefault(m.templateKey(), "A message from Nlolo Life"),
            m.policyNumber(), m.body(), m.sentAt(), m.read());
    }
}
