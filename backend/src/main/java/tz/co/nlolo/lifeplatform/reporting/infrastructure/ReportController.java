package tz.co.nlolo.lifeplatform.reporting.infrastructure;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.FileDownloadResponses;
import tz.co.nlolo.lifeplatform.reporting.api.RebuildStatusView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportingApi;

import java.util.List;
import java.util.Map;

/**
 * The /reports surface. FINANCE_APPROVER reads reports too (user decision, 2026-10-11): no reporting role
 * exists, and the people who approve journals are the people who read the management figures. Rebuilding
 * rewrites every row the tenant has, so it is ADMIN only.
 */
@RestController
public class ReportController {


    private final ReportingApi reportingApi;
    private final ReportExporter exporter;

    public ReportController(ReportingApi reportingApi, ReportExporter exporter) {
        this.reportingApi = reportingApi;
        this.exporter = exporter;
    }

    @GetMapping("/reports/catalogue")
    @PreAuthorize("@access.can('reports.view')")
    public List<ReportDefinitionView> catalogue() {
        return reportingApi.catalogue();
    }

    @GetMapping("/reports/{reportKey}")
    @PreAuthorize("@access.can('reports.view')")
    public ReportResult run(@PathVariable String reportKey, @RequestParam Map<String, String> query) {
        return reportingApi.run(reportKey, ReportFilterParams.parse(query));
    }

    @GetMapping("/reports/{reportKey}/export")
    @PreAuthorize("@access.can('reports.view')")
    public ResponseEntity<byte[]> export(@PathVariable String reportKey, @RequestParam Map<String, String> query) {
        ReportExporter.Export export = exporter.export(reportingApi.run(reportKey, ReportFilterParams.parse(query)),
            query.get("format"));
        return FileDownloadResponses.fileResponse(export.bytes(), export.contentType(), export.fileName(), export.fileName());
    }

    @PostMapping("/reports/rebuild")
    @PreAuthorize("@access.can('reports.rebuild')")
    public ResponseEntity<RebuildStatusView> rebuild(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(reportingApi.startRebuild(jwt.getSubject()));
    }

    @GetMapping("/reports/rebuild/status")
    @PreAuthorize("@access.can('reports.view')")
    public RebuildStatusView rebuildStatus() {
        return reportingApi.rebuildStatus();
    }
}
