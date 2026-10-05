package tz.co.nlolo.lifeplatform.unitlinked.infrastructure;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import tz.co.nlolo.lifeplatform.unitlinked.api.AdjustmentView;
import tz.co.nlolo.lifeplatform.unitlinked.api.CreateFund;
import tz.co.nlolo.lifeplatform.unitlinked.api.FundPriceView;
import tz.co.nlolo.lifeplatform.unitlinked.api.FundView;
import tz.co.nlolo.lifeplatform.unitlinked.api.PolicyUnitsView;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedApi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * The fund register and its prices. Finance (or an admin) creates funds and proposes and approves prices; the
 * two-person rule and the cut-off are the service's, so they hold whichever role clicks. Closing a fund is an
 * admin's decision.
 */
@RestController
public class FundController {

    static final String STAFF = "hasRole('REALM_STAFF')";
    static final String FINANCE = "hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))";
    static final String ADMIN = "hasRole('REALM_STAFF') and hasRole('ADMIN')";

    private final UnitLinkedApi api;

    public FundController(UnitLinkedApi api) {
        this.api = api;
    }

    public record CreateFundRequest(String code, String name, String currency, String assetClass,
                                    BigDecimal annualManagementChargePercent, LocalTime cutOffTime) {}

    public record ProposePriceRequest(String fundCode, LocalDate valuationDate, BigDecimal price, String moveReason) {}

    @PostMapping("/funds")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(FINANCE)
    public FundView create(@RequestBody CreateFundRequest r, @AuthenticationPrincipal Jwt jwt) {
        return api.createFund(new CreateFund(r.code(), r.name(), r.currency(), r.assetClass(),
            r.annualManagementChargePercent(), r.cutOffTime()), jwt.getSubject());
    }

    @GetMapping("/funds")
    @PreAuthorize(STAFF)
    public List<FundView> list() {
        return api.listFunds();
    }

    @GetMapping("/funds/{code}")
    @PreAuthorize(STAFF)
    public FundView get(@PathVariable String code) {
        return api.getFund(code);
    }

    @PostMapping("/funds/{code}/closure")
    @PreAuthorize(ADMIN)
    public FundView close(@PathVariable String code, @AuthenticationPrincipal Jwt jwt) {
        return api.closeFund(code, jwt.getSubject());
    }

    @GetMapping("/funds/{code}/prices")
    @PreAuthorize(STAFF)
    public List<FundPriceView> prices(@PathVariable String code,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return api.listPrices(code, from, to);
    }

    @GetMapping("/funds/{code}/waiting")
    @PreAuthorize(STAFF)
    public List<UnitLinkedApi.WaitingCount> waiting(@PathVariable String code) {
        return api.waiting(code);
    }

    /** A unit-linked policy's holdings, waiting orders and ledger entries (spec §10). */
    @GetMapping("/policies/{policyNumber}/units")
    @PreAuthorize(STAFF)
    public PolicyUnitsView units(@PathVariable String policyNumber) {
        return api.units(policyNumber);
    }

    /** Name the payee of a maturity or lapse payout the policyholder's record could not supply. */
    @PostMapping("/policies/{policyNumber}/units/payee")
    @PreAuthorize(FINANCE)
    public void payAwaitingExit(@PathVariable String policyNumber, @RequestBody SettleRequest r, @AuthenticationPrincipal Jwt jwt) {
        api.payAwaitingExit(policyNumber, r.payeeRef(), jwt.getSubject());
    }

    @PostMapping("/fund-prices")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(FINANCE)
    public FundPriceView propose(@RequestBody ProposePriceRequest r, @AuthenticationPrincipal Jwt jwt) {
        return api.proposePrice(r.fundCode(), r.valuationDate(), r.price(), r.moveReason(), jwt.getSubject());
    }

