package tz.co.nlolo.lifeplatform.omnichannel.infrastructure;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.omnichannel.api.PaymentScheduleView;
import tz.co.nlolo.lifeplatform.omnichannel.api.SavingsStatementView;
import tz.co.nlolo.lifeplatform.omnichannel.application.PolicyDocuments;
import tz.co.nlolo.lifeplatform.omnichannel.domain.CustomerDocument;
import tz.co.nlolo.lifeplatform.omnichannel.domain.DocumentPdf;
import tz.co.nlolo.lifeplatform.omnichannel.domain.DocumentXlsx;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;

import java.time.LocalDate;

/**
 * The documents a customer can ask for (2026-10-07): a policy's payment schedule and a savings plan's account
 * statement, as JSON for the console's table and as PDF or Excel to hand over. Staff read any policy in their
 * tenant; a customer only their own (the token's party_id must be the policyholder), the same rule
 * GET /policies/{policyNumber} applies.
 */
@RestController
public class PolicyDocumentsController {

    static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final PolicyDocuments documents;
    private final PolicyApi policyApi;

    public PolicyDocumentsController(PolicyDocuments documents, PolicyApi policyApi) {
        this.documents = documents;
        this.policyApi = policyApi;
    }

    @GetMapping("/policies/{policyNumber}/payment-schedule")
    @PreAuthorize("hasRole('REALM_STAFF') or hasRole('REALM_CUSTOMERS')")
    public PaymentScheduleView paymentSchedule(@PathVariable String policyNumber, @AuthenticationPrincipal Jwt jwt,
                                               Authentication authentication) {
        ownPolicyOnly(policyNumber, jwt, authentication);
        return documents.paymentSchedule(policyNumber);
    }

    @GetMapping(value = "/policies/{policyNumber}/payment-schedule/{format}")
    @PreAuthorize("hasRole('REALM_STAFF') or hasRole('REALM_CUSTOMERS')")
    public ResponseEntity<byte[]> paymentScheduleFile(@PathVariable String policyNumber, @PathVariable String format,
                                                      @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        ownPolicyOnly(policyNumber, jwt, authentication);
        return file(documents.paymentScheduleDocument(policyNumber), format, "payment-schedule-" + policyNumber);
    }

    @GetMapping("/policies/{policyNumber}/savings-statement")
    @PreAuthorize("hasRole('REALM_STAFF') or hasRole('REALM_CUSTOMERS')")
    public SavingsStatementView savingsStatement(@PathVariable String policyNumber, @RequestParam LocalDate from,
                                                 @RequestParam LocalDate to, @AuthenticationPrincipal Jwt jwt,
                                                 Authentication authentication) {
        ownPolicyOnly(policyNumber, jwt, authentication);
        return documents.savingsStatement(policyNumber, from, to);
    }

    @GetMapping("/policies/{policyNumber}/savings-statement/{format}")
    @PreAuthorize("hasRole('REALM_STAFF') or hasRole('REALM_CUSTOMERS')")
    public ResponseEntity<byte[]> savingsStatementFile(@PathVariable String policyNumber, @PathVariable String format,
                                                       @RequestParam LocalDate from, @RequestParam LocalDate to,
                                                       @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        ownPolicyOnly(policyNumber, jwt, authentication);
        return file(documents.savingsStatementDocument(policyNumber, from, to), format,
            "savings-statement-" + policyNumber + "-" + from + "-to-" + to);
    }

    /** The policy schedule (2026-10-08, the customer portal step 3): what the policy is, on one page. PDF only. */
    @GetMapping("/policies/{policyNumber}/policy-schedule/pdf")
    @PreAuthorize("hasRole('REALM_STAFF') or hasRole('REALM_CUSTOMERS')")
    public ResponseEntity<byte[]> policySchedule(@PathVariable String policyNumber, @AuthenticationPrincipal Jwt jwt,
                                                 Authentication authentication) {
        ownPolicyOnly(policyNumber, jwt, authentication);
        return file(documents.policyScheduleDocument(policyNumber), "pdf", "policy-schedule-" + policyNumber);
    }

    /** Every premium received on the policy, newest first (2026-10-08, the customer portal step 3). */
    @GetMapping("/policies/{policyNumber}/receipts")
    @PreAuthorize("hasRole('REALM_STAFF') or hasRole('REALM_CUSTOMERS')")
    public java.util.List<tz.co.nlolo.lifeplatform.omnichannel.api.ReceiptLine> receipts(@PathVariable String policyNumber,
            @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        ownPolicyOnly(policyNumber, jwt, authentication);
        return documents.receipts(policyNumber);
    }

    /** One premium receipt as a PDF. */
    @GetMapping("/policies/{policyNumber}/receipts/{receiptId}/pdf")
    @PreAuthorize("hasRole('REALM_STAFF') or hasRole('REALM_CUSTOMERS')")
    public ResponseEntity<byte[]> receipt(@PathVariable String policyNumber, @PathVariable java.util.UUID receiptId,
                                          @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        ownPolicyOnly(policyNumber, jwt, authentication);
        return file(documents.receiptDocument(policyNumber, receiptId), "pdf", "receipt-" + policyNumber + "-" + receiptId);
    }

    private static ResponseEntity<byte[]> file(CustomerDocument document, String format, String name) {
        boolean pdf = "pdf".equalsIgnoreCase(format);
        if (!pdf && !"xlsx".equalsIgnoreCase(format)) {
            throw new IllegalArgumentException("A document downloads as pdf or xlsx, not " + format);
        }
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + (pdf ? ".pdf" : ".xlsx") + "\"")
            .contentType(pdf ? MediaType.APPLICATION_PDF : XLSX)
            .body(pdf ? DocumentPdf.render(document) : DocumentXlsx.render(document));
    }

    /** A customer reads only their own policy: a real 403, as GET /policies/{policyNumber} gives. */
    private void ownPolicyOnly(String policyNumber, Jwt jwt, Authentication authentication) {
        boolean customer = authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
            .anyMatch("ROLE_REALM_CUSTOMERS"::equals);
        if (!customer) {
            return;
        }
        String own = jwt.getClaimAsString("party_id");
        var policy = policyApi.getPolicy(policyNumber);
        if (own == null || policy.policyholderPartyId() == null || !own.equals(policy.policyholderPartyId().toString())) {
            throw new AccessDeniedException("Access denied: customer may only access their own policy");
        }
    }
}
