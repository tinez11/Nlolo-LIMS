package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tz.co.nlolo.lifeplatform.benefitpayout.api.BenefitPayoutApi;
import tz.co.nlolo.lifeplatform.benefitpayout.api.InstalmentStatus;

import java.util.List;
import java.util.UUID;

@RestController
public class BenefitPayoutController {

    /**
     * Money leaving the company is finance's, exactly as a commission payout is. Reviewing and
     * approving a payout both move real money, so neither is open to every staff role -- the gap
     * step 1's surrender approval had until it was closed.
     */
    static final String FINANCE = "hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))";

    private final BenefitPayoutApi api;

    public BenefitPayoutController(BenefitPayoutApi api) {
        this.api = api;
    }

    /** A whole policy's schedule. Readable by any staff member: it is the contract's own terms. */
    @GetMapping("/policies/{policyNumber}/payouts")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public List<PayoutInstalmentResponse> listForPolicy(@PathVariable String policyNumber) {
        return api.listForPolicy(policyNumber).stream().map(PayoutInstalmentResponse::from).toList();
    }

    @GetMapping("/payouts/{instalmentId}")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public PayoutInstalmentResponse get(@PathVariable UUID instalmentId) {
        return PayoutInstalmentResponse.from(api.getInstalment(instalmentId));
    }

    /**
     * The payouts register. Ordered by due date THEN id, because a bulk drain brings many
     * instalments due in the same instant and an unordered page would show one row twice and never
     * show another -- on a queue, that is a customer nobody pays.
     */
    @GetMapping("/payouts")
    @PreAuthorize(FINANCE)
    public PayoutInstalmentResponse.PageResponse search(@RequestParam(required = false) InstalmentStatus status,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int pageSize) {
        // One status at a time, like every other register here. The API takes a collection because
        // a queue screen may yet want "everything awaiting a person", and widening it later should
        // not mean changing its shape.
        return PayoutInstalmentResponse.PageResponse.from(
            api.search(status != null ? List.of(status) : List.of(),
                PageRequest.of(page, Math.min(pageSize, 100), Sort.by("dueDate", "instalmentId"))));
    }

    @PostMapping("/payouts/{instalmentId}/review")
    @PreAuthorize(FINANCE)
    public PayoutInstalmentResponse review(@PathVariable UUID instalmentId,
                                           @Valid @RequestBody ReviewPayoutRequest request,
                                           @AuthenticationPrincipal Jwt jwt) {
        return PayoutInstalmentResponse.from(api.review(instalmentId, request.payeeRef(),
            request.proofOfLifeMethod(), request.proofOfLifeDocumentId(), jwt.getSubject()));
    }

    /** 202: the payout is REQUESTED from the payment rail, not yet paid. The console says so. */
    @PostMapping("/payouts/{instalmentId}/approve")
    @PreAuthorize(FINANCE)
    public ResponseEntity<PayoutInstalmentResponse> approve(@PathVariable UUID instalmentId,
                                                            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.accepted().body(PayoutInstalmentResponse.from(api.approve(instalmentId, jwt.getSubject())));
    }

    /**
     * Try a FAILED payment again. Only a payout that genuinely did not move money is retryable --
     * an IN_DOUBT one stays APPROVED and waits for a person, because retrying it could pay twice.
     */
    @PostMapping("/payouts/{instalmentId}/retry")
    @PreAuthorize(FINANCE)
    public ResponseEntity<PayoutInstalmentResponse> retry(@PathVariable UUID instalmentId) {
        return ResponseEntity.accepted().body(PayoutInstalmentResponse.from(api.retry(instalmentId)));
    }
}
