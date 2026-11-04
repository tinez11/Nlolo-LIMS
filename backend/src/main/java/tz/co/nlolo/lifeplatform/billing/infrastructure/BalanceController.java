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
import tz.co.nlolo.lifeplatform.billing.api.BalanceRefusedException;
import tz.co.nlolo.lifeplatform.billing.application.BalanceWithdrawals;
import tz.co.nlolo.lifeplatform.billing.application.DepositReversals;
import tz.co.nlolo.lifeplatform.billing.domain.BalanceWithdrawal;
import tz.co.nlolo.lifeplatform.billing.domain.DepositReversal;

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
                throw new BalanceRefusedException(e.getMessage());
            }
        }
    }

    public record WithdrawalRequest(@NotNull @Valid Money amount, @NotNull @Valid Payee payee) {}

    public record Rejection(@NotBlank @Size(max = 500) String reason) {}

    public record BalanceResponse(String policyNumber, Money balance, java.time.LocalDate coversPremiumsTo) {}

    /** amount is signed here (money out negative), so it is a plain Money-shaped pair, not the request Money's pattern. */
    public record StatementLine(UUID entryId, int seq, String type, Instant at, Money amount, Money balanceAfter,
                                UUID paymentId, UUID invoiceId, UUID withdrawalId, UUID reversesEntryId, String method,
                                String reference, String source) {}

    public record StatementResponse(String policyNumber, long total, int page, int size, List<StatementLine> lines) {}

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

    public record ReversalRequest(@NotBlank @Size(max = 500) String reason) {}

    public record ReversalResponse(UUID reversalId, String policyNumber, UUID paymentId, Money amount, String reason,
                                   String status, String requestedBy, Instant requestedAt, String decidedBy,
                                   Instant decidedAt, String rejectionReason, boolean actedAlone) {
        static ReversalResponse from(DepositReversal r) {
            return new ReversalResponse(r.getReversalId(), r.getPolicyNumber(), r.getPaymentId(),
                new Money(r.getAmount().setScale(2).toPlainString(), r.getCurrency()), r.getReason(), r.getStatus(),
                r.getRequestedBy(), r.getRequestedAt(), r.getDecidedBy(), r.getDecidedAt(), r.getRejectionReason(),
                r.isActedAlone());
        }
    }

    private final BillingApi billing;
    private final BalanceWithdrawals withdrawals;
    private final DepositReversals reversals;

    public BalanceController(BillingApi billing, BalanceWithdrawals withdrawals, DepositReversals reversals) {
        this.billing = billing;
        this.withdrawals = withdrawals;
        this.reversals = reversals;
    }

    // ---- Reversals (money movement step 3b): a deposit whose money never really arrived ------------------------

    /** Ask to reverse the deposit a confirmed receipt or payment made; its id is the receipt's. */
    @PostMapping("/deposits/{paymentId}/reversal")
    @PreAuthorize("@access.can('collections.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public ReversalResponse requestReversal(@PathVariable UUID paymentId, @Valid @RequestBody ReversalRequest body,
                                            @AuthenticationPrincipal Jwt jwt) {
        return ReversalResponse.from(reversals.request(paymentId, body.reason(), jwt.getSubject()));
    }

    /** The reversals asked of one deposit, newest first; its page shows the one waiting, or what became of it. */
    @GetMapping("/deposits/{paymentId}/reversals")
    @PreAuthorize("@access.canAny('collections.view', 'collections.manage')")
    public List<ReversalResponse> reversalsOf(@PathVariable UUID paymentId) {
        return reversals.forDeposit(paymentId).stream().map(ReversalResponse::from).toList();
    }

    @GetMapping("/deposit-reversals/{reversalId}")
    @PreAuthorize("@access.canAny('collections.view', 'collections.manage')")
    public ReversalResponse getReversal(@PathVariable UUID reversalId) {
        return ReversalResponse.from(reversals.get(reversalId));
    }

    @PostMapping("/deposit-reversals/{reversalId}/confirm")
    @PreAuthorize("@access.can('collections.approve')")
    public ReversalResponse confirmReversal(@PathVariable UUID reversalId, @AuthenticationPrincipal Jwt jwt) {
        return ReversalResponse.from(reversals.confirm(reversalId, jwt.getSubject()));
    }

    @PostMapping("/deposit-reversals/{reversalId}/reject")
    @PreAuthorize("@access.can('collections.approve')")
    public ReversalResponse rejectReversal(@PathVariable UUID reversalId, @Valid @RequestBody Rejection body,
                                           @AuthenticationPrincipal Jwt jwt) {
        return ReversalResponse.from(reversals.reject(reversalId, jwt.getSubject(), body.reason()));
    }

    @GetMapping("/policies/{policyNumber}/balance")
    @PreAuthorize("@access.canAny('collections.view', 'collections.manage', 'payouts.view')")
    public BalanceResponse balance(@PathVariable String policyNumber) {
        var view = billing.balance(policyNumber);
        return new BalanceResponse(policyNumber, new Money(view.balance().toPlainString(), view.currency()),
            view.coversPremiumsTo());
    }

    /** One page of the balance's statement, newest first; amounts signed (money out negative), balance after each. */
    @GetMapping("/policies/{policyNumber}/balance/statement")
    @PreAuthorize("@access.canAny('collections.view', 'collections.manage', 'payouts.view')")
    public StatementResponse statement(@PathVariable String policyNumber,
                                       @RequestParam(defaultValue = "0") @jakarta.validation.constraints.Min(0) int page,
                                       @RequestParam(defaultValue = "50") @jakarta.validation.constraints.Min(1)
                                       @jakarta.validation.constraints.Max(200) int size) {
        var view = billing.balanceStatement(policyNumber, page, size);
        String c = view.currency();
        return new StatementResponse(view.policyNumber(), view.total(), view.page(), view.size(), view.lines().stream()
            .map(l -> new StatementLine(l.entryId(), l.seq(), l.type(), l.at(),
                new Money(l.amount().toPlainString(), c), new Money(l.balanceAfter().toPlainString(), c), l.paymentId(),
                l.invoiceId(), l.withdrawalId(), l.reversesEntryId(), l.method(), l.reference(), l.source()))
            .toList());
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

    /** 202: a credit-life lender's premium refund released to the bank-transfer queue (money movement step 4). */
    @PostMapping("/lender-refunds/{creditId}/approve")
    @PreAuthorize("@access.can('payouts.approve')")
    public ResponseEntity<tz.co.nlolo.lifeplatform.billing.api.LenderRefundView> approveLenderRefund(
            @PathVariable UUID creditId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.accepted().body(billing.approveLenderRefund(creditId, jwt.getSubject()));
    }

    @PostMapping("/lender-refunds/{creditId}/reject")
    @PreAuthorize("@access.can('payouts.approve')")
    public tz.co.nlolo.lifeplatform.billing.api.LenderRefundView rejectLenderRefund(@PathVariable UUID creditId,
            @Valid @RequestBody Rejection body, @AuthenticationPrincipal Jwt jwt) {
        return billing.refuseLenderRefund(creditId, jwt.getSubject(), body.reason());
    }
}