    @PostMapping(value = "/fund-prices/csv", consumes = {"text/csv", MediaType.TEXT_PLAIN_VALUE})
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(FINANCE)
    public List<FundPriceView> proposeCsv(@RequestBody String csv, @AuthenticationPrincipal Jwt jwt) {
        return api.proposePrices(csv, jwt.getSubject());
    }

    @PostMapping("/fund-prices/{priceId}/approval")
    @PreAuthorize(FINANCE)
    public FundPriceView approve(@PathVariable UUID priceId, @AuthenticationPrincipal Jwt jwt) {
        return api.approvePrice(priceId, jwt.getSubject());
    }

    public record CorrectionRequest(BigDecimal price, String reason) {}

    public record SettleRequest(String payeeRef) {}

    public record WaiveRequest(String reason) {}

    /** A corrected price for an approved one; a second person approves it at the usual /approval (spec §3). */
    @PostMapping("/fund-prices/{priceId}/correction")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(FINANCE)
    public FundPriceView proposeCorrection(@PathVariable UUID priceId, @RequestBody CorrectionRequest r,
                                           @AuthenticationPrincipal Jwt jwt) {
        return api.proposeCorrection(priceId, r.price(), r.reason(), jwt.getSubject());
    }

    @GetMapping("/price-adjustments")
    @PreAuthorize(FINANCE)
    public List<AdjustmentView> adjustments(@RequestParam(required = false) String status) {
        return api.listAdjustments(status);
    }

    @PostMapping("/price-adjustments/{adjustmentId}/settlement")
    @PreAuthorize(FINANCE)
    public AdjustmentView settle(@PathVariable UUID adjustmentId, @RequestBody SettleRequest r, @AuthenticationPrincipal Jwt jwt) {
        return api.settleAdjustment(adjustmentId, r.payeeRef(), jwt.getSubject());
    }

    @PostMapping("/price-adjustments/{adjustmentId}/waiver")
    @PreAuthorize(FINANCE)
    public AdjustmentView waive(@PathVariable UUID adjustmentId, @RequestBody WaiveRequest r, @AuthenticationPrincipal Jwt jwt) {
        return api.waiveAdjustment(adjustmentId, r.reason(), jwt.getSubject());
    }

    public record SplitRequest(List<tz.co.nlolo.lifeplatform.underwriting.api.UnitLinkedChoice.Split> split) {}

    /** Premium redirection (U2): any staff member, audited -- no money moves. */
    @org.springframework.web.bind.annotation.PutMapping("/policies/{policyNumber}/premium-split")
    @PreAuthorize(STAFF)
    public tz.co.nlolo.lifeplatform.unitlinked.api.PremiumSplitView redirect(@PathVariable String policyNumber,
                                                                          @RequestBody SplitRequest r,
                                                                          @AuthenticationPrincipal Jwt jwt) {
        return api.redirect(policyNumber, r.split(), jwt.getSubject());
    }

    /** A fund switch (U2): any staff member, audited -- the customer's money moves between funds, none leaves. */
    @PostMapping("/policies/{policyNumber}/switches")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(STAFF)
    public tz.co.nlolo.lifeplatform.unitlinked.api.SwitchView requestSwitch(@PathVariable String policyNumber,
            @RequestBody tz.co.nlolo.lifeplatform.unitlinked.api.SwitchInput input, @AuthenticationPrincipal Jwt jwt) {
        return api.requestSwitch(policyNumber, input, jwt.getSubject());
    }

    @GetMapping("/policies/{policyNumber}/switches")
    @PreAuthorize(STAFF)
    public List<tz.co.nlolo.lifeplatform.unitlinked.api.SwitchView> listSwitches(@PathVariable String policyNumber) {
        return api.listSwitches(policyNumber);
    }

    /** A partial withdrawal (U2): requested by any staff member, approved by a second -- finance or an admin. */
    @PostMapping("/policies/{policyNumber}/withdrawals")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(STAFF)
    public tz.co.nlolo.lifeplatform.unitlinked.api.WithdrawalView requestWithdrawal(@PathVariable String policyNumber,
            @RequestBody tz.co.nlolo.lifeplatform.unitlinked.api.WithdrawalInput input, @AuthenticationPrincipal Jwt jwt) {
        return api.requestWithdrawal(policyNumber, input, jwt.getSubject());
    }

