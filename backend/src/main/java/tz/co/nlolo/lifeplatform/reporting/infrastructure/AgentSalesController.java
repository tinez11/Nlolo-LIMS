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
import tz.co.nlolo.lifeplatform.reporting.api.CompareWith;
import tz.co.nlolo.lifeplatform.reporting.api.PolicySold;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportingApi;
import tz.co.nlolo.lifeplatform.reporting.api.SalesTotals;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * An agent's own sales in money (2026-11-12, analytics plan 3): any dates, by month, quarter or year, the policies
 * behind each figure, and a file to keep. Scoped by the token's party, never by a parameter; {@code agentId} ("Me")
 * is honoured only inside the agent's team.
 */
@RestController
public class AgentSalesController {

    private final ReportingApi reportingApi;
    private final ReportExporter exporter;

    public AgentSalesController(ReportingApi reportingApi, ReportExporter exporter) {
        this.reportingApi = reportingApi;
        this.exporter = exporter;
    }

    @GetMapping("/agents/me/sales")
    @PreAuthorize("hasRole('REALM_AGENTS')")
    public List<SalesTotals> sales(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                   @RequestParam(defaultValue = "MONTH") String by,
                                   @RequestParam(required = false) UUID agentId,
                                   @AuthenticationPrincipal Jwt jwt) {
        return reportingApi.salesByPeriod(agentParty(jwt), agentId, from, to, by);
    }

    @GetMapping("/agents/me/sales/report")
    @PreAuthorize("hasRole('REALM_AGENTS')")
    public ReportResult report(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                               @RequestParam(defaultValue = "QUARTER") String by,
                               @RequestParam(defaultValue = "PRIOR_PERIOD") CompareWith compareWith,
                               @RequestParam(required = false) UUID agentId,
                               @AuthenticationPrincipal Jwt jwt) {
        return reportingApi.agentSalesReport(agentParty(jwt), agentId, from, to, by, compareWith);
    }

    @GetMapping("/agents/me/sales/export")
    @PreAuthorize("hasRole('REALM_AGENTS')")
    public ResponseEntity<byte[]> export(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(defaultValue = "QUARTER") String by,
                                         @RequestParam(defaultValue = "PRIOR_PERIOD") CompareWith compareWith,
                                         @RequestParam(required = false) UUID agentId,
                                         @RequestParam String format,
                                         @AuthenticationPrincipal Jwt jwt) {
        ReportResult result = reportingApi.agentSalesReport(agentParty(jwt), agentId, from, to, by, compareWith);
        String preparedBy = preparedBy(jwt);
        ReportExporter.Export export = exporter.export(result, format, preparedBy);
        reportingApi.listDownloaded("My sales", format,
            result.sections().isEmpty() ? 0 : result.sections().get(0).rows().size(), preparedBy);
        return FileDownloadResponses.fileResponse(export.bytes(), export.contentType(), export.fileName(), export.fileName());
    }

    @GetMapping("/agents/me/sales/policies")
    @PreAuthorize("hasRole('REALM_AGENTS')")
    public List<PolicySold> policies(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                     @RequestParam(required = false) UUID agentId,
                                     @AuthenticationPrincipal Jwt jwt) {
        return reportingApi.policiesSold(agentParty(jwt), agentId, from, to);
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
