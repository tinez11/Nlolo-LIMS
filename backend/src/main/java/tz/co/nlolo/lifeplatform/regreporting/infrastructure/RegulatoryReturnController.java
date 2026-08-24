package tz.co.nlolo.lifeplatform.regreporting.infrastructure;

import tz.co.nlolo.lifeplatform.regreporting.api.RegreportingApi;
import tz.co.nlolo.lifeplatform.regreporting.api.RegulatoryReturnView;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The {@code /regulatory-returns*} surface -- staff-triggered return generation, plus read paths
 * for both staff and the regulator realm.
 *
 * <p><b>FINANCE_OFFICER/ADMIN gating staff access is a project DECISION, not a literal spec
 * quote.</b> No {@code COMPLIANCE_OFFICER} role (or any regreporting-specific staff role) exists
 * anywhere on this platform -- {@code docs/04-api-contracts.md:44} enumerates the complete staff
 * role set (UNDERWRITER, CLAIMS_ASSESSOR, CLAIMS_MANAGER, FINANCE_OFFICER, CUSTOMER_SERVICE_REP,
 * ADMIN) and stops there. Regulatory-return generation is a finance-adjacent back-office concern
 * with no better-fitting role, so it reuses FINANCE_OFFICER/ADMIN -- the same call M7 recorded for
 * {@code distribution} (see {@code AgentController}'s javadoc) and M8 recorded for
 * {@code reinsurance} (see {@code TreatyController}'s javadoc).
 *
 * <p><b>This is {@code REALM_REGULATORS}' first use anywhere on the platform.</b> Verified by
 * grepping every {@code .java}/{@code .yml}/{@code .yaml}/{@code .sql} file in the repository for
 * the literal {@code REALM_REGULATORS} before writing this class: zero hits outside this class and
 * this task's own planning documents. The realm itself, and the {@code ROLE_REALM_REGULATORS}
 * granted authority it maps to, already exist end-to-end in {@code SecurityConfig}
 * (app.security.issuers.regulators, {@code authoritiesFor}'s {@code "ROLE_REALM_" + realmMarker})
 * from Deliverable 6 onward -- this controller is simply the first endpoint to gate on it.
 *
 * <p><b>Regulators are necessarily tenant-scoped.</b> {@code TenantContextFilter} 403s (errorCode
 * {@code TENANT_CLAIM_MISSING}) any authenticated request whose JWT lacks a valid {@code tenant_id}
 * claim, before the request ever reaches this controller or {@code RegreportingApiImpl} -- see that
 * filter's javadoc and {@code doFilterInternal}. Every read below is additionally scoped inside
 * {@code RegreportingApiImpl} via {@code TenantContext.get()}, so a regulator token can only ever
 * see the one tenant named in its own claim. Cross-tenant regulatory access (a single TIRA token
 * reading across multiple insurers) is therefore out of scope by design, not by omission: it would
 * require a deliberately different claim/authorization shape, not an oversight in this endpoint.
 */
@RestController
public class RegulatoryReturnController {

    private final RegreportingApi regreportingApi;

    public RegulatoryReturnController(RegreportingApi regreportingApi) {
        this.regreportingApi = regreportingApi;
    }

    @PostMapping("/regulatory-returns")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<RegulatoryReturnResponseDto> generateReturn(
            @Valid @RequestBody GenerateReturnRequestDto request, @AuthenticationPrincipal Jwt jwt) {
        RegulatoryReturnView view = regreportingApi.generateReturn(request.returnType(), request.period(),
            jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(RegulatoryReturnResponseDto.from(view));
    }

    @GetMapping("/regulatory-returns")
    @PreAuthorize("(hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))) or hasRole('REALM_REGULATORS')")
    public ResponseEntity<List<RegulatoryReturnResponseDto>> listReturns(
            @RequestParam(required = false) String period) {
        return ResponseEntity.ok(regreportingApi.listReturns(period).stream()
            .map(RegulatoryReturnResponseDto::from).toList());
    }

    @GetMapping("/regulatory-returns/{returnId}")
    @PreAuthorize("(hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))) or hasRole('REALM_REGULATORS')")
    public ResponseEntity<RegulatoryReturnResponseDto> getReturn(@PathVariable UUID returnId) {
        return ResponseEntity.ok(RegulatoryReturnResponseDto.from(regreportingApi.getReturn(returnId)));
    }
}
