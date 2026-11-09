package tz.co.nlolo.lifeplatform.reporting.infrastructure;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.FileDownloadResponses;
import tz.co.nlolo.lifeplatform.reporting.api.NewSolvencyFactorSet;
import tz.co.nlolo.lifeplatform.reporting.api.RebuildStatusView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportingApi;
import tz.co.nlolo.lifeplatform.reporting.api.SolvencyFactorSetView;

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

    /** What one line is made of -- the computation tree (2026-11-09). The same filters as the report. */
    @GetMapping("/reports/{reportKey}/parts")
    @PreAuthorize("@access.can('reports.view')")
    public tz.co.nlolo.lifeplatform.reporting.api.ReportParts parts(@PathVariable String reportKey,
            @RequestParam String lineId, @RequestParam(defaultValue = "0") int page,
            @RequestParam Map<String, String> query) {
        return reportingApi.parts(reportKey, lineId, ReportFilterParams.parse(query), page);
    }

    @GetMapping("/reports/{reportKey}/export")
    @PreAuthorize("@access.can('reports.view')")
    public ResponseEntity<byte[]> export(@PathVariable String reportKey, @RequestParam Map<String, String> query,
                                         @AuthenticationPrincipal Jwt jwt) {
        // Who prepared it, on the printed report (2026-11-09, report formatting).
        String preparedBy = jwt == null ? null : jwt.getClaimAsString("name");
        if ((preparedBy == null || preparedBy.isBlank()) && jwt != null) preparedBy = jwt.getClaimAsString("preferred_username");
        ReportExporter.Export export = exporter.export(reportingApi.run(reportKey, ReportFilterParams.parse(query)),
            query.get("format"), preparedBy);
        return FileDownloadResponses.fileResponse(export.bytes(), export.contentType(), export.fileName(), export.fileName());
    }

    /**
     * The solvency factors (2026-11-09): every version, newest first, ending with the built-in placeholder set. Mapped
     * under /reports/settings so it can never be read as a report key.
     */
    @GetMapping("/reports/settings/solvency-factors")
    @PreAuthorize("@access.can('reports.view')")
    public List<SolvencyFactorSetView> solvencyFactorSets() {
        return reportingApi.solvencyFactorSets();
    }

    /** A new version of the solvency factors. Versions are never edited: a past month re-runs on its own. */
    @PostMapping("/reports/settings/solvency-factors")
    @PreAuthorize("@access.can('reports.solvency-factors')")
    public ResponseEntity<SolvencyFactorSetView> publishSolvencyFactorSet(@RequestBody NewSolvencyFactorSet set,
                                                                          @AuthenticationPrincipal Jwt jwt) {
        String by = jwt.getClaimAsString("name");
        if (by == null || by.isBlank()) by = jwt.getClaimAsString("preferred_username");
        if (by == null || by.isBlank()) by = jwt.getSubject();
        return ResponseEntity.status(HttpStatus.CREATED).body(reportingApi.publishSolvencyFactorSet(set, by));
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
