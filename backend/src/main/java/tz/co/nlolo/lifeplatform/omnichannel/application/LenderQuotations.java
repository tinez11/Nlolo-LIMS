package tz.co.nlolo.lifeplatform.omnichannel.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.omnichannel.api.CustomerRequestRefusedException;
import tz.co.nlolo.lifeplatform.omnichannel.domain.CustomerDocument;
import tz.co.nlolo.lifeplatform.omnichannel.domain.CustomerDocument.Column;
import tz.co.nlolo.lifeplatform.omnichannel.domain.CustomerDocument.Field;
import tz.co.nlolo.lifeplatform.omnichannel.domain.CustomerDocument.Kind;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyDetailView;
import tz.co.nlolo.lifeplatform.policy.api.EnrolmentApi;
import tz.co.nlolo.lifeplatform.policy.api.EnrolmentQuotationView;
import tz.co.nlolo.lifeplatform.policy.api.EnrolmentSubmissionView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import tz.co.nlolo.lifeplatform.policy.api.SubmissionStatus;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.omnichannel.application.PolicyDocuments.CIVIL_ZONE;
import static tz.co.nlolo.lifeplatform.omnichannel.application.PolicyDocuments.DMY;
import static tz.co.nlolo.lifeplatform.omnichannel.application.PolicyDocuments.ISSUER;
import static tz.co.nlolo.lifeplatform.omnichannel.application.PolicyDocuments.address;
import static tz.co.nlolo.lifeplatform.omnichannel.application.PolicyDocuments.money;

/**
 * The quotation a lender is sent for one monthly file (2026-11-02), as an individual's quotation is handed over: who
 * is covered, each borrower's loan and premium, what the lender pays and earns, and the two signatures. Every figure
 * is the file's own quotation -- priced exactly as acceptance prices it, so this is what the lender will be invoiced.
 */
@Service
public class LenderQuotations {

    private final EnrolmentApi enrolmentApi;
    private final PolicyApi policyApi;
    private final PartyApi partyApi;
    private final ProductApi productApi;

    public LenderQuotations(EnrolmentApi enrolmentApi, PolicyApi policyApi, PartyApi partyApi, ProductApi productApi) {
        this.enrolmentApi = enrolmentApi;
        this.policyApi = policyApi;
        this.partyApi = partyApi;
        this.productApi = productApi;
    }

    @Transactional(readOnly = true)
    public CustomerDocument fileQuotation(String policyNumber, UUID submissionId) {
        EnrolmentSubmissionView file = enrolmentApi.getSubmission(submissionId);
        if (!file.policyNumber().equals(policyNumber)) {
            throw new CustomerRequestRefusedException("File " + submissionId + " is not on scheme " + policyNumber);
        }
        if (file.status() == SubmissionStatus.WITHDRAWN) {
            throw new CustomerRequestRefusedException("This file was withdrawn: it covers nobody and charges nothing");
        }
        EnrolmentQuotationView q = enrolmentApi.quotation(submissionId);
        if (q.borrowers() == 0) {
            throw new CustomerRequestRefusedException("Every row of this file was refused: there is nothing to quote");
        }
        PolicyView policy = policyApi.getPolicy(policyNumber);
        PartyDetailView lender = partyApi.getPartyDetail(policy.policyholderPartyId());
        String currency = q.currency();
        String lenderName = lender.displayName() == null ? "The lender" : lender.displayName();
        boolean accepted = file.status() == SubmissionStatus.ACCEPTED;

        List<Field> fields = new ArrayList<>();
        fields.add(new Field("Lender", lenderName));
        fields.add(new Field("Phone", lender.phoneNumber() != null ? lender.phoneNumber() : "-"));
        fields.add(new Field("Address", address(lender)));
        fields.add(new Field("Scheme", policyNumber));
        fields.add(new Field("Product", productApi.getProduct(policy.productId()).productName()));
        fields.add(new Field("File", file.fileName() == null ? "-" : file.fileName()));
        fields.add(new Field("Received on", file.submittedAt() == null ? "-"
            : DMY.format(file.submittedAt().atZone(CIVIL_ZONE).toLocalDate())));
        fields.add(new Field("Printed on", DMY.format(LocalDate.now(CIVIL_ZONE))));

        // A line per borrower who will be covered: the lender's own line number, so it reads beside their file.
        List<Column> columns = List.of(new Column("Line", Kind.TEXT, 0.6f), new Column("Borrower", Kind.TEXT, 2.6f),
            new Column("Loan (" + currency + ")", Kind.MONEY, 1.5f), new Column("Loan start", Kind.TEXT, 1.1f),
            new Column("Months", Kind.TEXT, 0.7f), new Column("Premium (" + currency + ")", Kind.MONEY, 1.4f));
        List<List<Object>> rows = q.lines().stream()
            .map(l -> List.<Object>of(String.valueOf(l.lineNumber()),
                l.borrowerFullName() == null ? "-" : l.borrowerFullName(),
                l.loanAmount() == null ? "-" : l.loanAmount(),
                l.loanStartDate() == null ? "-" : DMY.format(l.loanStartDate()),
                l.loanTermMonths() == null ? "-" : String.valueOf(l.loanTermMonths()),
                l.premium()))
            .toList();

        List<Field> totals = new ArrayList<>();
        totals.add(new Field("Borrowers covered", String.valueOf(q.borrowers())));
        totals.add(new Field("Total lent", money(q.totalLoanAmount(), currency)));
        totals.add(new Field(accepted ? "Premium charged" : "Premium to pay", money(q.amountToPay(), currency)));
        if (q.commission() != null && q.commission().signum() > 0) {
            totals.add(new Field(q.commissionPercent() != null
                ? "Lender's commission (" + q.commissionPercent().stripTrailingZeros().toPlainString() + "%)"
                : "Lender's commission", money(q.commission(), currency)));
        }

        List<String> notes = new ArrayList<>();
        notes.add(accepted
            ? "This file has been accepted: the borrowers above are covered from their loan start dates."
            : "Each borrower above is covered from their loan start date once this file is accepted.");
        if (file.rejectedCount() > 0) {
            notes.add(file.rejectedCount() + (file.rejectedCount() == 1 ? " row was" : " rows were")
                + " refused and is not covered; the report for this file says why.");
        }
        if (q.difference() != null && q.difference().signum() != 0) {
            notes.add("Your file stated a premium of " + money(q.statedPremium(), currency) + "; the premium is "
                + money(q.amountToPay(), currency) + ".");
        }
        if (q.commission() != null && q.commission().signum() > 0) {
            notes.add("The full premium is paid to the insurer; the lender's commission is paid back once it is received.");
        }
        notes.add("Cover follows each loan's balance as it is repaid. A claim pays what the borrower still owed, from the"
            + " lender's statement, never more than their cover.");

        List<String> signOff = new ArrayList<>(notes);
        signOff.add("");
        signOff.add("Signature for the lender ...........................................    Date ......................");
        signOff.add("(" + lenderName + ")");
        signOff.add("");
        signOff.add("Signature (authorised officer) ..................................    Date ......................");
        return new CustomerDocument(ISSUER, "Credit life quotation", fields, columns, rows, totals, signOff, false);
    }
}
