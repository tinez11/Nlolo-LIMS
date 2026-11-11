package tz.co.nlolo.lifeplatform.billing.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.billing.api.OpenPremiumView;
import tz.co.nlolo.lifeplatform.policy.api.AgentBook;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Calls and reminders on a premium still owed (2026-11-11, premiums to follow up). Staff with collections.manage act on
 * any premium; an agent only on a policy in their own book -- the check BillingSyncController.captureFieldReceipt uses.
 */
@RestController
public class CollectionActionController {

    private final BillingApi billingApi;
    private final PolicyApi policyApi;

    public CollectionActionController(BillingApi billingApi, PolicyApi policyApi) {
        this.billingApi = billingApi;
        this.policyApi = policyApi;
    }

    public record CallRequest(@NotNull UUID invoiceId, String outcome, LocalDate promisedOn,
                              @Size(max = 500) String note) {}

    public record ReminderRequest(@NotNull UUID invoiceId) {}

    @PostMapping("/collections/actions/calls")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('REALM_AGENTS') or @access.can('collections.manage')")
    public OpenPremiumView.ActionView recordCall(@Valid @RequestBody CallRequest request, @AuthenticationPrincipal Jwt jwt,
                                                 Authentication authentication) {
        AgentBook.require(policyApi, billingApi.getInvoice(request.invoiceId()).policyNumber(), jwt, authentication);
        return billingApi.recordCall(request.invoiceId(), request.outcome(), request.promisedOn(), request.note(),
            jwt == null ? null : jwt.getSubject());
    }

    @PostMapping("/collections/actions/reminders")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('REALM_AGENTS') or @access.can('collections.manage')")
    public OpenPremiumView.ActionView sendReminder(@Valid @RequestBody ReminderRequest request,
                                                   @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        AgentBook.require(policyApi, billingApi.getInvoice(request.invoiceId()).policyNumber(), jwt, authentication);
        return billingApi.sendPremiumReminder(request.invoiceId(), jwt == null ? null : jwt.getSubject());
    }
}
