package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tz.co.nlolo.lifeplatform.accumulation.api.AccountNotFoundException;
import tz.co.nlolo.lifeplatform.accumulation.api.AccumulationApi;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
public class AccumulationController {

    /** Setting a price is ADMIN's, as publishing a product version is. */
    static final String PRICING = "hasRole('REALM_STAFF') and hasRole('ADMIN')";
    /** The second signature on money: another ADMIN, or finance. */
    static final String FINANCE = "hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))";

    private final AccumulationApi api;

    public AccumulationController(AccumulationApi api) {
        this.api = api;
    }

    @GetMapping("/products/{productId}/rate-declarations")
    @PreAuthorize(FINANCE)
    public List<RateDeclarationResponse> listRates(@PathVariable UUID productId) {
        return api.listRates(productId).stream().map(RateDeclarationResponse::from).toList();
    }

    @PostMapping("/products/{productId}/rate-declarations")
    @PreAuthorize(PRICING)
    @ResponseStatus(HttpStatus.CREATED)
    public RateDeclarationResponse proposeRate(@PathVariable UUID productId, @Valid @RequestBody RateDeclarationRequest request,
                                               @AuthenticationPrincipal Jwt jwt) {
        return RateDeclarationResponse.from(api.proposeRate(productId, request.ratePercent(), request.effectiveFrom(),
            jwt.getSubject()));
    }

    @PostMapping("/rate-declarations/{declarationId}/approve")
    @PreAuthorize(FINANCE)
    public RateDeclarationResponse approveRate(@PathVariable UUID declarationId, @AuthenticationPrincipal Jwt jwt) {
        return RateDeclarationResponse.from(api.approveRate(declarationId, jwt.getSubject()));
    }

    @PostMapping("/rate-declarations/{declarationId}/withdraw")
    @PreAuthorize(PRICING)
    public RateDeclarationResponse withdrawRate(@PathVariable UUID declarationId, @AuthenticationPrincipal Jwt jwt) {
        return RateDeclarationResponse.from(api.withdrawRate(declarationId, jwt.getSubject()));
    }

    // ---- The account and the money moving on it (task 6) ----------------------------------------

    /** Any staff member may read an account: it is the customer's own money and its history. */
    @GetMapping("/policies/{policyNumber}/account")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public AccountResponse account(@PathVariable String policyNumber) {
        return AccountResponse.from(api.findAccount(policyNumber).orElseThrow(() -> new AccountNotFoundException(policyNumber)),
            api.entries(policyNumber));
    }

    /**
     * What a closing today would pay, before any surrender charge: the balance plus interest not yet
     * posted. The surrender approval shows this beside the request's own estimate (spec §5.4).
     */
    @GetMapping("/policies/{policyNumber}/account/quote")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ClosingQuoteResponse quote(@PathVariable String policyNumber) {
        return ClosingQuoteResponse.from(api.quoteClosing(policyNumber, java.time.LocalDate.now()));
    }

    /** Any staff member may REQUEST, as a surrender is requested; approving moves money and is finance's. */
    @PostMapping("/policies/{policyNumber}/account/withdrawals")
    @PreAuthorize("hasRole('REALM_STAFF')")
    @ResponseStatus(HttpStatus.CREATED)
    public WithdrawalResponse requestWithdrawal(@PathVariable String policyNumber,
                                                @Valid @RequestBody AccountRequestBodies.Withdrawal body,
                                                @AuthenticationPrincipal Jwt jwt) {
        return WithdrawalResponse.from(api.requestWithdrawal(policyNumber, new BigDecimal(body.amount()), body.payeeRef(),
            jwt.getSubject()));
    }

    /** 202: approved and REQUESTED from the rail, not yet paid -- as a payout approval. */
    @PostMapping("/account-withdrawals/{withdrawalId}/approve")
    @PreAuthorize(FINANCE)
    public ResponseEntity<WithdrawalResponse> approveWithdrawal(@PathVariable UUID withdrawalId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.accepted().body(WithdrawalResponse.from(api.approveWithdrawal(withdrawalId, jwt.getSubject())));
    }

