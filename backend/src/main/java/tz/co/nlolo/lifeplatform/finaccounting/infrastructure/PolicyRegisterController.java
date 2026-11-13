package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import org.springframework.format.annotation.DateTimeFormat;
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
import tz.co.nlolo.lifeplatform.finaccounting.api.BaselineSignOffView;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.finaccounting.application.BaselineSignOffs;
import tz.co.nlolo.lifeplatform.finaccounting.api.PolicyElectionInput;
import tz.co.nlolo.lifeplatform.finaccounting.api.PolicyElectionView;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * The accounting policy register (IFRS 17 spec §3, decision D7): finance or an admin proposes an election; a second
 * person approves it with its sign-off reference, or rejects it with a reason.
 */
@RestController
public class PolicyRegisterController {

    private static final ZoneId CIVIL = ZoneId.of("Africa/Dar_es_Salaam");

    public record ApprovalRequest(String signOffRef) {}

    public record RejectionRequest(String reason) {}

    private final FinaccountingApi api;
    private final tz.co.nlolo.lifeplatform.finaccounting.application.BaselineSignOffs signOffs;

    public PolicyRegisterController(FinaccountingApi api,
                                    tz.co.nlolo.lifeplatform.finaccounting.application.BaselineSignOffs signOffs) {
        this.api = api;
        this.signOffs = signOffs;
    }

    /** The elections in force on {@code asOf} (today when omitted), those scheduled after it, and every one proposed. */
    @GetMapping("/finance/accounting-policies")
    @PreAuthorize("@access.can('ledger.view')")
    public List<PolicyElectionView> list(@RequestParam(required = false)
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return api.policyElections(asOf == null ? LocalDate.now(CIVIL) : asOf);
    }

    /** IFRS 17 I2: how a contract is classified, oldest first (a vesting pension has two). */
    @GetMapping("/finance/policy-classifications/{policyNumber}")
    @PreAuthorize("@access.can('ledger.view')")
    public List<tz.co.nlolo.lifeplatform.finaccounting.api.PolicyClassificationView> classifications(
            @PathVariable String policyNumber) {
        return api.policyClassifications(policyNumber);
    }

    @PostMapping("/finance/accounting-policies")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.can('ledger.manage')")
    public PolicyElectionView propose(@RequestBody PolicyElectionInput input, @AuthenticationPrincipal Jwt jwt) {
        return api.proposePolicyElection(input, jwt.getSubject());
    }

    @PostMapping("/finance/accounting-policies/{electionId}/approval")
    @PreAuthorize("@access.can('ledger.manage')")
    public PolicyElectionView approve(@PathVariable UUID electionId, @RequestBody ApprovalRequest body,
                                      @AuthenticationPrincipal Jwt jwt) {
        return api.approvePolicyElection(electionId, body == null ? null : body.signOffRef(), jwt.getSubject());
    }

    // ---- the baseline's sign-off (2026-11-12, finance findings 99 and 66) ----------------------------------------

    public record SignOffRequest(String reference) {}

    @GetMapping("/finance/accounting-policies/baseline-sign-off")
    @PreAuthorize("@access.can('ledger.view')")
    public BaselineSignOffView baselineSignOff() {
        return signOffs.current();
    }

    @PostMapping("/finance/accounting-policies/baseline-sign-off/finance")
    @PreAuthorize("@access.can('ledger.baseline-sign-off')")
    public BaselineSignOffView signOffAsFinance(@RequestBody SignOffRequest body, @AuthenticationPrincipal Jwt jwt) {
        return signOffs.signOff(BaselineSignOffs.Signer.FINANCE, body == null ? null : body.reference(), jwt.getSubject());
    }

    @PostMapping("/finance/accounting-policies/baseline-sign-off/actuary")
    @PreAuthorize("@access.can('ifrs17.actuary-sign-off')")
    public BaselineSignOffView signOffAsActuary(@RequestBody SignOffRequest body, @AuthenticationPrincipal Jwt jwt) {
        return signOffs.signOff(BaselineSignOffs.Signer.ACTUARY, body == null ? null : body.reference(), jwt.getSubject());
    }

    @PostMapping("/finance/accounting-policies/{electionId}/rejection")
    @PreAuthorize("@access.can('ledger.manage')")
    public PolicyElectionView reject(@PathVariable UUID electionId, @RequestBody RejectionRequest body,
                                     @AuthenticationPrincipal Jwt jwt) {
        return api.rejectPolicyElection(electionId, body == null ? null : body.reason(), jwt.getSubject());
    }
}
