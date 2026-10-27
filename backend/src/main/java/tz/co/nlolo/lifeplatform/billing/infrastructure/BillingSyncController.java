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
        BillingApi.FieldReceiptResult result = billingApi.captureFieldReceipt(agentId, request.policyNumber(),
            new BigDecimal(request.amount().amount()), request.amount().currencyCode(),
            request.clientIdempotencyKey(), request.capturedAt());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new FieldReceiptResponseDto(result.receiptId(), result.status()));
    }
}
