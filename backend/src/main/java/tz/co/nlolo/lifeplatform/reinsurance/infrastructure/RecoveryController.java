package tz.co.nlolo.lifeplatform.reinsurance.infrastructure;

import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceApi;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The {@code /policies/{policyNumber}/cessions} and {@code /claims/{claimId}/recoveries} surface.
 * Same FINANCE_OFFICER/ADMIN gate recorded in {@code TreatyController}'s javadoc, for the same
 * reason.
 *
 * <p>There is no Confirm since IFRS 17 I3c (user answer Q4): a recovery is posted when its claim is approved
 * (B-05, Dr 1420 / Cr 6120) and the amount is agreed with the reinsurer on its statement.
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
}
