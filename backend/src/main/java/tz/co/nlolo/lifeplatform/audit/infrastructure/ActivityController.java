package tz.co.nlolo.lifeplatform.audit.infrastructure;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.FileDownloadResponses;
import tz.co.nlolo.lifeplatform.audit.application.ActivityQueries;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The activity log (2026-11-14, the user: who did what, when, to what, seen by admins). Holders of audit.view only.
 * Personal numbers are masked everywhere, the opened line included (2026-11-15, the user, reversing "full when opened").
 */
@RestController
public class ActivityController {

    static final int CSV_LIMIT = 10_000;
    private static final ZoneId DAR = ZoneId.of("Africa/Dar_es_Salaam");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Map<String, String> KIND = Map.of("STAFF", "staff", "AGENT", "agent", "CUSTOMER", "customer");

    private final ActivityQueries queries;

    public ActivityController(ActivityQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/activity")
    @PreAuthorize("@access.can('audit.view')")
    public Map<String, Object> list(
            @RequestParam(required = false) String person, @RequestParam(required = false) String recordType,
            @RequestParam(required = false) String recordId, @RequestParam(required = false) String q,
            @RequestParam(required = false) String kind, @RequestParam(required = false) String outcome,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int pageSize) {
        int size = Math.max(1, Math.min(pageSize, 100));
        ActivityQueries.Page result = queries.list(filters(person, recordType, recordId, q, kind, outcome, from, to),
            Math.max(0, page), size);
        return Map.of(
            "items", result.items().stream().map(ActivityQueries::masked).toList(),
            "page", Map.of("page", result.page(), "pageSize", result.pageSize(), "totalElements", result.totalElements()));
    }

    @GetMapping("/activity/people")
    @PreAuthorize("@access.can('audit.view')")
    public List<ActivityQueries.Person> people() {
        return queries.people();
    }

    @GetMapping("/activity/{activityId}")
    @PreAuthorize("@access.can('audit.view')")
    public ResponseEntity<ActivityQueries.Detail> detail(@PathVariable UUID activityId) {
        return queries.detail(activityId).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/activity.csv")
    @PreAuthorize("@access.can('audit.view')")
    public ResponseEntity<byte[]> csv(
            @RequestParam(required = false) String person, @RequestParam(required = false) String recordType,
            @RequestParam(required = false) String recordId, @RequestParam(required = false) String q,
            @RequestParam(required = false) String kind, @RequestParam(required = false) String outcome,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        ActivityQueries.Filters f = filters(person, recordType, recordId, q, kind, outcome, from, to);
        StringWriter out = new StringWriter();
        try (CSVPrinter csv = new CSVPrinter(out, CSVFormat.DEFAULT.builder()
                .setHeader("When", "Who", "Did", "Record", "Amount", "Outcome", "Reason").build())) {
            for (ActivityQueries.Line l : queries.all(f, CSV_LIMIT)) {
                ActivityQueries.Line m = ActivityQueries.masked(l);
                csv.printRecord(WHEN.format(m.occurredAt().atZone(DAR)), who(m), m.label(),
                    m.recordLabel() != null ? m.recordLabel() : m.recordId(),
                    m.amount() == null ? "" : m.amount().currencyCode() + " " + m.amount().amount(),
                    m.outcome().charAt(0) + m.outcome().substring(1).toLowerCase(java.util.Locale.ROOT),
                    m.reason() == null ? "" : m.reason());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String name = "activity-" + DateTimeFormatter.ISO_LOCAL_DATE.format(f.from().atZone(DAR)) + "-to-"
            + DateTimeFormatter.ISO_LOCAL_DATE.format(f.to().atZone(DAR)) + ".csv";
        return FileDownloadResponses.fileResponse(out.toString().getBytes(StandardCharsets.UTF_8),
            "text/csv;charset=UTF-8", name, name);
    }

    private static String who(ActivityQueries.Line l) {
        if ("SYSTEM".equals(l.actorKind())) return "System";
        if ("UNRECORDED".equals(l.actorKind())) return "Not recorded";
        String name = l.actorName() == null || l.actorName().isBlank() ? "Someone" : l.actorName();
        return name + " (" + KIND.getOrDefault(l.actorKind(), "staff") + ")";
    }

    /** The last seven days unless asked otherwise. */
    private static ActivityQueries.Filters filters(String person, String recordType, String recordId, String q,
                                                   String kind, String outcome, Instant from, Instant to) {
        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minus(Duration.ofDays(7)) : from;
        return new ActivityQueries.Filters(person, recordType, recordId, q, kind, outcome, start, end);
    }
}
