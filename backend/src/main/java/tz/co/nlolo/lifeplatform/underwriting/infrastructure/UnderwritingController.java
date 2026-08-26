package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseStatus;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseView;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/underwriting")
public class UnderwritingController {

    private final UnderwritingApi underwritingApi;

    public UnderwritingController(UnderwritingApi underwritingApi) {
        this.underwritingApi = underwritingApi;
    }

    @GetMapping("/cases")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<UnderwritingCaseSearchResponse> listCases(
            @RequestParam(required = false) UnderwritingCaseStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        Pageable pageable = PageRequest.of(page, Math.min(pageSize, 100), Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<UnderwritingCaseView> result = underwritingApi.listCases(status, pageable);
        return ResponseEntity.ok(UnderwritingCaseSearchResponse.from(result));
    }

    @PostMapping("/cases")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<UnderwritingCaseView> openCase(@Valid @RequestBody OpenCaseRequest request,
                                                          @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                          @AuthenticationPrincipal Jwt jwt) {
        // Idempotency-Key accepted but not yet enforced -- see plan Global Constraints;
        // real dedup registry lands with payment's idempotency work in M5.
        UnderwritingCaseView view = underwritingApi.openCase(request.applicantPartyId(), request.productId(), request.productVersionId(),
            new BigDecimal(request.sumAssured().amount()), request.sumAssured().currencyCode(), jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @GetMapping("/cases/{caseId}")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<UnderwritingCaseView> getCase(@PathVariable UUID caseId) {
        return ResponseEntity.ok(underwritingApi.getCase(caseId));
    }

    @PostMapping("/cases/{caseId}/assessments")
    @PreAuthorize("hasRole('UNDERWRITER')")
    public ResponseEntity<UnderwritingCaseView> submitAssessment(@PathVariable UUID caseId,
                                                                  @Valid @RequestBody SubmitAssessmentRequest request,
                                                                  @AuthenticationPrincipal Jwt jwt) {
        UnderwritingCaseView view = underwritingApi.submitAssessment(caseId, request.assessmentType(), request.findings(), request.riskScore(), jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @PostMapping("/cases/{caseId}/referral")
    @PreAuthorize("hasRole('UNDERWRITER')")
    public ResponseEntity<Void> referCase(@PathVariable UUID caseId) {
        underwritingApi.referToSeniorUnderwriter(caseId);
        return ResponseEntity.ok().build();
    }
}
