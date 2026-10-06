package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingQueueApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingRulesView;
import tz.co.nlolo.lifeplatform.finaccounting.api.UnpostedEventView;

import java.util.List;
import java.util.UUID;

/**
 * The posting rules, read-only, and the queue of events they could not post (IFRS 17 I3a): finance retries one once
 * the rules are fixed, or dismisses it with a reason.
 */
@RestController
public class PostingQueueController {

    private static final String FINANCE = "hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))";

    public record DismissRequest(String reason) {}

    private final PostingQueueApi api;

    public PostingQueueController(PostingQueueApi api) {
        this.api = api;
    }

    @GetMapping("/finance/posting-rules")
    @PreAuthorize(FINANCE)
    public PostingRulesView rules() {
        return api.postingRules();
    }

    @GetMapping("/finance/unposted-events")
    @PreAuthorize(FINANCE)
    public List<UnpostedEventView> list(@RequestParam(defaultValue = "false") boolean openOnly) {
        return api.unpostedEvents(openOnly);
    }

    @PostMapping("/finance/unposted-events/{id}/retry")
    @PreAuthorize(FINANCE)
    public UnpostedEventView retry(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return api.retryUnpostedEvent(id, jwt.getSubject());
    }

    @PostMapping("/finance/unposted-events/{id}/dismissal")
    @PreAuthorize(FINANCE)
    public UnpostedEventView dismiss(@PathVariable UUID id, @RequestBody(required = false) DismissRequest body,
                                     @AuthenticationPrincipal Jwt jwt) {
        return api.dismissUnpostedEvent(id, body == null ? null : body.reason(), jwt.getSubject());
    }
}
