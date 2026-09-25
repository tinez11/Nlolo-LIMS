package tz.co.nlolo.lifeplatform.policy.infrastructure;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tz.co.nlolo.lifeplatform.AllowedDocumentContentTypes;
import tz.co.nlolo.lifeplatform.policy.api.ExitApi;
import tz.co.nlolo.lifeplatform.policy.api.ExitRowView;
import tz.co.nlolo.lifeplatform.policy.api.ExitSubmissionView;
import tz.co.nlolo.lifeplatform.policy.api.InvalidPolicyStateException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;

/**
 * The monthly exits file: the loans that ended, and the cover that comes off with them.
 *
 * <p><b>This controller exists because the feature had none.</b> Plan 3 built the exits file
 * completely — parser, submission state machine, propose-then-accept, pro-rata refund, commission
 * clawback — and shipped it unexposed. {@code ExitApi} and {@code ExitApiImpl} were the only two
 * files in the entire backend that named it, so the file a lender sends every month to take
 * repaid, refinanced, written-off and cancelled loans off cover could not be run by any HTTP
 * client at all. Everything below is a surface over work that already existed and was unreachable.
 *
 * <p><b>A deliberate mirror of {@link EnrolmentController}, not a merged submissions
 * controller.</b> The two flows share a shape and not a meaning: one puts people on cover and the
 * other takes them off, they validate different rows, and they have opposite money consequences —
 * an enrolment charges a premium, an exit may refund one and claw back the commission it earned.
 * A single controller parameterised by kind would turn the next difference between them into a
 * branch instead of a separate file.
 *
 * <p><b>Staff only in this build</b>, for the same reason the enrolment side is: the design has
 * the lender uploading their own file, and the backend is ready for it, but that path needs a
 * public PKCE client on the {@code customers} realm, real CORS and a deployed same-origin story.
 * All three are on the production-readiness gate and none belongs inside a product build.
 *
 * <p>{@code submittedBy} and {@code acceptedBy} come from the token and never from the request
 * body: the two-person rule is only as real as those identities.
 */
@RestController
public class ExitController {

    private static final String XLSX =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ExitApi exitApi;

    public ExitController(ExitApi exitApi) {
        this.exitApi = exitApi;
    }