    @GetMapping("/policies/{policyNumber}/account/withdrawals")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public List<WithdrawalResponse> listWithdrawals(@PathVariable String policyNumber) {
        return api.listWithdrawals(policyNumber).stream().map(WithdrawalResponse::from).toList();
    }

    /** 202: the collection is REQUESTED from the rail; it is credited only once payment confirms it. */
    @PostMapping("/policies/{policyNumber}/account/top-ups")
    @PreAuthorize("hasRole('REALM_STAFF')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TopUpResponse requestTopUp(@PathVariable String policyNumber, @Valid @RequestBody AccountRequestBodies.TopUp body,
                                      @AuthenticationPrincipal Jwt jwt) {
        return TopUpResponse.from(api.requestTopUp(policyNumber, new BigDecimal(body.amount()), body.payerRef(), jwt.getSubject()));
    }

    @GetMapping("/policies/{policyNumber}/account/top-ups")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public List<TopUpResponse> listTopUps(@PathVariable String policyNumber) {
        return api.listTopUps(policyNumber).stream().map(TopUpResponse::from).toList();
    }

    /** Finance's: it puts money on an account. */
    @PostMapping("/policies/{policyNumber}/account/transfers-in")
    @PreAuthorize(FINANCE)
    @ResponseStatus(HttpStatus.CREATED)
    public TransferInResponse recordTransferIn(@PathVariable String policyNumber,
                                               @Valid @RequestBody AccountRequestBodies.TransferIn body,
                                               @AuthenticationPrincipal Jwt jwt) {
        return TransferInResponse.from(api.recordTransferIn(policyNumber, new BigDecimal(body.amount()), body.sourceScheme(),
            body.documentRef(), jwt.getSubject()));
    }

    @GetMapping("/policies/{policyNumber}/account/transfers-in")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public List<TransferInResponse> listTransfersIn(@PathVariable String policyNumber) {
        return api.listTransfersIn(policyNumber).stream().map(TransferInResponse::from).toList();
    }

    /** Finance proposes a correction; a DIFFERENT finance user or admin decides it. */
    @PostMapping("/policies/{policyNumber}/account/adjustments")
    @PreAuthorize(FINANCE)
    @ResponseStatus(HttpStatus.CREATED)
    public AdjustmentResponse proposeAdjustment(@PathVariable String policyNumber,
                                                @Valid @RequestBody AccountRequestBodies.Adjustment body,
                                                @AuthenticationPrincipal Jwt jwt) {
        return AdjustmentResponse.from(api.proposeAdjustment(policyNumber, new BigDecimal(body.amount()), body.reason(),
            jwt.getSubject()));
    }

    @PostMapping("/account-adjustments/{adjustmentId}/approve")
    @PreAuthorize(FINANCE)
    public AdjustmentResponse approveAdjustment(@PathVariable UUID adjustmentId, @AuthenticationPrincipal Jwt jwt) {
        return AdjustmentResponse.from(api.approveAdjustment(adjustmentId, jwt.getSubject()));
    }

    @PostMapping("/account-adjustments/{adjustmentId}/reject")
    @PreAuthorize(FINANCE)
    public AdjustmentResponse rejectAdjustment(@PathVariable UUID adjustmentId, @AuthenticationPrincipal Jwt jwt) {
        return AdjustmentResponse.from(api.rejectAdjustment(adjustmentId, jwt.getSubject()));
    }

    @GetMapping("/policies/{policyNumber}/account/adjustments")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public List<AdjustmentResponse> listAdjustments(@PathVariable String policyNumber) {
        return api.listAdjustments(policyNumber).stream().map(AdjustmentResponse::from).toList();
    }
}
