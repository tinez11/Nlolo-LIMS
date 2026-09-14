package tz.co.nlolo.lifeplatform.reinsurance.infrastructure;

import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceApi;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyStatus;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyView;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The {@code /treaties*} surface -- treaty authoring and reads.
 *
 * <p><b>FINANCE_OFFICER/ADMIN gate every endpoint here, which is a decision, not a spec quote.</b>
 * docs/04-api-contracts.md:21 deferred this surface entirely and names no role, there is no
 * reinsurance-specific staff role, and reinsurance is a finance-adjacent back-office concern --
 * the same decision and reasoning M7 recorded for {@code distribution} (see
 * {@code AgentController}'s javadoc).
 */
@RestController
public class TreatyController {

    private final ReinsuranceApi reinsuranceApi;

    public TreatyController(ReinsuranceApi reinsuranceApi) {
        this.reinsuranceApi = reinsuranceApi;
    }

    /**
     * {@code Idempotency-Key} is required and genuinely enforced, copying
     * {@code AgentController.onboardAgent}'s shape: declared {@code required = false} at the
     * Spring level and rejected explicitly here, so a missing header and a present-but-blank one
     * land on one {@code ProblemDetails} path instead of a framework
     * {@code MissingRequestHeaderException} for the first only.
     */
    @PostMapping("/treaties")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<TreatyResponseDto> createTreaty(@Valid @RequestBody CreateTreatyRequestDto request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        requireIdempotencyKey(idempotencyKey);
        TreatyView view = reinsuranceApi.createTreaty(new ReinsuranceApi.CreateTreatyRequest(
            request.reinsurerName(), request.treatyType(),
            new BigDecimal(request.retentionLimit().amount()), request.retentionLimit().currencyCode(),
            request.cessionPercent() == null ? null : new BigDecimal(request.cessionPercent()),
            request.effectiveFrom(), request.effectiveTo()), jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(TreatyResponseDto.from(view));
    }

    @GetMapping("/treaties/{treatyId}")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<TreatyResponseDto> getTreaty(@PathVariable UUID treatyId) {
        TreatyView view = reinsuranceApi.getTreaty(treatyId);
        return ResponseEntity.ok(TreatyResponseDto.from(view));
    }

    /**
     * What has actually been ceded to this treaty.
     *
     * <p>The read a treaty most obviously needs and did not have: cessions could be listed only
     * through the policy they were made on, so a treaty stated a retention limit and a cession
     * percent while hundreds of cessions naming it were unreachable from it.
     *
     * <p>Paged, unlike {@code GET /policies/{n}/cessions}. A policy has a handful; a treaty gains
     * one per policy it covers for as long as it runs.
     */
    @GetMapping("/treaties/{treatyId}/cessions")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<CessionSearchResponseDto> listTreatyCessions(@PathVariable UUID treatyId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok(CessionSearchResponseDto.from(reinsuranceApi.listCessionsForTreaty(
            treatyId, PageRequest.of(page, Math.min(pageSize, 100)))));
    }

    /**
     * The treaty's totals, summed server-side.
     *
     * <p>Its own path rather than fields on {@code GET /treaties/{n}} so that listing treaties
     * does not aggregate the whole cession table per row, and separate from the paged list above
     * because a caller must never sum the page in hand and call it the treaty's utilisation --
     * that figure is always too small and always plausible.
     */
    @GetMapping("/treaties/{treatyId}/utilisation")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<TreatyUtilisationResponseDto> getTreatyUtilisation(@PathVariable UUID treatyId) {
        return ResponseEntity.ok(TreatyUtilisationResponseDto.from(
            reinsuranceApi.getTreatyUtilisation(treatyId)));
    }

    @GetMapping("/treaties")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<List<TreatyResponseDto>> listTreaties(
            @RequestParam(required = false) TreatyStatus status) {
        return ResponseEntity.ok(reinsuranceApi.listTreaties(status).stream()
            .map(TreatyResponseDto::from).toList());
    }

    private static void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required: the same key is treated as "
                + "the same attempt and deduplicated, a new key as a genuinely new attempt. There is "
                + "deliberately no default -- any default would make that dedup meaningless.");
        }
    }
}
