package tz.co.nlolo.lifeplatform.billing.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tz.co.nlolo.lifeplatform.PayoutRoute;
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.billing.api.WithdrawalRefusedException;
import tz.co.nlolo.lifeplatform.billing.application.BalanceWithdrawals;
import tz.co.nlolo.lifeplatform.billing.domain.BalanceWithdrawal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A policy's balance and the money taken back off it (money movement step 3a, 2026-11-04). Reading it is for anyone who
 * sees collections or payouts; asking for a withdrawal or naming its payee is payouts.manage; the second person is
 * payouts.approve (payouts.approve-own lets the asker decide it, recorded against their name).
 */
@RestController
public class BalanceController {

    public record Money(@NotBlank @Pattern(regexp = "^\\d+(\\.\\d{1,2})?$") String amount,
                        @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currencyCode) {}

    /** Who receives it: mobile money to payeeRef, a bank transfer, or a cheque -- PayoutRoute.of checks which. */
    public record Payee(String method, @Size(max = 200) String payeeRef, @Size(max = 100) String payeeBank,
                        @Size(max = 100) String payeeBranch, @Size(max = 200) String payeeAccountName,
                        @Size(max = 50) String payeeAccountNumber) {
        PayoutRoute route() {
            try {
                return PayoutRoute.of(method, payeeRef, payeeBank, payeeBranch, payeeAccountName, payeeAccountNumber);
            } catch (IllegalArgumentException e) {
                throw new WithdrawalRefusedException(e.getMessage());
            }
        }
    }

    public record WithdrawalRequest(@NotNull @Valid Money amount, @NotNull @Valid Payee payee) {}

    public record Rejection(@NotBlank @Size(max = 500) String reason) {}

    public record BalanceResponse(String policyNumber, Money balance) {}

    public record WithdrawalResponse(UUID withdrawalId, String policyNumber, Money amount, String status, String reason,
                                     String method, String payeeRef, String requestedBy, Instant requestedAt,
                                     String approvedBy, Instant approvedAt, String rejectedBy, String rejectionReason,
                                     boolean actedAlone) {
        static WithdrawalResponse from(BalanceWithdrawal w) {
            return new WithdrawalResponse(w.getWithdrawalId(), w.getPolicyNumber(),
                new Money(w.getAmount().setScale(2).toPlainString(), w.getCurrency()), w.getStatus(), w.getReason(),
                w.route().method(), w.route().payee(), w.getRequestedBy(), w.getRequestedAt(), w.getApprovedBy(),
                w.getApprovedAt(), w.getRejectedBy(), w.getRejectionReason(), w.isActedAlone());
        }
    }

    private final BillingApi billing;
    private final BalanceWithdrawals withdrawals;

    public BalanceController(BillingApi billing, BalanceWithdrawals withdrawals) {
        this.billing = billing;
        this.withdrawals = withdrawals;
    }

    @GetMapping("/policies/{policyNumber}/balance")
    @PreAuthorize("@access.canAny('collections.view', 'collections.manage', 'payouts.view')")
    public BalanceResponse balance(@PathVariable String policyNumber) {
        BigDecimal now = billing.balanceOf(policyNumber);
        String currency = billing.listInvoices(policyNumber, null).stream().findFirst()
            .map(i -> i.currency()).orElse("TZS");
        return new BalanceResponse(policyNumber, new Money(now.toPlainString(), currency));
    }

    @GetMapping("/policies/{policyNumber}/balance/withdrawals")
    @PreAuthorize("@access.canAny('collections.view', 'payouts.view')")
    public List<WithdrawalResponse> withdrawalsFor(@PathVariable String policyNumber) {
        return withdrawals.listFor(policyNumber).stream().map(WithdrawalResponse::from).toList();
    }

    @PostMapping("/policies/{policyNumber}/balance/withdrawals")
    @PreAuthorize("@access.can('payouts.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public WithdrawalResponse requestWithdrawal(@PathVariable String policyNumber,
                                                @Valid @RequestBody WithdrawalRequest body,
                                                @AuthenticationPrincipal Jwt jwt) {
        return WithdrawalResponse.from(withdrawals.request(policyNumber, new BigDecimal(body.amount().amount()),
            body.payee().route(), jwt.getSubject()));
    }

    @GetMapping("/balance-withdrawals/{withdrawalId}")
    @PreAuthorize("@access.canAny('collections.view', 'payouts.view')")
    public WithdrawalResponse get(@PathVariable UUID withdrawalId) {
        return WithdrawalResponse.from(withdrawals.get(withdrawalId));
    }

    /** A withdrawal the platform raised when the policy ended: finance names who receives it. */
    @PostMapping("/balance-withdrawals/{withdrawalId}/payee")
    @PreAuthorize("@access.can('payouts.manage')")
    public WithdrawalResponse namePayee(@PathVariable UUID withdrawalId, @Valid @RequestBody Payee body,
                                        @AuthenticationPrincipal Jwt jwt) {
        return WithdrawalResponse.from(withdrawals.namePayee(withdrawalId, body.route(), jwt.getSubject()));
    }

    /** 202: approved and asked of the rail, not yet paid -- as every payout approval. */
    @PostMapping("/balance-withdrawals/{withdrawalId}/approve")
    @PreAuthorize("@access.can('payouts.approve')")
    public ResponseEntity<WithdrawalResponse> approve(@PathVariable UUID withdrawalId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.accepted().body(WithdrawalResponse.from(withdrawals.approve(withdrawalId, jwt.getSubject())));
    }

    @PostMapping("/balance-withdrawals/{withdrawalId}/reject")
    @PreAuthorize("@access.can('payouts.approve')")
    public WithdrawalResponse reject(@PathVariable UUID withdrawalId, @Valid @RequestBody Rejection body,
                                     @AuthenticationPrincipal Jwt jwt) {
        return WithdrawalResponse.from(withdrawals.reject(withdrawalId, jwt.getSubject(), body.reason()));
    }
}
