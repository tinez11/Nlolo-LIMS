package tz.co.nlolo.lifeplatform.finaccounting.application;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.approvals.api.OwnWork;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountBalanceView;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingValidationException;
import tz.co.nlolo.lifeplatform.finaccounting.api.LineDimensions;
import tz.co.nlolo.lifeplatform.finaccounting.api.PeriodStatus;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingMode;
import tz.co.nlolo.lifeplatform.finaccounting.api.ShareRegisterApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.ShareRegisterNotFoundException;
import tz.co.nlolo.lifeplatform.finaccounting.api.ShareRegisterStateException;
import tz.co.nlolo.lifeplatform.finaccounting.domain.CapitalChecks;
import tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ShareIssueRules;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.ChartOfAccountRepository;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.ChartOfAccountSeeder;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The shareholders register (2026-11-14, spec 2026-11-14-shareholders-register-design.md). An issue of shares is
 * recorded by one person and approved by a second -- or by the recorder holding ledger.approve-own -- and approving it
 * posts one MANUAL journal in the month the money came: Dr the bank, Cr share capital (shares × nominal value), Cr
 * share premium (the rest). The issue's approval and its journal are one transaction, so the register and the books
 * cannot drift apart; the "register agrees with the books" check would show it if a hand-made journal moved them.
 */
@Service
public class ShareRegister implements ShareRegisterApi {

    static final String EVENT = "finaccounting.ShareIssue";
    private static final ZoneId CIVIL = ZoneId.of("Africa/Dar_es_Salaam");
    private static final Set<String> CAPITAL_ACCOUNTS = Set.of("3110", "3120", "3130");
    private static final String ISSUE_SELECT = "SELECT i.*, s.name AS shareholder_name FROM finaccounting.share_issue i"
        + " JOIN finaccounting.shareholder s ON s.shareholder_id = i.shareholder_id WHERE i.tenant_id = ?";

    private final JdbcTemplate jdbc;
    private final FinaccountingApiImpl ledger;
    private final FinaccountingApi finance;
    private final AccountingPeriods periods;
    private final ChartOfAccountSeeder seeder;
    private final ChartOfAccountRepository chart;
    private final OwnWork ownWork;
    private final TransactionTemplate tx;

    ShareRegister(JdbcTemplate jdbc, FinaccountingApiImpl ledger, FinaccountingApi finance, AccountingPeriods periods,
                  ChartOfAccountSeeder seeder, ChartOfAccountRepository chart, OwnWork ownWork,
                  PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.finance = finance;
        this.periods = periods;
        this.seeder = seeder;
        this.chart = chart;
        this.ownWork = ownWork;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ---- shareholders ---------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<ShareholderView> shareholders() {
        return jdbc.query("SELECT s.*, COALESCE(sum(i.shares) FILTER (WHERE i.status = 'APPROVED'), 0) AS held,"
                + " COALESCE(sum(i.amount_paid) FILTER (WHERE i.status = 'APPROVED'), 0) AS paid"
                + " FROM finaccounting.shareholder s"
                + " LEFT JOIN finaccounting.share_issue i ON i.shareholder_id = s.shareholder_id"
                + " WHERE s.tenant_id = ? GROUP BY s.shareholder_id ORDER BY s.name",
            (rs, n) -> new ShareholderView(rs.getObject("shareholder_id", UUID.class), rs.getString("name"),
                rs.getString("kind"), rs.getBoolean("tanzanian"), rs.getString("id_number"), rs.getString("phone"),
                rs.getString("address"), rs.getLong("held"), rs.getBigDecimal("paid")),
            TenantContext.get());
    }

    @Override
    @Transactional
    public ShareholderView addShareholder(ShareholderInput in, String by) {
        validate(in);
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO finaccounting.shareholder (shareholder_id, tenant_id, name, kind, tanzanian, id_number,"
                    + " phone, address, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, TenantContext.get(), in.name().trim(), in.kind(), in.tanzanian(), in.idNumber().trim(),
                blankToNull(in.phone()), blankToNull(in.address()), by);
        } catch (DuplicateKeyException e) {
            throw new ShareRegisterStateException("A shareholder with ID " + in.idNumber().trim()
                + " is already registered");
        }
        return shareholder(id);
    }

