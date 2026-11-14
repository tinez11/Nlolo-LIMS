package tz.co.nlolo.lifeplatform.finaccounting.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The shareholders register (2026-11-14, spec 2026-11-14-shareholders-register-design.md): who holds the company's
 * shares, each issue of shares -- recorded by one person, approved by a second, and posted on approval -- and the three
 * capital checks.
 */
public interface ShareRegisterApi {

    record ShareholderInput(String name, String kind, boolean tanzanian, String idNumber, String phone, String address) {}

    /** {@code shares} and {@code paid} are through approved issues only. */
    record ShareholderView(UUID shareholderId, String name, String kind, boolean tanzanian, String idNumber, String phone,
                           String address, long shares, BigDecimal paid) {}

    /** {@code issued}: an issue of this class is approved, so its nominal value is fixed. */
    record ShareClassView(String classCode, BigDecimal nominalValue, String setBy, Instant setAt, boolean issued) {}

    record IssueInput(UUID shareholderId, String classCode, long shares, BigDecimal pricePerShare, BigDecimal amountPaid,
                      LocalDate receivedOn, String bankAccount, String reference) {}

    record IssueView(UUID issueId, UUID shareholderId, String shareholderName, String classCode, long shares,
                     BigDecimal pricePerShare, BigDecimal nominalValue, BigDecimal amountPaid, BigDecimal shareCapital,
                     BigDecimal sharePremium, LocalDate receivedOn, String bankAccount, String reference, String status,
                     String proposedBy, Instant proposedAt, String decidedBy, Instant decidedAt, String decisionReason,
                     UUID journalEntryId, boolean actedAlone) {}

    /** {@code state}: MET, NOT_MET or NOT_SET. */
    record CheckView(String state, String figure) {}

    /** {@code paidUpCapital}: the register's approved issues; {@code ledgerCapital}: 3110 + 3120 + 3130 in the books. */
    record CapitalView(BigDecimal paidUpCapital, BigDecimal ledgerCapital, long issuedShares, BigDecimal minimumPaidUp,
                       String minimumSetBy, Instant minimumSetAt, CheckView books, CheckView tanzanian,
                       CheckView minimum) {}

    record BankAccountView(String code, String name) {}

    List<ShareholderView> shareholders();

    ShareholderView addShareholder(ShareholderInput input, String by);

    ShareholderView updateShareholder(UUID id, ShareholderInput input, String by);

    List<ShareClassView> classes();

    ShareClassView setNominal(String classCode, BigDecimal nominalValue, String by);

    /** Newest first; {@code status} null for all. */
    List<IssueView> issues(String status);

    IssueView propose(IssueInput input, String by);

    IssueView withdraw(UUID issueId, String by);

    IssueView approve(UUID issueId, String by);

    IssueView reject(UUID issueId, String reason, String by);

    CapitalView capital();

    /** Null clears it: "Not set". */
    CapitalView setMinimum(BigDecimal minimumPaidUp, String by);

    /** The accounts a share issue may debit: under 1100, posting allowed, MAN or BOTH. */
    List<BankAccountView> bankAccounts();
}
