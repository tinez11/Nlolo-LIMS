package tz.co.nlolo.lifeplatform.payment.infrastructure;

import tz.co.nlolo.lifeplatform.payment.api.DisbursementStatusView;
import tz.co.nlolo.lifeplatform.payment.api.PaymentApi;
import tz.co.nlolo.lifeplatform.payment.application.PaymentApiImpl;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Finance's half of the EFT rail — the only rail a credit-life claim settles on.
 *
 * <p>Every other payout on this platform completes without a human: the mobile-money aggregator
 * calls {@link MobileMoneyCallbackController} back and the row moves itself. An EFT has no
 * callback, because it has no integration: a finance officer logs in to the bank, makes the
 * transfer, and comes here to record that they did. That is a deliberate design choice and not a
 * missing feature — see {@code PaymentRequestListener.handleClaimSettlement} for why a
 * multi-million-shilling lender payout must not go through this platform's mobile-money mock.
 *
 * <p><b>FINANCE_OFFICER, not REALM_STAFF.</b> The POST below is the act that tells the whole
 * platform a claim is paid: it publishes {@code payment.DisbursementCompleted}, which settles the
 * claim, exits the borrower from cover and books the claims expense to the general ledger. Anyone
 * who can call it can mark money as having moved when it has not.
 */
@RestController
public class DisbursementController {

    private final PaymentApi paymentApi;
    private final PaymentApiImpl paymentApiImpl;

    /** Both, on purpose. The read goes through the published read-only {@link PaymentApi}; the
     * write reaches {@link PaymentApiImpl} directly, because payment's published API is read-only
     * to everything including this module's own controller — the same arrangement
     * {@link MobileMoneyCallbackController} uses for {@code applyGatewayCallback}. */
    public DisbursementController(PaymentApi paymentApi, PaymentApiImpl paymentApiImpl) {
        this.paymentApi = paymentApi;
        this.paymentApiImpl = paymentApiImpl;
    }

    /**
     * The work queue: what this tenant owes by bank transfer and has not paid yet.
     *
     * <p>Answered with {@link AwaitingEftResponseDto} rather than the generic status envelope,
     * because nothing else on the platform tells a finance officer these exist. A row therefore
     * has to carry the payee, the purpose and the age, not just an id and an amount — see that
     * record for why the generic shape made this queue unbuildable.
     */
    @GetMapping("/disbursements/awaiting-execution")
    @PreAuthorize("hasRole('FINANCE_OFFICER')")
    public ResponseEntity<List<AwaitingEftResponseDto>> listAwaitingExecution() {
        List<DisbursementStatusView> awaiting = paymentApi.listAwaitingEftExecution();
        return ResponseEntity.ok(awaiting.stream().map(AwaitingEftResponseDto::from).toList());
    }

    /**
     * 200 rather than 202: unlike a settlement decision, nothing here happens later. The transfer
     * already happened, in the bank; this call only records it. The downstream consequences
     * (claim SETTLED, member EXITED, GL posted) run synchronously on this thread's AFTER_COMMIT.
     */
    @PostMapping("/disbursements/{disbursementId}/eft-execution")
    @PreAuthorize("hasRole('FINANCE_OFFICER')")
    public ResponseEntity<Void> markEftExecuted(@PathVariable UUID disbursementId,
            @Valid @RequestBody MarkEftExecutedRequestDto request, @AuthenticationPrincipal Jwt jwt) {
        paymentApiImpl.markEftExecuted(disbursementId, request.bankReference(), jwt.getSubject());
        return ResponseEntity.ok().build();
    }

    /**
     * 409, not 500. Confirming a row that is not AWAITING_EXECUTION is a state-machine violation —
     * most often an attempt to hand-complete a mobile-money payout the gateway owns, which is
     * exactly what {@code DisbursementInstruction.markEftExecuted} exists to refuse.
     *
     * <p>Declared on the controller rather than in {@link PaymentExceptionHandler}, deliberately:
     * that advice is unscoped and runs at HIGHEST_PRECEDENCE, so mapping
     * {@code IllegalStateException} there would silently turn every unrelated illegal state on the
     * platform into a 409. A handler method on the controller is consulted only for this
     * controller's own requests.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleWrongState(IllegalStateException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setProperty("errorCode", "DISBURSEMENT_NOT_AWAITING_EXECUTION");
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }
}
