package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.finaccounting.api.ShareRegisterApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.ShareRegisterApi.BankAccountView;
import tz.co.nlolo.lifeplatform.finaccounting.api.ShareRegisterApi.CapitalView;
import tz.co.nlolo.lifeplatform.finaccounting.api.ShareRegisterApi.IssueInput;
import tz.co.nlolo.lifeplatform.finaccounting.api.ShareRegisterApi.IssueView;
import tz.co.nlolo.lifeplatform.finaccounting.api.ShareRegisterApi.ShareClassView;
import tz.co.nlolo.lifeplatform.finaccounting.api.ShareRegisterApi.ShareholderInput;
import tz.co.nlolo.lifeplatform.finaccounting.api.ShareRegisterApi.ShareholderView;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The shareholders register (2026-11-14): finance records shareholders and issues; a finance approver -- never the
 * recorder, unless they hold ledger.approve-own -- approves an issue, and the approval posts its journal.
 */
@RestController
public class ShareRegisterController {

    public record NominalRequest(BigDecimal nominalValue) {}

    public record RejectionRequest(String reason) {}

    public record MinimumRequest(BigDecimal minimumPaidUp) {}

    private static final String BASE = "/finance/share-register";

    private final ShareRegisterApi api;

    public ShareRegisterController(ShareRegisterApi api) {
        this.api = api;
    }

    @GetMapping(BASE + "/shareholders")
    @PreAuthorize("@access.can('ledger.view')")
    public List<ShareholderView> shareholders() {
        return api.shareholders();
    }

    @PostMapping(BASE + "/shareholders")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.can('ledger.manage')")
    public ShareholderView addShareholder(@RequestBody ShareholderInput in, @AuthenticationPrincipal Jwt jwt) {
        return api.addShareholder(in, jwt.getSubject());
    }

    @PutMapping(BASE + "/shareholders/{shareholderId}")
    @PreAuthorize("@access.can('ledger.manage')")
    public ShareholderView updateShareholder(@PathVariable UUID shareholderId, @RequestBody ShareholderInput in,
                                             @AuthenticationPrincipal Jwt jwt) {
        return api.updateShareholder(shareholderId, in, jwt.getSubject());
    }

    @GetMapping(BASE + "/classes")
    @PreAuthorize("@access.can('ledger.view')")
    public List<ShareClassView> classes() {
        return api.classes();
    }

    @PutMapping(BASE + "/classes/{classCode}")
    @PreAuthorize("@access.can('ledger.manage')")
    public ShareClassView setNominal(@PathVariable String classCode, @RequestBody NominalRequest body,
                                     @AuthenticationPrincipal Jwt jwt) {
        return api.setNominal(classCode, body.nominalValue(), jwt.getSubject());
    }

    @GetMapping(BASE + "/issues")
    @PreAuthorize("@access.can('ledger.view')")
    public List<IssueView> issues(@RequestParam(required = false) String status) {
        return api.issues(status);
    }

    @PostMapping(BASE + "/issues")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.can('ledger.manage')")
    public IssueView propose(@RequestBody IssueInput in, @AuthenticationPrincipal Jwt jwt) {
        return api.propose(in, jwt.getSubject());
    }

    @PostMapping(BASE + "/issues/{issueId}/withdrawal")
    @PreAuthorize("@access.can('ledger.manage')")
    public IssueView withdraw(@PathVariable UUID issueId, @AuthenticationPrincipal Jwt jwt) {
        return api.withdraw(issueId, jwt.getSubject());
    }

    @PostMapping(BASE + "/issues/{issueId}/approval")
    @PreAuthorize("@access.can('ledger.approve')")
    public IssueView approve(@PathVariable UUID issueId, @AuthenticationPrincipal Jwt jwt) {
        return api.approve(issueId, jwt.getSubject());
    }

    @PostMapping(BASE + "/issues/{issueId}/rejection")
    @PreAuthorize("@access.can('ledger.approve')")
    public IssueView reject(@PathVariable UUID issueId, @RequestBody RejectionRequest body,
                            @AuthenticationPrincipal Jwt jwt) {
        return api.reject(issueId, body.reason(), jwt.getSubject());
    }

    @GetMapping(BASE + "/capital")
    @PreAuthorize("@access.can('ledger.view')")
    public CapitalView capital() {
        return api.capital();
    }

    @PutMapping(BASE + "/capital/minimum")
    @PreAuthorize("@access.can('ledger.manage')")
    public CapitalView setMinimum(@RequestBody MinimumRequest body, @AuthenticationPrincipal Jwt jwt) {
        return api.setMinimum(body.minimumPaidUp(), jwt.getSubject());
    }

    @GetMapping(BASE + "/bank-accounts")
    @PreAuthorize("@access.can('ledger.view')")
    public List<BankAccountView> bankAccounts() {
        return api.bankAccounts();
    }
}
