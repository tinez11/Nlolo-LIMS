package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tz.co.nlolo.lifeplatform.AllowedDocumentContentTypes;
import tz.co.nlolo.lifeplatform.document.api.DocumentApi;
import tz.co.nlolo.lifeplatform.document.api.DocumentType;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingValidationException;
import tz.co.nlolo.lifeplatform.finaccounting.api.ManualJournalApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.ManualJournalApi.JournalTemplateView;
import tz.co.nlolo.lifeplatform.finaccounting.api.ManualJournalApi.ManualJournalView;
import tz.co.nlolo.lifeplatform.finaccounting.api.ManualJournalInput;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;

/**
 * Manual journals (IFRS 17 I4): finance prepares, submits, withdraws and reverses; a FINANCE_APPROVER -- never the
 * preparer -- approves or rejects. A supporting document is uploaded to the document store and recorded on the
 * draft; a large journal's lines can come from a CSV or Excel file.
 */
@RestController
public class ManualJournalController {

    private static final String FINANCE = "hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))";
    private static final String APPROVER = "hasRole('REALM_STAFF') and hasRole('FINANCE_APPROVER')";

    public record RejectionRequest(String reason) {}

    public record TemplateRequest(String name, String description, List<ManualJournalInput.Line> lines, String reasonCode) {}

    private final ManualJournalApi api;
    private final DocumentApi documents;

    public ManualJournalController(ManualJournalApi api, DocumentApi documents) {
        this.api = api;
        this.documents = documents;
    }

    @GetMapping("/finance/manual-journals")
    @PreAuthorize(FINANCE + " or " + APPROVER)
    public List<ManualJournalView> list(@RequestParam(required = false) String status,
                                        @RequestParam(required = false) String period,
                                        @RequestParam(required = false) String preparer) {
        return api.list(status, period, preparer);
    }

    @PostMapping("/finance/manual-journals")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(FINANCE)
    public ManualJournalView create(@RequestBody ManualJournalInput input, @AuthenticationPrincipal Jwt jwt) {
        return api.create(input, jwt.getSubject());
    }

    @GetMapping("/finance/manual-journals/{id}")
    @PreAuthorize(FINANCE + " or " + APPROVER)
    public ManualJournalView get(@PathVariable UUID id) {
        return api.get(id);
    }

    @PutMapping("/finance/manual-journals/{id}")
    @PreAuthorize(FINANCE)
    public ManualJournalView update(@PathVariable UUID id, @RequestBody ManualJournalInput input,
                                    @AuthenticationPrincipal Jwt jwt) {
        return api.update(id, input, jwt.getSubject());
    }

    @PostMapping("/finance/manual-journals/{id}/submission")
    @PreAuthorize(FINANCE)
    public ManualJournalView submit(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return api.submit(id, jwt.getSubject());
    }

    @PostMapping("/finance/manual-journals/{id}/withdrawal")
    @PreAuthorize(FINANCE)
    public ManualJournalView withdraw(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return api.withdraw(id, jwt.getSubject());
    }

    @PostMapping("/finance/manual-journals/{id}/approval")
    @PreAuthorize(APPROVER)
    public ManualJournalView approve(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return api.approve(id, jwt.getSubject());
    }

    @PostMapping("/finance/manual-journals/{id}/rejection")
    @PreAuthorize(APPROVER)
    public ManualJournalView reject(@PathVariable UUID id, @RequestBody(required = false) RejectionRequest body,
                                    @AuthenticationPrincipal Jwt jwt) {
        return api.reject(id, body == null ? null : body.reason(), jwt.getSubject());
    }

    @PostMapping("/finance/manual-journals/{id}/reversal")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(FINANCE)
    public ManualJournalView reverse(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return api.reverse(id, jwt.getSubject());
    }

    @PostMapping(value = "/finance/manual-journals/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(FINANCE)
    public ManualJournalView attach(@PathVariable UUID id, @RequestPart("file") MultipartFile file,
                                    @AuthenticationPrincipal Jwt jwt) {
        // 404, or 409 when this person may not change the draft -- before anything is stored, so a refused
        // attachment leaves no orphan in the document store.
        api.requireEditable(id, jwt.getSubject());
        String contentType;
        try {
            contentType = AllowedDocumentContentTypes.normalizeOrThrow(file.getContentType());
        } catch (IllegalArgumentException e) {
            throw new FinaccountingValidationException(e.getMessage());
        }
        String ref;
        try {
            ref = documents.upload("manual-journal:" + id, DocumentType.JOURNAL_SUPPORT, jwt.getSubject(),
                file.getInputStream(), file.getSize(), contentType, file.getOriginalFilename());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the uploaded document", e);
        }
        return api.attachDocument(id, ref, jwt.getSubject());
    }

    @DeleteMapping("/finance/manual-journals/{id}/documents/{documentRef}")
    @PreAuthorize(FINANCE)
    public ManualJournalView detach(@PathVariable UUID id, @PathVariable String documentRef,
                                    @AuthenticationPrincipal Jwt jwt) {
        return api.detachDocument(id, documentRef, jwt.getSubject());
    }

    @PostMapping(value = "/finance/manual-journals/{id}/lines", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(FINANCE)
    public ManualJournalView uploadLines(@PathVariable UUID id, @RequestPart("file") MultipartFile file,
                                         @AuthenticationPrincipal Jwt jwt) {
        try {
            return api.uploadLines(id, file.getBytes(), file.getOriginalFilename(), jwt.getSubject());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the uploaded lines", e);
        }
    }

    @GetMapping("/finance/journal-templates")
    @PreAuthorize(FINANCE + " or " + APPROVER)
    public List<JournalTemplateView> templates() {
        return api.templates();
    }

    @PostMapping("/finance/journal-templates")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(FINANCE)
    public JournalTemplateView saveTemplate(@RequestBody TemplateRequest body, @AuthenticationPrincipal Jwt jwt) {
        return api.saveTemplate(body.name(), body.description(), body.lines(), body.reasonCode(), jwt.getSubject());
    }
}
