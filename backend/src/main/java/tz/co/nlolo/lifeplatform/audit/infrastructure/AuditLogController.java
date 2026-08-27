package tz.co.nlolo.lifeplatform.audit.infrastructure;

import tz.co.nlolo.lifeplatform.audit.api.AuditApi;
import tz.co.nlolo.lifeplatform.audit.api.AuditEntryView;
import tz.co.nlolo.lifeplatform.audit.api.DateRange;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * The audit module's FIRST controller. It has held 15 files and an API since M1,
 * and the log has been written on every domain event since -- with no way to read
 * it from outside the platform.
 *
 * READ WHAT THIS IS. It exposes a domain-event JOURNAL: event type, when it
 * occurred, when it was recorded, and the event's own JSON payload. `audit_log`
 * has no actor column, and no before/after or reason. A compliance screen wants
 * actor, action, record, before, after and reason; five of those six exist nowhere
 * on this platform. Any UI over this must say what it is rather than presenting an
 * event feed as compliance evidence -- see AuditApi.listEvents.
 *
 * `payloadJson` is returned RAW and unparsed, deliberately: the payload shape
 * varies per event type across forty-odd events, and normalising it here would be
 * a translation layer nobody has specified.
 */
@RestController
public class AuditLogController {

    private final AuditApi auditApi;

    public AuditLogController(AuditApi auditApi) {
        this.auditApi = auditApi;
    }

    @GetMapping("/audit-log")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<AuditLogSearchResponse> listEvents(
            @RequestParam(required = false) String eventTypePrefix,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        Page<AuditEntryView> result = auditApi.listEvents(eventTypePrefix, new DateRange(from, to),
            PageRequest.of(page, Math.min(pageSize, 100), Sort.by(Sort.Direction.DESC, "occurredAt")));
        return ResponseEntity.ok(AuditLogSearchResponse.from(result));
    }
}
