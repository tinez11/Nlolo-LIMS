package tz.co.nlolo.lifeplatform.finaccounting.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PostingFacts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What PAA premium has still to be earned (IFRS 17 I3a, guide I-03), kept as an earning schedule beside the ledger.
 *
 * <p>A PAA invoice's premium is earned straight-line over the cover it pays for: an instalment over the period it is
 * billed for; a credit-life enrolment file over EACH BORROWER'S OWN loan term (user decision 2026-10-06), so a file is
 * one schedule row per borrower. Billing states the cover on the invoice event ({@code covers}); an invoice that does
 * not is earned over the twelve months from its posting, and says so in the log.
 *
 * <p>A waiver, a credit or a downward restatement of a PAA invoice reduces what is left to earn: a credit names the
 * borrower it is for, and reduces that borrower's row; otherwise the invoice's rows share the reduction in proportion
 * to what each has left. An upward restatement raises the invoice's rows the same way. Written in the posting's own
 * transaction, so the schedule and the ledger never disagree about an invoice.
 */
@Component
class PaaEarningSchedule {

    private static final Logger log = LoggerFactory.getLogger(PaaEarningSchedule.class);

    private final JdbcTemplate jdbc;

    PaaEarningSchedule(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One cover an invoice pays for. {@code memberRef} is empty for an invoice with one cover. */
    record Cover(String memberRef, LocalDate from, LocalDate to, BigDecimal amount) {}

    void afterPosting(UUID tenantId, PostingFacts facts, PolicyClassifier.InForce classification, UUID journalEntryId) {
        BigDecimal amount = facts.amount(PostingFactsExtractor.AMOUNT);
        switch (facts.eventType()) {
            case PaaEarningJob.EVENT -> earned(tenantId, facts, journalEntryId);
            case "billing.PremiumInvoiceGenerated" -> record(tenantId, facts, classification, amount);
            case "billing.InvoiceWaived" -> reduce(tenantId, facts.sourceRef(), null, amount);
            case "billing.PremiumRefundDue" -> reduce(tenantId, facts.attribute("invoiceRef"), facts.attribute("memberRef"), amount);
            case "billing.PremiumInvoiceReduced" -> reduce(tenantId, facts.attribute("invoiceRef"), null, amount);
            case "billing.PremiumInvoiceIncreased" -> increase(tenantId, facts.attribute("invoiceRef"), amount);
            default -> { /* posts nothing to the schedule */ }
        }
    }

    private void record(UUID tenantId, PostingFacts facts, PolicyClassifier.InForce classification, BigDecimal amount) {
        List<Cover> covers = parse(facts.attribute("covers"));
        if (covers.isEmpty()) {
            log.warn("PAA invoice {} on {} states no cover; earning it over the twelve months from {}",
                facts.sourceRef(), facts.policyNumber(), facts.eventDate());
            covers = List.of(new Cover("", facts.eventDate(), facts.eventDate().plusYears(1).minusDays(1), amount));
        }
        for (Cover c : covers) {
            if (c.amount().signum() <= 0) {
                continue;
            }
            jdbc.update("INSERT INTO finaccounting.paa_earning (tenant_id, policy_number, invoice_ref, member_ref, group_key,"
                    + " currency, covers_from, covers_to, amount) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                    + " ON CONFLICT (tenant_id, invoice_ref, member_ref) DO NOTHING",
                tenantId, facts.policyNumber(), facts.sourceRef(), c.memberRef(), classification.groupKey(),
                facts.currency(), Date.valueOf(c.from()), Date.valueOf(c.to()), c.amount());
        }
    }

    private void reduce(UUID tenantId, String invoiceRef, String memberRef, BigDecimal amount) {
        if (invoiceRef == null || amount.signum() <= 0) {
            return;
        }
        if (memberRef != null) {
            int n = jdbc.update("UPDATE finaccounting.paa_earning SET reduced = LEAST(amount - earned, reduced + ?)"
                + " WHERE tenant_id = ? AND invoice_ref = ? AND member_ref = ?", amount, tenantId, invoiceRef, memberRef);
            if (n > 0) {
                return;
            }
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT earning_id, amount - reduced - earned AS left_to_earn"
            + " FROM finaccounting.paa_earning WHERE tenant_id = ? AND invoice_ref = ? ORDER BY member_ref", tenantId, invoiceRef);
        BigDecimal left = rows.stream().map(r -> (BigDecimal) r.get("left_to_earn")).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (left.signum() <= 0) {
            return;
        }
        BigDecimal toReduce = amount.min(left);
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < rows.size(); i++) {
            BigDecimal rowLeft = (BigDecimal) rows.get(i).get("left_to_earn");
            BigDecimal share = i == rows.size() - 1 ? toReduce.subtract(allocated)
                : toReduce.multiply(rowLeft).divide(left, 2, RoundingMode.HALF_EVEN);
            share = share.min(rowLeft).max(BigDecimal.ZERO);
            allocated = allocated.add(share);
            jdbc.update("UPDATE finaccounting.paa_earning SET reduced = reduced + ? WHERE earning_id = ?",
                share, rows.get(i).get("earning_id"));
        }
    }

    private void increase(UUID tenantId, String invoiceRef, BigDecimal amount) {
        if (invoiceRef == null || amount.signum() <= 0) {
            return;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT earning_id, amount FROM finaccounting.paa_earning"
            + " WHERE tenant_id = ? AND invoice_ref = ? ORDER BY member_ref", tenantId, invoiceRef);
        BigDecimal total = rows.stream().map(r -> (BigDecimal) r.get("amount")).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < rows.size(); i++) {
            BigDecimal share = i == rows.size() - 1 ? amount.subtract(allocated)
                : amount.multiply((BigDecimal) rows.get(i).get("amount")).divide(total, 2, RoundingMode.HALF_EVEN);
            allocated = allocated.add(share);
            jdbc.update("UPDATE finaccounting.paa_earning SET amount = amount + ? WHERE earning_id = ?",
                share, rows.get(i).get("earning_id"));
        }
    }

    /**
     * A month's earning posted: each row it earned from records what, in the journal's own transaction, so the
     * schedule says exactly what the ledger holds. The facts carry {@code earningId=amount;...} as the job wrote them.
     */
    private void earned(UUID tenantId, PostingFacts facts, UUID journalEntryId) {
        String period = facts.sourceRef().substring(facts.sourceRef().lastIndexOf(':') + 1);
        for (String entry : facts.attribute("earnings").split(";")) {
            String[] f = entry.split("=");
            UUID earningId = UUID.fromString(f[0]);
            BigDecimal amount = new BigDecimal(f[1]);
            jdbc.update("INSERT INTO finaccounting.paa_earning_run (tenant_id, earning_id, period, amount, journal_entry_id)"
                + " VALUES (?, ?, ?, ?, ?)", tenantId, earningId, period, amount, journalEntryId);
            jdbc.update("UPDATE finaccounting.paa_earning SET earned = earned + ?, earned_through = ? WHERE earning_id = ?",
                amount, Date.valueOf(facts.eventDate()), earningId);
        }
    }

    /** Reads {@code member|from|to|amount;...} as {@code PostingFactsExtractor} wrote it. */
    static List<Cover> parse(String raw) {
        List<Cover> covers = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return covers;
        }
        for (String entry : raw.split(";")) {
            String[] f = entry.split("\\|", -1);
            covers.add(new Cover(f[0], LocalDate.parse(f[1]), LocalDate.parse(f[2]), new BigDecimal(f[3])));
        }
        return covers;
    }
}