    @Override
    @Transactional
    public ShareholderView updateShareholder(UUID id, ShareholderInput in, String by) {
        validate(in);
        shareholder(id);
        try {
            jdbc.update("UPDATE finaccounting.shareholder SET name = ?, kind = ?, tanzanian = ?, id_number = ?, phone = ?,"
                    + " address = ? WHERE tenant_id = ? AND shareholder_id = ?",
                in.name().trim(), in.kind(), in.tanzanian(), in.idNumber().trim(), blankToNull(in.phone()),
                blankToNull(in.address()), TenantContext.get(), id);
        } catch (DuplicateKeyException e) {
            throw new ShareRegisterStateException("A shareholder with ID " + in.idNumber().trim()
                + " is already registered");
        }
        return shareholder(id);
    }

    private ShareholderView shareholder(UUID id) {
        return shareholders().stream().filter(s -> s.shareholderId().equals(id)).findFirst()
            .orElseThrow(() -> new ShareRegisterNotFoundException("Shareholder " + id + " not found"));
    }

    private static void validate(ShareholderInput in) {
        if (in == null || in.name() == null || in.name().isBlank()) {
            throw new FinaccountingValidationException("Enter the shareholder's name");
        }
        if (!"PERSON".equals(in.kind()) && !"COMPANY".equals(in.kind())) {
            throw new FinaccountingValidationException("Say whether the shareholder is a person or a company");
        }
        if (in.idNumber() == null || in.idNumber().isBlank()) {
            throw new FinaccountingValidationException("Enter the ID or company registration number");
        }
    }

