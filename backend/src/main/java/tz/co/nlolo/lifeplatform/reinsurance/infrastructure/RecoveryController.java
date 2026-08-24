package tz.co.nlolo.lifeplatform.reinsurance.infrastructure;

import tz.co.nlolo.lifeplatform.reinsurance.api.ClaimRecoveryView;
import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceApi;
import tz.co.nlolo.lifeplatform.reinsurance.api.RecoveryNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The {@code /policies/{policyNumber}/cessions} and {@code /claims/{claimId}/recoveries*} surface.
 * Same FINANCE_OFFICER/ADMIN gate recorded in {@code TreatyController}'s javadoc, for the same
 * reason.
 */
@RestController
public class RecoveryController {

    private final ReinsuranceApi reinsuranceApi;

    public RecoveryController(ReinsuranceApi reinsuranceApi) {
        this.reinsuranceApi = reinsuranceApi;
    }

    @GetMapping("/policies/{policyNumber}/cessions")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<List<CessionResponseDto>> listCessionsForPolicy(@PathVariable String policyNumber) {
        return ResponseEntity.ok(reinsuranceApi.listCessionsForPolicy(policyNumber).stream()
            .map(CessionResponseDto::from).toList());
    }

    @GetMapping("/claims/{claimId}/recoveries")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<List<ClaimRecoveryResponseDto>> listRecoveriesForClaim(@PathVariable UUID claimId) {
        return ResponseEntity.ok(reinsuranceApi.listRecoveriesForClaim(claimId).stream()
            .map(ClaimRecoveryResponseDto::from).toList());
    }

    /**
     * <b>IDOR fix, same shape as {@code AgentController.listAccruals}'s security fix.</b> This path
     * nests {@code recoveryId} under {@code claimId}, but nesting in a path is not authorization:
     * {@code ReinsuranceApi.confirmRecovery(UUID recoveryId, String confirmedBy)} takes no
     * {@code claimId} at all -- it is a pure recovery-id lookup, tenant-scoped but claim-agnostic.
     * Checking only that the caller may act on {@code claimId} and then trusting {@code recoveryId}
     * without verifying it actually belongs to that same claim is a same-tenant IDOR: any caller
     * legitimately allowed to confirm recoveries on THEIR claim could substitute a DIFFERENT
     * claim's real recovery id and confirm (and thereby learn the existence and amount of) a
     * recovery that has nothing to do with the claim in the path.
     *
     * <p>Fixed by resolving {@code claimId}'s OWN recoveries (already tenant- and claim-scoped) and
     * requiring {@code recoveryId} to be among them before ever calling {@code confirmRecovery}. A
     * mismatch 404s -- matching this platform's convention that a resource belonging to someone
     * else is reported as not found under the path implying it is theirs, rather than confirming
     * its existence with a 403.
     *
     * <p>202, not 200: the recovery is CONFIRMED here synchronously (unlike a payout request), but
     * kept at 202 to mirror this platform's convention for an imperative staff action that also
     * publishes a domain event other modules react to asynchronously (finaccounting's journal
     * entry) -- the caller's view of "done" and the downstream settlement are not the same moment.
     */
    @PostMapping("/claims/{claimId}/recoveries/{recoveryId}/confirm")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<ClaimRecoveryResponseDto> confirmRecovery(@PathVariable UUID claimId,
            @PathVariable UUID recoveryId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        requireIdempotencyKey(idempotencyKey);
        boolean belongsToClaim = reinsuranceApi.listRecoveriesForClaim(claimId).stream()
            .anyMatch(r -> r.recoveryId().equals(recoveryId));
        if (!belongsToClaim) {
            throw new RecoveryNotFoundException(
                "Recovery " + recoveryId + " not found for claim " + claimId);
        }
        ClaimRecoveryView view = reinsuranceApi.confirmRecovery(recoveryId, jwt.getSubject());
        return ResponseEntity.accepted().body(ClaimRecoveryResponseDto.from(view));
    }

    private static void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required: the same key is treated as "
                + "the same attempt and deduplicated, a new key as a genuinely new attempt. There is "
                + "deliberately no default -- any default would make that dedup meaningless.");
        }
    }
}
