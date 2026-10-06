package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tz.co.nlolo.lifeplatform.AllowedDocumentContentTypes;
import tz.co.nlolo.lifeplatform.document.api.DocumentApi;
import tz.co.nlolo.lifeplatform.document.api.DocumentType;
import tz.co.nlolo.lifeplatform.finaccounting.api.EngineExtractView;
import tz.co.nlolo.lifeplatform.finaccounting.api.EngineRunView;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingValidationException;
import tz.co.nlolo.lifeplatform.finaccounting.application.EngineExtracts;
import tz.co.nlolo.lifeplatform.finaccounting.application.EngineRuns;
import tz.co.nlolo.lifeplatform.finaccounting.domain.EngineResultsTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;

/**
 * The IFRS 17 engine period cycle (IFRS 17 I5a): finance makes a closing period's extract and uploads the engine's
 * results; a FINANCE_APPROVER who did not upload them approves -- with the appointed actuary's sign-off reference and
 * report -- which posts them through 9160 and reconciles the ledger to the engine; a difference is explained by one
 * person and accepted by another. The same endpoints serve the console and, later, an engine calling in.
 */
@RestController
public class EngineController {

    private static final String FINANCE = "hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))";
    private static final String APPROVER = "hasRole('REALM_STAFF') and hasRole('FINANCE_APPROVER')";
    private static final MediaType XLSX = MediaType.parseMediaType(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    public record RejectionRequest(String reason) {}

    public record ExplanationRequest(String text) {}

    private final EngineExtracts extracts;
    private final EngineRuns runs;
    private final DocumentApi documents;

    public EngineController(EngineExtracts extracts, EngineRuns runs, DocumentApi documents) {
        this.extracts = extracts;
        this.runs = runs;
        this.documents = documents;
    }

    /** The document store's owner of a run's report: VARCHAR(50) -- "enginerun:" plus a UUID is 46. */
    static String reportOwnerOf(UUID runId) {
        return "enginerun:" + runId;
    }

    // ---- extracts (step 6) ----

    @PostMapping("/ifrs17/periods/{period}/extracts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(FINANCE)
    public EngineExtractView createExtract(@PathVariable String period, @AuthenticationPrincipal Jwt jwt) {
        return extracts.create(period, jwt.getSubject());
    }

    @GetMapping("/ifrs17/periods/{period}/extracts")
    @PreAuthorize(FINANCE + " or " + APPROVER)
    public List<EngineExtractView> listExtracts(@PathVariable String period) {
        return extracts.list(period);
    }

    @GetMapping("/ifrs17/extracts/{id}")
    @PreAuthorize(FINANCE + " or " + APPROVER)
    public EngineExtractView getExtract(@PathVariable UUID id) {
        return extracts.get(id);
    }

    /** {@code format} xlsx (the workbook) or csv (one {@code sheet}: cash-flows, balances or policies). */
    @GetMapping("/ifrs17/extracts/{id}/download")
    @PreAuthorize(FINANCE + " or " + APPROVER)
    public ResponseEntity<byte[]> download(@PathVariable UUID id, @RequestParam(defaultValue = "xlsx") String format,
                                           @RequestParam(required = false) String sheet) {
        EngineExtractView view = extracts.get(id);
        byte[] body = extracts.render(id, format, sheet);
        boolean csv = "csv".equalsIgnoreCase(format);
        String name = "ifrs17-extract-" + view.period() + "-" + view.number()
            + (csv ? "-" + (sheet == null ? "cash-flows" : sheet) + ".csv" : ".xlsx");
        return file(body, csv ? MediaType.parseMediaType("text/csv") : XLSX, name);
    }

    @GetMapping("/ifrs17/results-template")
    @PreAuthorize(FINANCE + " or " + APPROVER)
    public ResponseEntity<byte[]> template() {
        return file(EngineResultsTemplate.blank(), XLSX, "ifrs17-engine-results-template.xlsx");
    }

    // ---- runs (step 7) ----

    @PostMapping(value = "/ifrs17/engine-runs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(FINANCE)
    public EngineRunView upload(@RequestPart("file") MultipartFile file, @AuthenticationPrincipal Jwt jwt) {
        try {
            return runs.upload(file.getBytes(), file.getOriginalFilename(), jwt.getSubject());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the uploaded results", e);
        }
    }

    @GetMapping("/ifrs17/engine-runs")
    @PreAuthorize(FINANCE + " or " + APPROVER)
    public List<EngineRunView> listRuns(@RequestParam(required = false) String period,
                                        @RequestParam(required = false) String status) {
        return runs.list(period, status);
    }

    @GetMapping("/ifrs17/engine-runs/{id}")
    @PreAuthorize(FINANCE + " or " + APPROVER)
    public EngineRunView getRun(@PathVariable UUID id) {
        return runs.get(id);
    }

    /** The decider is checked before the report is stored, so a refusal leaves no orphan in the document store. */
    @PostMapping(value = "/ifrs17/engine-runs/{id}/approval", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(APPROVER)
    public EngineRunView approve(@PathVariable UUID id, @RequestParam("signOffReference") String signOffReference,
                                 @RequestPart("report") MultipartFile report, @AuthenticationPrincipal Jwt jwt) {
        runs.requireDecidable(id, jwt.getSubject());
        String contentType;
        try {
            contentType = AllowedDocumentContentTypes.normalizeOrThrow(report.getContentType());
        } catch (IllegalArgumentException e) {
            throw new FinaccountingValidationException(e.getMessage());
        }
        String ref;
        try {
            ref = documents.upload(reportOwnerOf(id), DocumentType.ACTUARIAL_REPORT, jwt.getSubject(),
                report.getInputStream(), report.getSize(), contentType, report.getOriginalFilename());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the actuary's report", e);
        }
        return runs.approve(id, signOffReference, ref, jwt.getSubject());
    }

    @PostMapping("/ifrs17/engine-runs/{id}/rejection")
    @PreAuthorize(APPROVER)
    public EngineRunView reject(@PathVariable UUID id, @RequestBody(required = false) RejectionRequest body,
                                @AuthenticationPrincipal Jwt jwt) {
        return runs.reject(id, body == null ? null : body.reason(), jwt.getSubject());
    }

    // ---- the reconciliation's exceptions ----

    @PostMapping("/ifrs17/engine-runs/{id}/exceptions/{group}/{figure}/explanation")
    @PreAuthorize(FINANCE)
    public EngineRunView explain(@PathVariable UUID id, @PathVariable String group, @PathVariable String figure,
                                 @RequestBody ExplanationRequest body, @AuthenticationPrincipal Jwt jwt) {
        return runs.explain(id, group, figure, body == null ? null : body.text(), jwt.getSubject());
    }

    @PostMapping("/ifrs17/engine-runs/{id}/exceptions/{group}/{figure}/acceptance")
    @PreAuthorize(APPROVER)
    public EngineRunView accept(@PathVariable UUID id, @PathVariable String group, @PathVariable String figure,
                                @AuthenticationPrincipal Jwt jwt) {
        return runs.accept(id, group, figure, jwt.getSubject());
    }

    private static ResponseEntity<byte[]> file(byte[] body, MediaType type, String name) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
            .contentType(type)
            .body(body);
    }
}
