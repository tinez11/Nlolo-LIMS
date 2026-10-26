package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.finaccounting.api.ExpenseAllocationInput;
import tz.co.nlolo.lifeplatform.finaccounting.api.ExpenseAllocationPreview;
import tz.co.nlolo.lifeplatform.finaccounting.api.ExpenseAllocationView;
import tz.co.nlolo.lifeplatform.finaccounting.application.ExpenseAllocations;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * P-19 over HTTP (IFRS 17 I5b, month-end step 5): finance prepares a closing month's expense allocation -- three totals,
 * or why there is none -- and a FINANCE_APPROVER who did not prepare it approves or rejects it.
 */
@RestController
public class ExpenseAllocationController {


    public record ApprovalRequest(Boolean aboveThePool) {}

    public record RejectionRequest(String reason) {}

    private final ExpenseAllocations allocations;

    public ExpenseAllocationController(ExpenseAllocations allocations) {
        this.allocations = allocations;
    }

    @GetMapping("/ifrs17/periods/{period}/expense-allocation-preview")
    @PreAuthorize("@access.can('ifrs17.view')")
    public ExpenseAllocationPreview preview(@PathVariable String period,
                                            @RequestParam(required = false) BigDecimal maintenance,
                                            @RequestParam(required = false) BigDecimal claimsHandling,
                                            @RequestParam(required = false) BigDecimal acquisition) {
        return allocations.preview(period, maintenance, claimsHandling, acquisition);
    }

    @PostMapping("/ifrs17/periods/{period}/expense-allocations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.can('ifrs17.manage')")
    public ExpenseAllocationView prepare(@PathVariable String period, @RequestBody(required = false) ExpenseAllocationInput body,
                                         @AuthenticationPrincipal Jwt jwt) {
        return allocations.prepare(period, body, jwt.getSubject());
    }

    @GetMapping("/ifrs17/periods/{period}/expense-allocations")
    @PreAuthorize("@access.can('ifrs17.view')")
    public List<ExpenseAllocationView> list(@PathVariable String period) {
        return allocations.list(period);
    }

    @GetMapping("/ifrs17/expense-allocations/{id}")
    @PreAuthorize("@access.can('ifrs17.view')")
    public ExpenseAllocationView get(@PathVariable UUID id) {
        return allocations.get(id);
    }

    @PostMapping("/ifrs17/expense-allocations/{id}/approval")
    @PreAuthorize("@access.can('ifrs17.approve')")
    public ExpenseAllocationView approve(@PathVariable UUID id, @RequestBody(required = false) ApprovalRequest body,
                                         @AuthenticationPrincipal Jwt jwt) {
        return allocations.approve(id, body != null && Boolean.TRUE.equals(body.aboveThePool()), jwt.getSubject());
    }

    @PostMapping("/ifrs17/expense-allocations/{id}/rejection")
    @PreAuthorize("@access.can('ifrs17.approve')")
    public ExpenseAllocationView reject(@PathVariable UUID id, @RequestBody(required = false) RejectionRequest body,
                                        @AuthenticationPrincipal Jwt jwt) {
        return allocations.reject(id, body == null ? null : body.reason(), jwt.getSubject());
    }
}