    // ---- classes --------------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<ShareClassView> classes() {
        return jdbc.query("SELECT c.*, EXISTS (SELECT 1 FROM finaccounting.share_issue i WHERE i.tenant_id = c.tenant_id"
                + " AND i.class_code = c.class_code AND i.status = 'APPROVED') AS issued"
                + " FROM finaccounting.share_class c WHERE c.tenant_id = ? ORDER BY c.class_code",
            (rs, n) -> new ShareClassView(rs.getString("class_code"), rs.getBigDecimal("nominal_value"),
                rs.getString("set_by"), rs.getTimestamp("set_at").toInstant(), rs.getBoolean("issued")),
            TenantContext.get());
    }

    @Override
    @Transactional
    public ShareClassView setNominal(String classCode, BigDecimal nominalValue, String by) {
        if (!"ORDINARY".equals(classCode) && !"PREFERENCE".equals(classCode)) {
            throw new FinaccountingValidationException("A share class is ORDINARY or PREFERENCE");
        }
        if (nominalValue == null || nominalValue.signum() <= 0) {
            throw new FinaccountingValidationException("The nominal value is more than zero");
        }
        if (classes().stream().anyMatch(c -> c.classCode().equals(classCode) && c.issued())) {
            throw new ShareRegisterStateException("Shares of this class are already issued; the nominal value cannot"
                + " change");
        }
        jdbc.update("INSERT INTO finaccounting.share_class (tenant_id, class_code, nominal_value, set_by, set_at)"
                + " VALUES (?, ?, ?, ?, now()) ON CONFLICT (tenant_id, class_code)"
                + " DO UPDATE SET nominal_value = EXCLUDED.nominal_value, set_by = EXCLUDED.set_by, set_at = now()",
            TenantContext.get(), classCode, nominalValue, by);
        return classes().stream().filter(c -> c.classCode().equals(classCode)).findFirst().orElseThrow();
    }

    // ---- issues ---------------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<IssueView> issues(String status) {
        if (status == null || status.isBlank()) {
            return jdbc.query(ISSUE_SELECT + " ORDER BY i.proposed_at DESC", ShareRegister::issue, TenantContext.get());
        }
        return jdbc.query(ISSUE_SELECT + " AND i.status = ? ORDER BY i.proposed_at DESC", ShareRegister::issue,
            TenantContext.get(), status.trim());
    }

    @Override
    @Transactional
    public IssueView propose(IssueInput in, String by) {
        if (in == null || in.shareholderId() == null) {
            throw new FinaccountingValidationException("Choose the shareholder");
        }
        seeder.seedIfAbsent(TenantContext.get(), by);
        shareholder(in.shareholderId());
        BigDecimal nominal = classes().stream().filter(c -> c.classCode().equals(in.classCode())).findFirst()
            .map(ShareClassView::nominalValue)
            .orElseThrow(() -> new FinaccountingValidationException("Set the nominal value of "
                + (in.classCode() == null ? "the" : in.classCode().toLowerCase()) + " shares first"));
        if (in.pricePerShare() == null || in.amountPaid() == null || in.receivedOn() == null || in.shares() <= 0) {
            throw new FinaccountingValidationException("Enter the shares, the price, the amount paid and the date received");
        }
        if (in.reference() == null || in.reference().isBlank()) {
            throw new FinaccountingValidationException("Name the board resolution or allotment the issue rests on");
        }
        if (in.reference().trim().length() > 200) {
            throw new FinaccountingValidationException("The reference is at most 200 characters");
        }
        if (bankAccounts().stream().noneMatch(b -> b.code().equals(in.bankAccount()))) {
            throw new FinaccountingValidationException("Account " + in.bankAccount()
                + " cannot take a share issue; choose a bank account finance posts to by hand");
        }
        List<String> problems = ShareIssueRules.problems(in.shares(), nominal, in.pricePerShare(), in.amountPaid(),
            in.receivedOn(), LocalDate.now(CIVIL));
        if (!problems.isEmpty()) {
            throw new FinaccountingValidationException(String.join("; ", problems));
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO finaccounting.share_issue (issue_id, tenant_id, shareholder_id, class_code, shares,"
                + " price_per_share, nominal_value, amount_paid, received_on, bank_account, reference, proposed_by)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, TenantContext.get(), in.shareholderId(), in.classCode(), in.shares(), in.pricePerShare(), nominal,
            in.amountPaid(), Date.valueOf(in.receivedOn()), in.bankAccount(), in.reference().trim(), by);
        return issue(id);
    }

    @Override
    @Transactional
    public IssueView withdraw(UUID issueId, String by) {
        IssueView i = issue(issueId);
        if (!i.proposedBy().equals(by)) {
            throw new ShareRegisterStateException("Only the person who recorded it can withdraw it");
        }
        move(issueId, "WITHDRAWN", by, null, false, null);
        return issue(issueId);
    }

    /**
     * Checked again, then posted in one transaction with the issue's approval: approved and posted, or neither. A
     * refusal by the ledger's own guards comes back as a 409 with the guard's words, as for a manual journal.
     */
    @Override
    public IssueView approve(UUID issueId, String by) {
        UUID tenantId = TenantContext.get();
        IssueView i = issue(issueId);
        boolean alone = decidable(i, by, "approve");
        String period = YearMonth.from(i.receivedOn()).toString();
        if (periods.view(period).status() == PeriodStatus.LOCKED) {
            throw new ShareRegisterStateException("That month is locked");
        }
        try {
            tx.executeWithoutResult(status -> {
                seeder.seedIfAbsent(tenantId, by);
                JournalEntry entry = new JournalEntry(tenantId, EVENT, issueId.toString(), period, null, by)
                    .asManual(i.proposedBy(), by, "Shares issued to " + i.shareholderName() + " (" + i.reference() + ")",
                        "SHARE_CAPITAL", null);
                entry.addLeg(i.bankAccount(), PostingDirection.DR, i.amountPaid(), "TZS", LineDimensions.NONE);
                entry.addLeg(ShareIssueRules.capitalAccount(i.classCode()), PostingDirection.CR, i.shareCapital(), "TZS",
                    LineDimensions.NONE);
                if (i.sharePremium().signum() > 0) {
                    entry.addLeg(ShareIssueRules.PREMIUM_ACCOUNT, PostingDirection.CR, i.sharePremium(), "TZS",
                        LineDimensions.NONE);
                }
                JournalEntry posted = ledger.postEntry(entry)
                    .orElseThrow(() -> new ShareRegisterStateException("This issue was already posted"));
                move(issueId, "APPROVED", by, null, alone, posted.getJournalEntryId());
                if (alone) {
                    ownWork.record("Share issue approved by the person who recorded it", issueId.toString(), null, by);
                }
            });
        } catch (ShareRegisterStateException e) {
            throw e;
        } catch (RuntimeException e) {
            String guard = ManualJournalApiImpl.ledgerGuard(e);
            if (guard != null) {
                throw new ShareRegisterStateException("The ledger refused this issue: " + guard);
            }
            throw e;
        }
        return issue(issueId);
    }

    @Override
    @Transactional
    public IssueView reject(UUID issueId, String reason, String by) {
        IssueView i = issue(issueId);
        boolean alone = decidable(i, by, "reject");
        if (reason == null || reason.isBlank()) {
            throw new FinaccountingValidationException("Say why the issue is rejected");
        }
        move(issueId, "REJECTED", by, reason.trim(), alone, null);
        if (alone) {
            ownWork.record("Share issue rejected by the person who recorded it", issueId.toString(), null, by);
        }
        return issue(issueId);
    }

    /** A recorded issue, and a decider who did not record it -- unless they hold ledger.approve-own. True when alone. */
    private boolean decidable(IssueView i, String by, String action) {
        if (!"PROPOSED".equals(i.status())) {
            throw new ShareRegisterStateException("Only a recorded issue can be " + action + "d; this one is "
                + i.status().toLowerCase());
        }
        if (!i.proposedBy().equals(by)) {
            return false;
        }
        if (!ownWork.mayActAlone("ledger.approve-own")) {
            throw new ShareRegisterStateException("You recorded this issue; a second person must " + action + " it");
        }
        return true;
    }

    private void move(UUID issueId, String to, String by, String reason, boolean alone, UUID journalId) {
        int n = jdbc.update("UPDATE finaccounting.share_issue SET status = ?, decided_by = ?, decided_at = now(),"
                + " decision_reason = ?, acted_alone = ?, journal_entry_id = ? WHERE tenant_id = ? AND issue_id = ?"
                + " AND status = 'PROPOSED'",
            to, by, reason, alone, journalId, TenantContext.get(), issueId);
        if (n == 0) {
            throw new ShareRegisterStateException("This issue is no longer waiting for a decision");
        }
    }

    private IssueView issue(UUID id) {
        return jdbc.query(ISSUE_SELECT + " AND i.issue_id = ?", ShareRegister::issue, TenantContext.get(), id)
            .stream().findFirst()
            .orElseThrow(() -> new ShareRegisterNotFoundException("Share issue " + id + " not found"));
    }

    private static IssueView issue(ResultSet rs, int n) throws SQLException {
        long shares = rs.getLong("shares");
        var split = ShareIssueRules.split(shares, rs.getBigDecimal("nominal_value"), rs.getBigDecimal("price_per_share"),
            rs.getBigDecimal("amount_paid"));
        Timestamp decided = rs.getTimestamp("decided_at");
        return new IssueView(rs.getObject("issue_id", UUID.class), rs.getObject("shareholder_id", UUID.class),
            rs.getString("shareholder_name"), rs.getString("class_code"), shares, rs.getBigDecimal("price_per_share"),
            rs.getBigDecimal("nominal_value"), rs.getBigDecimal("amount_paid"), split.capital(), split.premium(),
            rs.getDate("received_on").toLocalDate(), rs.getString("bank_account"), rs.getString("reference"),
            rs.getString("status"), rs.getString("proposed_by"), rs.getTimestamp("proposed_at").toInstant(),
            rs.getString("decided_by"), decided == null ? null : decided.toInstant(), rs.getString("decision_reason"),
            rs.getObject("journal_entry_id", UUID.class), rs.getBoolean("acted_alone"));
    }

    // ---- capital --------------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public CapitalView capital() {
        UUID tenantId = TenantContext.get();
        Map<String, Object> reg = jdbc.queryForMap("SELECT COALESCE(sum(i.amount_paid), 0) AS paid,"
                + " COALESCE(sum(i.shares), 0) AS shares,"
                + " COALESCE(sum(i.shares) FILTER (WHERE s.tanzanian), 0) AS tz"
                + " FROM finaccounting.share_issue i JOIN finaccounting.shareholder s ON s.shareholder_id = i.shareholder_id"
                + " WHERE i.tenant_id = ? AND i.status = 'APPROVED'", tenantId);
        BigDecimal paid = new BigDecimal(String.valueOf(reg.get("paid")));
        long shares = ((Number) reg.get("shares")).longValue();
        long tz = ((Number) reg.get("tz")).longValue();
        BigDecimal ledgerCapital = BigDecimal.ZERO;
        for (AccountBalanceView a : finance.trialBalance(null).accounts()) {
            if (CAPITAL_ACCOUNTS.contains(a.accountCode())) {
                ledgerCapital = ledgerCapital.add(a.ownCredit().subtract(a.ownDebit()));
            }
        }
        List<Object[]> latest = jdbc.query("SELECT minimum_paid_up, set_by, set_at FROM finaccounting.capital_minimum"
                + " WHERE tenant_id = ? ORDER BY set_at DESC LIMIT 1",
            (rs, n) -> new Object[] {rs.getBigDecimal(1), rs.getString(2), rs.getTimestamp(3).toInstant()}, tenantId);
        BigDecimal minimum = latest.isEmpty() ? null : (BigDecimal) latest.get(0)[0];
        CapitalChecks.Result r = CapitalChecks.of(paid, ledgerCapital, tz, shares, minimum);
        return new CapitalView(paid, ledgerCapital, shares, minimum,
            latest.isEmpty() ? null : (String) latest.get(0)[1], latest.isEmpty() ? null : (Instant) latest.get(0)[2],
            new CheckView(r.books().state(), r.books().figure()),
            new CheckView(r.tanzanian().state(), r.tanzanian().figure()),
            new CheckView(r.minimum().state(), r.minimum().figure()));
    }

    @Override
    @Transactional
    public CapitalView setMinimum(BigDecimal minimumPaidUp, String by) {
        if (minimumPaidUp != null && minimumPaidUp.signum() <= 0) {
            throw new FinaccountingValidationException("The minimum is more than zero, or empty for Not set");
        }
        jdbc.update("INSERT INTO finaccounting.capital_minimum (tenant_id, set_at, minimum_paid_up, set_by)"
            + " VALUES (?, clock_timestamp(), ?, ?)", TenantContext.get(), minimumPaidUp, by);
        return capital();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BankAccountView> bankAccounts() {
        return chart.findByTenantIdOrderByAccountCodeAsc(TenantContext.get()).stream()
            .filter(a -> a.getAccountCode().startsWith("11") && a.isPostingAllowed()
                && (a.getMode() == PostingMode.MAN || a.getMode() == PostingMode.BOTH))
            .map(a -> new BankAccountView(a.getAccountCode(), a.getName()))
            .toList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
