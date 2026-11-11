package tz.co.nlolo.lifeplatform.billing.infrastructure;

import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.policy.api.AgentBook;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
public class BillingSyncController {

    private final BillingApi billingApi;
    private final PolicyApi policyApi;

    public BillingSyncController(BillingApi billingApi, PolicyApi policyApi) {
        this.billingApi = billingApi;
        this.policyApi = policyApi;
    }

    /**
     * Cash an agent says they collected. Since 2026-10-27 the agent files only as themselves -- the agent id in the
     * path is the signed-in agent's own -- and only for a policy in their book. Before, any agent could file cash in
     * another agent's name, against any customer.
     */
    @PostMapping("/agents/{agentId}/field-receipts")
    @PreAuthorize("hasRole('REALM_AGENTS')")
    public ResponseEntity<FieldReceiptResponseDto> captureFieldReceipt(@PathVariable UUID agentId,
                                                                        @Valid @RequestBody FieldReceiptRequestDto request,
                                                                        @AuthenticationPrincipal Jwt jwt,
                                                                        Authentication authentication) {
        if (!agentId.equals(AgentBook.ownAgentId(policyApi, jwt))) {
            throw new AccessDeniedException("Access denied: an agent files field receipts only as themselves");
        }
        AgentBook.require(policyApi, request.policyNumber(), jwt, authentication);
        // The agent's own party rides along so the customer's SMS can name who took the cash (2026-11-11).
        String agentParty = jwt == null ? null : jwt.getClaimAsString("party_id");
        BillingApi.FieldReceiptResult result = billingApi.captureFieldReceipt(agentId,
            agentParty == null ? null : UUID.fromString(agentParty), request.policyNumber(),
            new BigDecimal(request.amount().amount()), request.amount().currencyCode(),
            request.clientIdempotencyKey(), request.capturedAt());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new FieldReceiptResponseDto(result.receiptId(), result.status()));
    }

    /** The cash this agent holds: collected and not yet received by the company, oldest first (2026-11-10). */
    @GetMapping("/agents/{agentId}/field-receipts")
    @PreAuthorize("hasRole('REALM_AGENTS')")
    public java.util.List<FieldReceiptQueueResponseDto> agentCash(@PathVariable UUID agentId, @AuthenticationPrincipal Jwt jwt) {
        ownOnly(agentId, jwt);
        return billingApi.agentCash(agentId).stream().map(FieldReceiptQueueResponseDto::from).toList();
    }

    /** The bank slip or mobile-money reference the agent paid these collections in under (2026-11-10). */
    public record BankedRequest(@jakarta.validation.constraints.NotEmpty java.util.List<UUID> receiptIds,
                                @jakarta.validation.constraints.NotBlank
                                @jakarta.validation.constraints.Size(max = 100) String reference) {}

    @PostMapping("/agents/{agentId}/field-receipts/banked")
    @PreAuthorize("hasRole('REALM_AGENTS')")
    public java.util.List<FieldReceiptQueueResponseDto> markBanked(@PathVariable UUID agentId,
                                                                   @Valid @RequestBody BankedRequest request,
                                                                   @AuthenticationPrincipal Jwt jwt) {
        ownOnly(agentId, jwt);
        return billingApi.markAgentCashBanked(agentId, request.receiptIds(), request.reference()).stream()
            .map(FieldReceiptQueueResponseDto::from).toList();
    }

    private void ownOnly(UUID agentId, Jwt jwt) {
        if (!agentId.equals(AgentBook.ownAgentId(policyApi, jwt))) {
            throw new AccessDeniedException("Access denied: an agent sees and vouches only for their own cash");
        }
    }
}
