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
import tz.co.nlolo.lifeplatform.policy.api.EnrolmentApi;
import tz.co.nlolo.lifeplatform.policy.api.EnrolmentRowView;
import tz.co.nlolo.lifeplatform.policy.api.EnrolmentSubmissionView;
import tz.co.nlolo.lifeplatform.policy.api.InvalidPolicyStateException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;

/**
 * Bulk enrolment of a lender's borrowers onto a credit-life scheme.
 *
 * <p><b>Staff only, deliberately, in this build.</b> The design has the lender uploading
 * their own file, and the backend is ready for it -- but that path additionally needs a
 * public PKCE client on the {@code customers} realm, real CORS, a deployed same-origin
 * story and a {@code party_id} that can name a CORPORATE party. All four are on the
 * production-readiness gate and none of them belongs inside a product build. Staff
 * upload on the lender's behalf until they land.
 *
 * <p>{@code submittedBy} and {@code acceptedBy} come from the token and never from the
 * request body: the two-person rule is only as real as those identities.
 */
@RestController
public class EnrolmentController {

    private static final String XLSX =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final EnrolmentApi enrolmentApi;

    public EnrolmentController(EnrolmentApi enrolmentApi) {
        this.enrolmentApi = enrolmentApi;
    }

    @PostMapping(value = "/credit-life-schemes/{policyNumber}/enrolments",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<EnrolmentSubmissionView> submit(@PathVariable String policyNumber,
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
            // octet-stream is tolerated because a browser posting either format often
            // sends it, and the service sniffs the real shape from the bytes anyway.
            // Anything else is a format the template does not define.
            throw new InvalidPolicyStateException("An enrolment schedule must be a CSV or an"
                + " XLSX in the template at credit-life-enrolment-sample.csv; this file is "
                + contentType);
        }
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(enrolmentApi.submit(
                policyNumber, file.getInputStream(), file.getOriginalFilename(), jwt.getSubject()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the uploaded schedule", e);
        }
    }

    @PostMapping("/credit-life-schemes/{policyNumber}/enrolments/{submissionId}/acceptance")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public EnrolmentSubmissionView accept(@PathVariable String policyNumber,
                                           @PathVariable UUID submissionId,
                                           @AuthenticationPrincipal Jwt jwt) {
        return enrolmentApi.accept(submissionId, jwt.getSubject());
    }

    @PostMapping("/credit-life-schemes/{policyNumber}/enrolments/{submissionId}/withdrawal")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public EnrolmentSubmissionView withdraw(@PathVariable String policyNumber,
                                             @PathVariable UUID submissionId,
                                             @AuthenticationPrincipal Jwt jwt) {
        return enrolmentApi.withdraw(submissionId, jwt.getSubject());
    }

    /**
     * The scheme's submission history, newest first.
     *
     * <p>Every other read on this controller needs a {@code submissionId}, which the upload flow
     * has and a console page opening on a scheme does not. Without this there is no way to reach
     * the report of a file sent last month.
     *
     * <p>{@code REALM_STAFF}, matching every other operation here: running a lender's file is
     * staff work, and what the lender themselves can see is the portal's problem rather than
     * this endpoint's.
     */
    @GetMapping("/credit-life-schemes/{policyNumber}/enrolments")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public List<EnrolmentSubmissionView> listSubmissions(@PathVariable String policyNumber) {
        return enrolmentApi.listSubmissions(policyNumber);
    }

    @GetMapping("/credit-life-schemes/{policyNumber}/enrolments/{submissionId}")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public EnrolmentSubmissionView getSubmission(@PathVariable String policyNumber,
                                                  @PathVariable UUID submissionId) {
        return enrolmentApi.getSubmission(submissionId);
    }

    @GetMapping("/credit-life-schemes/{policyNumber}/enrolments/{submissionId}/rows")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public List<EnrolmentRowView> listRows(@PathVariable String policyNumber,
                                            @PathVariable UUID submissionId) {
        return enrolmentApi.listRows(submissionId);
    }

    /**
     * The report that goes back to the lender.
     *
     * <p>Served as a download rather than JSON because it is read in the spreadsheet the
     * file came from, beside the rows it judges.
     */
    @GetMapping(value = "/credit-life-schemes/{policyNumber}/enrolments/{submissionId}/report",
                produces = "text/csv")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<String> report(@PathVariable String policyNumber,
                                          @PathVariable UUID submissionId) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"enrolment-report-" + submissionId + ".csv\"")
            .contentType(MediaType.valueOf("text/csv"))
            .body(enrolmentApi.renderReport(submissionId));
    }

    /**
     * The file this scheme's lender fills in, with one of their own borrowers already in it.
     *
     * <p><b>Three separate refusal messages already told people to "use the template at
     * credit-life-enrolment-sample.csv", and nothing served it.</b> That file lives in the
     * repository's spec folder, where no staff user and certainly no lender can reach it — so the
     * platform's own error messages named a document that existed only for us.
     *
     * <p><b>Scoped to the SCHEME, and it was product-scoped for one commit.</b> The columns are
     * identical for every lender, which made a product-wide blank look like the right shape. It
     * is not, for two reasons that only show up in use. A header row plus a page of prose is not
     * how anybody learns a file format — the borrower somebody typed into the set-up form, echoed
     * back in the file they are about to send, answers what the prose was trying to. And when the
     * lender's own portal lands, "the template" has to mean *their* scheme's; a product-scoped
     * path could not be served to a bank without first deciding which product they meant.
     */
    @GetMapping(value = "/credit-life-schemes/{policyNumber}/template", produces = "text/csv")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<String> enrolmentTemplate(@PathVariable String policyNumber) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"enrolment-template-" + policyNumber + ".csv\"")
            .contentType(MediaType.valueOf("text/csv"))
            .body(enrolmentApi.renderTemplate(policyNumber));
    }

    /**
     * The same template as a spreadsheet, and the one to send a lender who works in Excel.
     *
     * <p>See {@code EnrolmentApi.renderTemplateXlsx} for why: a CSV template cannot survive being
     * opened in Excel, and two real files were refused entire because of it.
     */
    @GetMapping(value = "/credit-life-schemes/{policyNumber}/template.xlsx",
                produces = XLSX)
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<byte[]> enrolmentTemplateXlsx(@PathVariable String policyNumber) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"enrolment-template-" + policyNumber + ".xlsx\"")
            .contentType(MediaType.valueOf(XLSX))
            .body(enrolmentApi.renderTemplateXlsx(policyNumber));
    }
}
