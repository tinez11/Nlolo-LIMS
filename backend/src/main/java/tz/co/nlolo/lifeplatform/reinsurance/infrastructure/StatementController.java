package tz.co.nlolo.lifeplatform.reinsurance.infrastructure;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceApi;
import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceValidationException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The quarterly reinsurance statement (IFRS 17 I3d): finance prepares, completes, submits and withdraws; a
 * FINANCE_APPROVER -- never the preparer -- approves or rejects. The reinsurer's statement is uploaded to the document
 * store and recorded on the draft. Amounts travel as decimal strings, as every reinsurance amount does.
 */
@RestController
public class StatementController {

    private static final String FINANCE = "hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))";
    private static final String APPROVER = "hasRole('REALM_STAFF') and hasRole('FINANCE_APPROVER')";

    public record PrepareStatementRequest(String quarter) {}

    public record UpdateStatementRequest(String fundsWithheld, String profitCommission, String reason) {}

    public record StatementRejectionRequest(String reason) {}

    private final ReinsuranceApi api;
    private final DocumentApi documents;

    public StatementController(ReinsuranceApi api, DocumentApi documents) {
        this.api = api;
        this.documents = documents;
    }

    /**
     * The document store's owner of a statement's file. Short, like claims' "claim:{id}": the column is VARCHAR(50),
     * and "statement:" plus a UUID is 46 (I4's "manual-journal:{id}", 51, failed every upload).
     */
    static String ownerContextOf(UUID id) {
        return "statement:" + id;
    }

    @GetMapping("/reinsurance-statements")
    @PreAuthorize(FINANCE + " or " + APPROVER)
    public List<StatementResponseDto> list(@RequestParam(required = false) String status,
                                           @RequestParam(required = false) UUID treatyId) {
        return api.listStatements(status, treatyId).stream().map(StatementResponseDto::from).toList();
    }

    @PostMapping("/treaties/{treatyId}/statements")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(FINANCE)
    public StatementResponseDto prepare(@PathVariable UUID treatyId, @RequestBody PrepareStatementRequest body,
                                        @AuthenticationPrincipal Jwt jwt) {
        return StatementResponseDto.from(api.prepareStatement(treatyId, body == null ? null : body.quarter(),
            jwt.getSubject()));
    }

    @GetMapping("/reinsurance-statements/{id}")
    @PreAuthorize(FINANCE + " or " + APPROVER)
    public StatementResponseDto get(@PathVariable UUID id) {
        return StatementResponseDto.from(api.getStatement(id));
    }

    @PutMapping("/reinsurance-statements/{id}")
    @PreAuthorize(FINANCE)
    public StatementResponseDto update(@PathVariable UUID id, @RequestBody UpdateStatementRequest body,
                                       @AuthenticationPrincipal Jwt jwt) {
        return StatementResponseDto.from(api.updateStatement(id, decimal(body.fundsWithheld(), "fundsWithheld"),
            decimal(body.profitCommission(), "profitCommission"), body.reason(), jwt.getSubject()));
    }

    @PostMapping(value = "/reinsurance-statements/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(FINANCE)
    public StatementResponseDto attach(@PathVariable UUID id, @RequestPart("file") MultipartFile file,
                                       @AuthenticationPrincipal Jwt jwt) {
        // 404, or 409 when this person may not change it -- before anything is stored, so a refusal leaves no orphan.
        api.requireStatementEditable(id, jwt.getSubject());
        String contentType;
        try {
            contentType = AllowedDocumentContentTypes.normalizeOrThrow(file.getContentType());
        } catch (IllegalArgumentException e) {
            throw new ReinsuranceValidationException(e.getMessage());
        }
        String ref;
        try {
            ref = documents.upload(ownerContextOf(id), DocumentType.REINSURANCE_STATEMENT, jwt.getSubject(),
                file.getInputStream(), file.getSize(), contentType, file.getOriginalFilename());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the uploaded statement", e);
        }
        return StatementResponseDto.from(api.attachStatementDocument(id, ref, jwt.getSubject()));
    }

    @PostMapping("/reinsurance-statements/{id}/submission")
    @PreAuthorize(FINANCE)
    public StatementResponseDto submit(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return StatementResponseDto.from(api.submitStatement(id, jwt.getSubject()));
    }

    @PostMapping("/reinsurance-statements/{id}/withdrawal")
    @PreAuthorize(FINANCE)
    public StatementResponseDto withdraw(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return StatementResponseDto.from(api.withdrawStatement(id, jwt.getSubject()));
    }

    @PostMapping("/reinsurance-statements/{id}/approval")
    @PreAuthorize(APPROVER)
    public StatementResponseDto approve(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return StatementResponseDto.from(api.approveStatement(id, jwt.getSubject()));
    }

    @PostMapping("/reinsurance-statements/{id}/rejection")
    @PreAuthorize(APPROVER)
    public StatementResponseDto reject(@PathVariable UUID id, @RequestBody(required = false) StatementRejectionRequest body,
                                       @AuthenticationPrincipal Jwt jwt) {
        return StatementResponseDto.from(api.rejectStatement(id, body == null ? null : body.reason(), jwt.getSubject()));
    }

    private static BigDecimal decimal(String value, String field) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            throw new ReinsuranceValidationException(field + " must be a decimal amount, got '" + value + "'");
        }
    }
}
