package tz.co.nlolo.lifeplatform.communication.infrastructure;

import tz.co.nlolo.lifeplatform.communication.api.NotificationApi;
import tz.co.nlolo.lifeplatform.communication.api.NotificationDispatchView;
import tz.co.nlolo.lifeplatform.communication.api.NotificationTemplateView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * The read surface over what this platform says to customers, plus the one write that changes it.
 *
 * <p><b>There is no send endpoint, and that is a design decision rather than an omission.</b>
 * Messages are produced by consuming domain events and by a scheduled sweep. Nothing on this
 * platform sends a customer an SMS because an HTTP caller asked it to — a "send" endpoint would
 * be an unauthenticated-adjacent way to make the platform contact anybody, and every legitimate
 * message already has a business event behind it that says why.
 */
@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationApi notificationApi;

    public NotificationController(NotificationApi notificationApi) {
        this.notificationApi = notificationApi;
    }

    @GetMapping("/templates")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public List<NotificationTemplateView> listTemplates() {
        return notificationApi.listTemplates();
    }

    /**
     * ADMIN, matching product authoring.
     *
     * <p>Rewording a template changes what every future customer is told, which is a larger act
     * than most staff writes on this platform: a careless edit reaches thousands of people and
     * nobody sees it until they do. Read stays open to all staff, because answering "what do we
     * actually say" should not need a privileged role.
     */
    @PutMapping("/templates/{templateId}")
    @PreAuthorize("hasRole('REALM_STAFF') and hasRole('ADMIN')")
    public ResponseEntity<NotificationTemplateView> rewordTemplate(
            @PathVariable UUID templateId,
            @Valid @RequestBody RewordTemplateRequestDto request) {
        return ResponseEntity.ok(notificationApi.rewordTemplate(templateId, request.bodyTemplate()));
    }

    @GetMapping("/dispatches")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public List<NotificationDispatchView> listDispatches(
            @RequestParam(required = false) UUID partyId,
            @RequestParam(required = false) String policyNumber,
            @RequestParam(required = false) String status) {
        return notificationApi.listDispatches(partyId, policyNumber, status);
    }

    /**
     * Body text only.
     *
     * <p>Key, channel and language are identity rather than content, so they are not on this
     * request at all: a template that changed its key would silently stop being the message the
     * sender looks up, and one that changed its channel would be an SMS rendered into an email.
     */
    public record RewordTemplateRequestDto(@NotBlank String bodyTemplate) {}
}