    @PostMapping("/withdrawals/{withdrawalId}/approval")
    @PreAuthorize(FINANCE)
    public tz.co.nlolo.lifeplatform.unitlinked.api.WithdrawalView approveWithdrawal(@PathVariable UUID withdrawalId,
                                                                                   @AuthenticationPrincipal Jwt jwt) {
        return api.approveWithdrawal(withdrawalId, jwt.getSubject());
    }

    @GetMapping("/policies/{policyNumber}/withdrawals")
    @PreAuthorize(STAFF)
    public List<tz.co.nlolo.lifeplatform.unitlinked.api.WithdrawalView> listWithdrawals(@PathVariable String policyNumber) {
        return api.listWithdrawals(policyNumber);
    }

    /** A top-up (U2): any staff member; the Idempotency-Key header is required, so a retry never collects twice. */
    @PostMapping("/policies/{policyNumber}/top-ups")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(STAFF)
    public tz.co.nlolo.lifeplatform.unitlinked.api.TopUpView requestTopUp(@PathVariable String policyNumber,
            @RequestBody tz.co.nlolo.lifeplatform.unitlinked.api.TopUpInput input,
            @org.springframework.web.bind.annotation.RequestHeader(value = "Idempotency-Key", required = false) String key,
            @AuthenticationPrincipal Jwt jwt) {
        return api.requestTopUp(policyNumber, input, jwt.getSubject(), key);
    }

    @GetMapping("/policies/{policyNumber}/top-ups")
    @PreAuthorize(STAFF)
    public List<tz.co.nlolo.lifeplatform.unitlinked.api.TopUpView> listTopUps(@PathVariable String policyNumber) {
        return api.listTopUps(policyNumber);
    }

    /** The period of an on-demand statement. */
    public record StatementPeriod(java.time.LocalDate from, java.time.LocalDate to) {}

    /** An on-demand unit statement (U2): any staff member, any period ending today at the latest. */
    @PostMapping("/policies/{policyNumber}/statements")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(STAFF)
    public tz.co.nlolo.lifeplatform.unitlinked.api.UnitStatementView fileStatement(@PathVariable String policyNumber,
            @RequestBody StatementPeriod period, @AuthenticationPrincipal Jwt jwt) {
        if (period == null) {
            throw new IllegalArgumentException("A statement needs the first and last day of its period");
        }
        return api.fileStatement(policyNumber, period.from(), period.to(), jwt.getSubject());
    }

    @GetMapping("/policies/{policyNumber}/statements")
    @PreAuthorize(STAFF)
    public List<tz.co.nlolo.lifeplatform.unitlinked.api.UnitStatementView> statements(@PathVariable String policyNumber) {
        return api.statements(policyNumber);
    }

    @GetMapping(value = "/unit-statements/{statementId}/file", produces = "application/pdf")
    @PreAuthorize(STAFF)
    public org.springframework.http.ResponseEntity<byte[]> statementFile(@PathVariable UUID statementId) {
        return org.springframework.http.ResponseEntity.ok()
            .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"unit-statement-" + statementId + ".pdf\"")
            .body(api.statementPdf(statementId));
    }

    @GetMapping("/policies/{policyNumber}/premium-split")
    @PreAuthorize(STAFF)
    public List<tz.co.nlolo.lifeplatform.unitlinked.api.PremiumSplitView> splitHistory(@PathVariable String policyNumber) {
        return api.splitHistory(policyNumber);
    }

    @PostMapping("/fund-prices/{priceId}/withdrawal")
    @PreAuthorize(FINANCE)
    public FundPriceView withdraw(@PathVariable UUID priceId, @AuthenticationPrincipal Jwt jwt) {
        return api.withdrawPrice(priceId, jwt.getSubject());
    }
}