    /**
     * CSV or XLSX, the same as the enrolment upload.
     *
     * <p><b>This refused a workbook until a real lender's month proved the refusal wrong.</b> The
     * argument for CSV-only was that an exits file is a short list generated from a loan system,
     * so a second format would buy a second set of numeric-coercion traps for nobody. The traps
     * are real; what the argument missed is that this file carries a DATE, and a CSV cannot
     * survive Excel -- it rewrites dates on open and again on save. Two enrolment files were
     * refused entire that way before the enrolment side moved to a workbook, and the exits side
     * was exposed to exactly the same failure, having simply not been reached yet.
     *
     * <p>The coercion traps were already solved anyway: {@link XlsxToCsv} is what the enrolment
     * path has read workbooks through since it was written, and {@code ExitApiImpl} has always
     * called it. Only this guard refused one.
     */
    @PostMapping(value = "/credit-life-schemes/{policyNumber}/exits",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<ExitSubmissionView> submit(@PathVariable String policyNumber,
                                                      @RequestPart("file") MultipartFile file,
                                                      @AuthenticationPrincipal Jwt jwt) {
        String contentType;
        try {
            contentType = AllowedDocumentContentTypes.normalizeOrThrow(file.getContentType());
        } catch (IllegalArgumentException e) {
            throw new InvalidPolicyStateException(e.getMessage());
        }
        if (!"text/csv".equals(contentType)
                && !XLSX.equals(contentType)
                && !"application/octet-stream".equals(contentType)) {
            // octet-stream is tolerated for the same reason the enrolment upload tolerates it: a
            // browser posting either format often sends it, and the service reads the real shape
            // from the bytes anyway.
            throw new InvalidPolicyStateException(
                "An exits file must be a CSV or an XLSX; this file is " + contentType);
        }
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(exitApi.submit(
                policyNumber, file.getInputStream(), file.getOriginalFilename(), jwt.getSubject()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the uploaded exits file", e);
        }
    }

    /** The scheme's exits history, newest first. Without it a console page opening on a scheme
     * cannot reach the report of a file sent last month. */
    @GetMapping("/credit-life-schemes/{policyNumber}/exits")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public List<ExitSubmissionView> listSubmissions(@PathVariable String policyNumber) {
        return exitApi.listSubmissions(policyNumber);
    }

    @GetMapping("/credit-life-schemes/{policyNumber}/exits/{submissionId}")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ExitSubmissionView getSubmission(@PathVariable String policyNumber,
                                             @PathVariable UUID submissionId) {
        return exitApi.getSubmission(submissionId);
    }

    @GetMapping("/credit-life-schemes/{policyNumber}/exits/{submissionId}/rows")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public List<ExitRowResponseDto> listRows(@PathVariable String policyNumber,
                                       @PathVariable UUID submissionId) {
        return ExitRowResponseDto.from(exitApi.listRows(submissionId));
    }

    /**
     * A second person turns the judged rows into actual exits — and into the refunds and
     * clawbacks that follow them. {@code ExitApiImpl} refuses the submitter, and the actor comes
     * from the token so that refusal has something real to compare.
     */
    @PostMapping("/credit-life-schemes/{policyNumber}/exits/{submissionId}/acceptance")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ExitSubmissionView accept(@PathVariable String policyNumber,
                                      @PathVariable UUID submissionId,
                                      @AuthenticationPrincipal Jwt jwt) {
        return exitApi.accept(submissionId, jwt.getSubject());
    }

    @PostMapping("/credit-life-schemes/{policyNumber}/exits/{submissionId}/withdrawal")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ExitSubmissionView withdraw(@PathVariable String policyNumber,
                                        @PathVariable UUID submissionId,
                                        @AuthenticationPrincipal Jwt jwt) {
        return exitApi.withdraw(submissionId, jwt.getSubject());
    }

    /**
     * The report that goes back to the lender.
     *
     * <p>Served as a download rather than JSON for the same reason the enrolment report is: it is
     * read in the spreadsheet the file came from, beside the rows it judges.
     */
    @GetMapping(value = "/credit-life-schemes/{policyNumber}/exits/{submissionId}/report",
                produces = "text/csv")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<String> report(@PathVariable String policyNumber,
                                          @PathVariable UUID submissionId) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"exits-report-" + submissionId + ".csv\"")
            .contentType(MediaType.valueOf("text/csv"))
            .body(exitApi.renderReport(submissionId));
    }

    /**
     * The exits file this scheme's lender fills in, with an example row.
     *
     * <p>Same origin as the enrolment template: {@code ExitCsvParser} refused files with "Use the
     * template at credit-life-exits-sample.csv" while nothing on the platform served that file.
     *
     * <p>Scheme-scoped like its sibling, and for the extra reason that the example row has to
     * carry this scheme's own reference prefix to be worth anything. See
     * {@code ExitApi.renderTemplate} for why that example names a member who cannot exist.
     *
     * <p>The four reasons a lender may state are not in the file — a CSV cannot carry them — so
     * the console lists them beside this download. A fifth, CLAIM_SETTLED, exists and is
     * deliberately not offered: only the claim path may write it.
     */
    @GetMapping(value = "/credit-life-schemes/{policyNumber}/exits-template", produces = "text/csv")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<String> exitsTemplate(@PathVariable String policyNumber) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"exits-template-" + policyNumber + ".csv\"")
            .contentType(MediaType.valueOf("text/csv"))
            .body(exitApi.renderTemplate(policyNumber));
    }

    /**
     * The same exits file as a spreadsheet, and the one to send a lender who works in Excel.
     *
     * <p>See {@code ExitApi.renderTemplateXlsx}: a CSV cannot survive Excel, and this file carries
     * a date. The workbook also carries the instructions, which a CSV has nowhere to put.
     */
    @GetMapping(value = "/credit-life-schemes/{policyNumber}/exits-template.xlsx", produces = XLSX)
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<byte[]> exitsTemplateXlsx(@PathVariable String policyNumber) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"exits-template-" + policyNumber + ".xlsx\"")
            .contentType(MediaType.valueOf(XLSX))
            .body(exitApi.renderTemplateXlsx(policyNumber));
    }
}
