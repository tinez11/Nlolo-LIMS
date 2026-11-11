package tz.co.nlolo.lifeplatform.reporting.infrastructure;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.FileDownloadResponses;
import tz.co.nlolo.lifeplatform.reporting.api.CollectionFigures;
import tz.co.nlolo.lifeplatform.reporting.api.FollowUpPage;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportingApi;
import tz.co.nlolo.lifeplatform.reporting.api.ReportingApi.FollowUpQuery;
import tz.co.nlolo.lifeplatform.reporting.domain.CivilDates;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Premiums to follow up (2026-11-11, analytics plan 1): staff with collections see the company's; an agent sees their
 * team's, and can download it for a hard copy (the user). Each download is recorded: who, which list, how many rows.
 */
@RestController
public class FollowUpController {

    private final ReportingApi reportingApi;
    private final ReportExporter exporter;

    public FollowUpController(ReportingApi reportingApi, ReportExporter exporter) {
        this.reportingApi = reportingApi;
        this.exporter = exporter;
    }

    @GetMapping("/collections/follow-up")
    @PreAuthorize("@access.canAny('collections.view', 'collections.manage')")
    public FollowUpPage followUp(@RequestParam(defaultValue = "OVERDUE") String view,
                                 @RequestParam(defaultValue = "7") int soonDays,
                                 @RequestParam(required = false) String lateBand,
                                 @RequestParam(required = false) UUID agentId,
                                 @RequestParam(required = false) String branchCode,
                                 @RequestParam(required = false) UUID productId,
                                 @RequestParam(required = false) String search,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "50") int pageSize) {
        return reportingApi.followUp(new FollowUpQuery(view, soonDays, lateBand, agentId, branchCode, productId, search,
            page, pageSize));
    }

    @GetMapping("/collections/follow-up/export")
    @PreAuthorize("@access.canAny('collections.view', 'collections.manage')")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "OVERDUE") String view,
                                         @RequestParam(defaultValue = "7") int soonDays,
                                         @RequestParam(required = false) String lateBand,
                                         @RequestParam(required = false) UUID agentId,
                                         @RequestParam(required = false) String branchCode,
                                         @RequestParam(required = false) UUID productId,
                                         @RequestParam(required = false) String search,
                                         @RequestParam String format,
                                         @AuthenticationPrincipal Jwt jwt) {
        FollowUpQuery query = new FollowUpQuery(view, soonDays, lateBand, agentId, branchCode, productId, search, 0, 0);
        return download("Premiums to follow up", reportingApi.followUpReport(query, null), format, jwt);
    }

    @GetMapping("/agents/me/follow-up")
    @PreAuthorize("hasRole('REALM_AGENTS')")
    public FollowUpPage myFollowUp(@RequestParam(defaultValue = "OVERDUE") String view,
                                   @RequestParam(defaultValue = "7") int soonDays,
                                   @RequestParam(required = false) String lateBand,
                                   @RequestParam(required = false) String search,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "50") int pageSize,
                                   @AuthenticationPrincipal Jwt jwt) {
        return reportingApi.followUpForAgentParty(agentParty(jwt),
            new FollowUpQuery(view, soonDays, lateBand, null, null, null, search, page, pageSize));
    }

    @GetMapping("/agents/me/follow-up/export")
    @PreAuthorize("hasRole('REALM_AGENTS')")
    public ResponseEntity<byte[]> myExport(@RequestParam(defaultValue = "OVERDUE") String view,
                                           @RequestParam(defaultValue = "7") int soonDays,
                                           @RequestParam(required = false) String lateBand,
                                           @RequestParam(required = false) String search,
                                           @RequestParam String format,
                                           @AuthenticationPrincipal Jwt jwt) {
        FollowUpQuery query = new FollowUpQuery(view, soonDays, lateBand, null, null, null, search, 0, 0);
        return download("My premiums to follow up", reportingApi.followUpReport(query, agentParty(jwt)), format, jwt);
    }

    /** The collection figures (C1): premiums due in the period, paid, the rate; due today and overdue as of today. */
    @GetMapping("/collections/figures")
    @PreAuthorize("@access.canAny('collections.view', 'reports.view')")
    public CollectionFigures figures(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportingApi.collectionFigures(from, to, CivilDates.today(Clock.systemUTC()));
    }

    private ResponseEntity<byte[]> download(String list, ReportResult result, String format, Jwt jwt) {
        String preparedBy = preparedBy(jwt);
        ReportExporter.Export export = exporter.export(result, format, preparedBy);
        int rows = result.sections().isEmpty() ? 0 : result.sections().get(0).rows().size();
        reportingApi.listDownloaded(list, format, rows, preparedBy);
        return FileDownloadResponses.fileResponse(export.bytes(), export.contentType(), export.fileName(), export.fileName());
    }

    /** Who prepared it, as ReportController names them. */
    private static String preparedBy(Jwt jwt) {
        if (jwt == null) return null;
        String name = jwt.getClaimAsString("name");
        if (name == null || name.isBlank()) name = jwt.getClaimAsString("preferred_username");
        if (name == null || name.isBlank()) name = jwt.getSubject();
        return name;
    }

    /** The signed-in agent's party -- AgentBook.agentParty's rule and words. */
    private static UUID agentParty(Jwt jwt) {
        String partyId = jwt == null ? null : jwt.getClaimAsString("party_id");
        if (partyId == null) throw new AccessDeniedException("Agent token carries no party_id claim");
        return UUID.fromString(partyId);
    }
}
