package tz.co.nlolo.lifeplatform.reinsurance.infrastructure;

import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceApi;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyStatus;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyView;
import jakarta.validation.Valid;
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
