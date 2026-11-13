package tz.co.nlolo.lifeplatform.finaccounting.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PostingFacts;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PostingRuleValidator.EventShape;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns an event's payload into the facts the posting rules read (IFRS 17 I3a). One job: what happened, in named
 * amounts and attributes -- never which accounts it posts to, which is the rules file's. Pure: no Spring, no I/O.
 *
 * <p>Every event here posted before the rules engine, and is keyed exactly as it was (the same source ref), so a
 * journal posted under the old listeners is recognised and never posted again.
 *
 * <p>Attributes every fact may carry, read by the engine rather than by rules: {@code refType} (the line's reference
 * type dimension), {@code fund} (the fund dimension), {@code covers} (what a PAA invoice pays for, read by the PAA
 * earning schedule).
 */
final class PostingFactsExtractor {

    private static final Logger log = LoggerFactory.getLogger(PostingFactsExtractor.class);

    static final String AMOUNT = "amount";

    private static EventShape amount(String... attributes) {
        return new EventShape(Set.of(AMOUNT), Set.of(attributes));
    }

    /** What each event offers the rules. The validator refuses a rule naming anything else. */
    static final Map<String, EventShape> SHAPES = Map.ofEntries(
        Map.entry("billing.PremiumInvoiceGenerated", amount()),
        // levy: derived by the engine at the rate in force (A-19), zero while none is configured.
        Map.entry("billing.PremiumCollected", new EventShape(Set.of(AMOUNT, "levy"), Set.of())),
        // Money movement step 2 (2026-11-04): cash onto a policy's balance, and a premium paid from it (levy derived by
        // the engine, as it was for PremiumCollected).
        Map.entry("billing.DepositReceived", amount()),
        Map.entry("billing.PremiumPaidFromBalance", new EventShape(Set.of(AMOUNT, "levy"), Set.of())),
        // Money movement step 3a: a withdrawal from the balance -- approved, paid, or returned by a refused transfer.
        Map.entry("billing.BalanceWithdrawalApproved", amount()),
        Map.entry("billing.BalanceWithdrawalPaid", amount()),
        Map.entry("billing.BalanceWithdrawalReturned", amount()),
        // Money movement step 3b: a deposit reversed, and a premium paid from it undone (levy derived by the engine).
        Map.entry("billing.DepositReversed", amount()),
        // Agent cash reaching the company (2026-11-11): from 1320 Due from agents to the account it reached.
        Map.entry("billing.AgentCashBanked", amount()),
        Map.entry("billing.PremiumPaidReversed", new EventShape(Set.of(AMOUNT, "levy"), Set.of())),
        Map.entry("billing.InvoiceWaived", amount()),
        // A waiver taken back (2026-11-08): the mirror of the waiver it undoes.
        Map.entry("billing.InvoiceWaiverUndone", amount()),
        Map.entry("billing.PremiumRefundDue", amount()),
        // A credit-life lender's premium paid back (2026-10-30): the bank side of the refund above.
        Map.entry("billing.PremiumRefundPaid", amount()),
        Map.entry("billing.PremiumInvoiceIncreased", amount()),
        Map.entry("billing.PremiumInvoiceReduced", amount()),
        // IFRS 17 I3b: money out is two steps (guide B, C, D, H) -- the payable when it is decided, the bank when paid.
        Map.entry("claims.ClaimApproved", new EventShape(Set.of(AMOUNT, "investmentComponent", "insured"), Set.of())),
        Map.entry("claims.ClaimSettled", amount()),
        Map.entry("policy.SurrenderPayoutRequested", amount()),
        Map.entry("policy.SurrenderPaid", amount()),
        Map.entry("benefitpayout.PayoutRequested",
            new EventShape(Set.of(AMOUNT, "gross", "investmentComponent", "insured"), Set.of("kind"))),
        Map.entry("benefitpayout.PayoutPaid", new EventShape(Set.of("gross", "paid", "withheld"), Set.of("kind"))),
        Map.entry("benefitpayout.FreeLookRefundPaid", amount()),
        Map.entry("distribution.CommissionAccrued", amount("direction", "channel", "movement")),
        Map.entry("distribution.CommissionPaid", new EventShape(Set.of("gross", "paid", "withheld"), Set.of("channel"))),
        Map.entry("policyloan.LoanDisbursed", amount()),
        // receiptMethod names the payout a loan was repaid out of (CLAIM_OFFSET...), 2026-11-07: its rules test it.
        Map.entry("policyloan.LoanRepaid", new EventShape(Set.of(AMOUNT, "interest", "principal"), Set.of("receiptMethod"))),
        // Money above what a loan owed (money movement step 1): its method rides as receiptMethod, as on LoanRepaid.
        Map.entry("policyloan.LoanOverpaid", amount()),
        Map.entry("policyloan.LoanInterestAccrued", amount()),
        Map.entry("policyloan.LoanForcedLapseTriggered", new EventShape(Set.of("total", "principal", "interest"), Set.of())),
        Map.entry("accumulation.PostingRecorded", amount("flow", "receiptMethod")),
        Map.entry("accumulation.PayoutRequested", new EventShape(Set.of("charge"), Set.of())),
        Map.entry("accumulation.PayoutPaid", amount()),
        // IFRS 17 I3c: reinsurance held from the monthly bordereau (K-01/K-02) and a recovery at claim approval (B-05).
        Map.entry("reinsurance.BordereauPosted", new EventShape(Set.of("premium", "commission", "premiumReversed",
            "commissionReversed"), Set.of())),
        Map.entry("reinsurance.RecoveryCalculated", amount()),
        // IFRS 17 I3d: the quarterly statement (R-01, R-03, R-04). premiumNet is the premium less what is withheld.
        Map.entry("reinsurance.StatementApproved", new EventShape(Set.of("premiumNet", "withheld", "commission",
            "recoveries", "owedToUs", "owedByUs", "profitCommission"), Set.of())),
        // Settling an approved statement (2026-11-08): money in names how it came (receiptMethod); money out names its
        // payout (paymentRef), whose rail the engine reads; a release of funds withheld moves no money.
        Map.entry("reinsurance.SettlementReceived", new EventShape(Set.of(AMOUNT), Set.of("receiptMethod"))),
        Map.entry("reinsurance.ProfitCommissionReceived", new EventShape(Set.of(AMOUNT), Set.of("receiptMethod"))),
        Map.entry("reinsurance.FundsWithheldReleased", amount()),
        Map.entry("reinsurance.SettlementPaid", amount()),
        // The platform's own: a month's PAA premium earned (PaaEarningJob), never an event from another module.
        Map.entry(PaaEarningJob.EVENT, amount()),
        Map.entry("unitlinked.UnitsAllocated", new EventShape(Set.of("premium", "allocated", "allocationCharge"), Set.of())),
        Map.entry("unitlinked.ChargesTaken", new EventShape(Set.of("policyFee", "costOfInsurance"), Set.of())),
        Map.entry("unitlinked.FundRevalued", new EventShape(Set.of("delta"), Set.of("sign"))),
        Map.entry("unitlinked.ExitPriced", amount("purpose")),
        Map.entry("unitlinked.PremiumReturned", new EventShape(Set.of("returned", "allocationCharge", "total"), Set.of())),
        Map.entry("unitlinked.ChargeRefunded", amount()),
        Map.entry("unitlinked.UnitsReinvested", amount()),
        Map.entry("unitlinked.PriceCorrected", new EventShape(Set.of("difference"), Set.of("part", "sign"))),
        Map.entry("unitlinked.PayoutPaid", amount("purpose")),
        Map.entry("unitlinked.SwitchExecuted", new EventShape(Set.of("fee"), Set.of())),
        Map.entry("unitlinked.WithdrawalPriced", new EventShape(Set.of("proceeds", "surrenderCharge"), Set.of("part"))),
        Map.entry("unitlinked.SurrenderCharged", amount()),
        Map.entry("unitlinked.TopUpReceived", new EventShape(Set.of(AMOUNT), Set.of("receiptMethod"))),
        Map.entry("unitlinked.AdjustmentCollected", amount()),
        Map.entry("unitlinked.AdjustmentWaived", amount("direction")));

    private PostingFactsExtractor() {}

    static boolean handles(String eventType) {
        return SHAPES.containsKey(eventType);
    }

    /**
     * The facts this event posts, one per journal; empty when it posts nothing. An event without its source ref has
     * nothing to key a journal (or a queue row) on, so it is logged and left -- the emitting module's defect to fix.
     */
    static List<PostingFacts> extract(String type, Map<String, Object> p, LocalDate today) {
        List<PostingFacts> all = facts(type, p, businessDate(type, p, today));
        if (all.stream().anyMatch(f -> f.sourceRef() == null)) {
            log.error("{} for policy {} carries no source reference; nothing can be posted or queued for it", type,
                p.get("policyNumber"));
            return all.stream().filter(f -> f.sourceRef() != null).toList();
        }
        return all;
    }

    /**
     * The day the business happened, which the journal is dated (2026-11-13, finance finding 89): money received on 31
     * October but confirmed on 1 November belongs to October, so month-end matches the bank statement. Where the event
     * carries one -- a receipt's businessDate, a unit-linked charge's or price's date, a savings account's month-end --
     * and it is not in the future; otherwise the day it posts, as before. A month already closing or locked refuses
     * it into Unposted events, as it would any event, for finance to see.
     */
    static LocalDate businessDate(String type, Map<String, Object> p, LocalDate today) {
        LocalDate day = switch (type) {
            case "unitlinked.ChargesTaken" -> date(p.get("chargeDate"));
            case "unitlinked.FundRevalued" -> date(p.get("valuationDate"));
            case "accumulation.PostingRecorded" -> java.util.Set.of("month-end", "deposit-interest", "term-account-interest")
                .contains(String.valueOf(p.get("sourceType"))) ? monthEndOf(p) : null;
            default -> date(p.get("businessDate"));
        };
        return day == null || day.isAfter(today) ? today : day;
    }

    private static LocalDate date(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(String.valueOf(value).substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A savings account's month-end or interest posting is dated the day it was for: its entries' latest effective date. */
    private static LocalDate monthEndOf(Map<String, Object> p) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) p.get("entries");
        return entries == null ? null : entries.stream().map(e -> date(e.get("effectiveDate")))
            .filter(java.util.Objects::nonNull).max(LocalDate::compareTo).orElse(null);
    }

    private static List<PostingFacts> facts(String type, Map<String, Object> p, LocalDate today) {
        String policy = string(p.get("policyNumber"));
        return switch (type) {
            case "billing.PremiumInvoiceGenerated" -> {
                Map<String, String> attrs = new HashMap<>(Map.of("refType", "INVOICE"));
                if (p.get("covers") != null) {
                    attrs.put("covers", covers(p.get("covers")));
                }
                yield List.of(money(type, string(p.get("invoiceId")), policy, p.get("amount"), today, attrs));
            }
            // How the money came in (2026-10-27) rides as receiptMethod, for the RECEIPT account; absent is mobile money.
            case "billing.PremiumCollected" -> {
                Map<String, String> attrs = new HashMap<>(Map.of("refType", "INVOICE"));
                putIfPresent(attrs, "receiptMethod", p.get("receiptMethod"));
                yield List.of(money(type, string(p.get("invoiceId")), policy, p.get("amount"), today, attrs));
            }
            // Keyed on the balance entry, so a redelivery posts once. The deposit's method picks the account it reached.
            case "billing.DepositReceived" -> {
                Map<String, String> attrs = new HashMap<>(Map.of("refType", "POLICY"));
                putIfPresent(attrs, "receiptMethod", p.get("receiptMethod"));
                yield List.of(money(type, string(p.get("entryId")), policy, p.get("amount"), today, attrs));
            }
            case "billing.PremiumPaidFromBalance" -> List.of(money(type, string(p.get("entryId")), policy, p.get("amount"),
                today, Map.of("refType", "INVOICE")));
            // Keyed on the withdrawal; its payout's rail (recorded by payment under the withdrawal id) picks the bank.
            case "billing.BalanceWithdrawalApproved", "billing.BalanceWithdrawalReturned" ->
                List.of(money(type, string(p.get("withdrawalId")), policy, p.get("amount"), today,
                    Map.of("refType", "WITHDRAWAL")));
            case "billing.BalanceWithdrawalPaid" -> List.of(money(type, string(p.get("withdrawalId")), policy,
                p.get("amount"), today, paid("WITHDRAWAL", p.get("withdrawalId"))));
            // Keyed on the reversing entry; the deposit's method picks the account the money is taken back out of.
            case "billing.DepositReversed" -> {
                Map<String, String> attrs = new HashMap<>(Map.of("refType", "POLICY"));
                putIfPresent(attrs, "receiptMethod", p.get("receiptMethod"));
                yield List.of(money(type, string(p.get("entryId")), policy, p.get("amount"), today, attrs));
            }
            // Keyed on the confirmed payment that brought the agent's cash in; its method picks the account it reached.
            case "billing.AgentCashBanked" -> {
                Map<String, String> attrs = new HashMap<>(Map.of("refType", "POLICY"));
                putIfPresent(attrs, "receiptMethod", p.get("receiptMethod"));
                yield List.of(money(type, string(p.get("paymentId")), policy, p.get("amount"), today, attrs));
            }
            case "billing.PremiumPaidReversed" -> List.of(money(type, string(p.get("entryId")), policy, p.get("amount"),
                today, Map.of("refType", "INVOICE")));
            // Keyed on the WAIVER (2026-11-08): the invoice id for its first, as always; a waiver after an undone one
            // has its own reference. The undo is keyed on the waiver it takes back.
            case "billing.InvoiceWaived", "billing.InvoiceWaiverUndone" -> {
                String waiverRef = p.get("waiverRef") != null ? string(p.get("waiverRef")) : string(p.get("invoiceId"));
                String ref = type.equals("billing.InvoiceWaived") ? waiverRef : "UNDO:" + waiverRef;
                Map<String, String> attrs = new HashMap<>(Map.of("refType", "INVOICE"));
                putIfPresent(attrs, "invoiceRef", p.get("invoiceId"));
                yield List.of(money(type, ref, policy, p.get("amount"), today, attrs));
            }
            // Keyed on the CREDIT: one invoice carries a credit for every borrower on its file, each its own movement.
            case "billing.PremiumRefundDue" -> {
                Map<String, String> attrs = new HashMap<>(Map.of("refType", "CREDIT"));
                putIfPresent(attrs, "invoiceRef", p.get("originalInvoiceId"));
                putIfPresent(attrs, "memberRef", p.get("policyMemberId"));
                yield List.of(money(type, string(p.get("creditId")), policy, p.get("amount"), today, attrs));
            }
            // Keyed on the CREDIT, as the refund it pays; the transfer's rail decides the bank account (1130 for EFT).
            case "billing.PremiumRefundPaid" -> List.of(money(type, string(p.get("creditId")), policy, p.get("amount"),
                today, paid("CREDIT", p.get("creditId"))));
            // Keyed on the RESTATEMENT: one instalment can be restated more than once.
            case "billing.PremiumInvoiceIncreased", "billing.PremiumInvoiceReduced" -> {
                Map<String, String> attrs = new HashMap<>(Map.of("refType", "RESTATEMENT"));
                putIfPresent(attrs, "invoiceRef", p.get("invoiceId"));
                yield List.of(money(type, string(p.get("restatementId")), policy, p.get("amount"), today, attrs));
            }
            case "claims.ClaimApproved" -> List.of(split(type, string(p.get("claimId")), policy, p.get("approvedAmount"),
                decimal(p.get("investmentComponent")), today, Map.of("refType", "CLAIM")));
            // What left the bank: the settled amount less any loan it repaid (2026-11-07), which cleared its own part of
            // the payable (E-03-CLAIM). settledAmount stays the claim's gross, for reinsurance and returns.
            case "claims.ClaimSettled" -> List.of(money(type, string(p.get("claimId")), policy,
                p.get("paidAmount") != null ? p.get("paidAmount") : p.get("settledAmount"), today,
                paid("CLAIM", p.get("claimId"))));
            // A surrender value is all investment component (C-05): it is the payable as it stands.
            // The whole value is payable (grossAmount, 2026-11-07); "amount" is what goes to the rail once the policy's
            // loans are repaid from it (E-03-SURRENDER clears their part).
            case "policy.SurrenderPayoutRequested" -> List.of(money(type, string(p.get("surrenderRequestId")), policy,
                p.get("grossAmount") != null ? p.get("grossAmount") : p.get("amount"), today, Map.of("refType", "SURRENDER")));
            case "policy.SurrenderPaid" -> List.of(money(type, string(p.get("surrenderRequestId")), policy,
                p.get("paidAmount"), today, paid("SURRENDER", p.get("surrenderRequestId"))));
            case "benefitpayout.PayoutRequested" -> List.of(payoutDue(type, p, policy, today));
            case "benefitpayout.PayoutPaid" -> List.of(payout(type, p, policy, today));
            case "benefitpayout.FreeLookRefundPaid" -> List.of(money(type, string(p.get("cancellationId")), policy,
                p.get("amount"), today, paid("FREE_LOOK", p.get("cancellationId"))));
            case "distribution.CommissionAccrued" -> List.of(commissionAccrued(type, p, policy, today));
            case "distribution.CommissionPaid" -> List.of(commissionPaid(type, p, today));
            case "policyloan.LoanDisbursed" -> List.of(money(type, string(p.get("loanId")), null, p.get("amount"), today,
                paid("LOAN", p.get("loanId"))));
            case "policyloan.LoanRepaid" -> List.of(loanRepaid(type, p, policy, today));
            case "policyloan.LoanOverpaid" -> List.of(loanSurplus(type, p, policy, today));
            case "policyloan.LoanInterestAccrued" -> List.of(money(type, p.get("loanId") + ":" + p.get("period"), policy,
                p.get("amount"), today, Map.of("refType", "LOAN")));
            case "policyloan.LoanForcedLapseTriggered" -> List.of(forcedLapse(type, p, policy, today));
            case "accumulation.PostingRecorded" -> accumulationEntries(type, p, policy, today);
            case "accumulation.PayoutRequested" -> accumulationCharge(type, p, policy, today);
            case "accumulation.PayoutPaid" -> List.of(money(type, string(p.get("payoutRef")), policy, p.get("amount"),
                today, paid("ACCOUNT_PAYOUT", p.get("paymentRef"))));
            // Dated the bordereau month's last day, so it posts in the month it charges (period from the event date).
            case "reinsurance.BordereauPosted" -> {
                java.time.YearMonth month = java.time.YearMonth.parse(string(p.get("period")));
                yield List.of(facts(type, string(p.get("bordereauId")), null, currencyOf(p.get("premium")),
                    month.atEndOfMonth(), Map.of("premium", amountOf(p.get("premium")), "commission",
                        amountOf(p.get("commission")),
                        // 2026-11-08: taken back for a policy cancelled in free-look; zero on a bordereau before then.
                        "premiumReversed", amountOf(p.get("premiumReversed")),
                        "commissionReversed", amountOf(p.get("commissionReversed"))), Map.of("refType", "BORDEREAU")));
            }
            case "reinsurance.RecoveryCalculated" ->
                List.of(money(type, string(p.get("recoveryId")), policy, p.get("recoverableAmount"), today,
                    Map.of("refType", "RECOVERY")));
            // Posted in the month it is approved: the quarter it settles may already be closing or locked.
            case "reinsurance.StatementApproved" -> {
                BigDecimal withheld = amountOf(p.get("fundsWithheld"));
                yield List.of(facts(type, string(p.get("statementId")), null, currencyOf(p.get("premium")), today,
                    Map.of("premiumNet", amountOf(p.get("premium")).subtract(withheld), "withheld", withheld,
                        "commission", amountOf(p.get("commission")), "recoveries", amountOf(p.get("recoveries")),
                        "owedToUs", amountOf(p.get("owedToUs")), "owedByUs", amountOf(p.get("owedByUs")),
                        "profitCommission", amountOf(p.get("profitCommission"))),
                    Map.of("refType", "STATEMENT")));
            }
            // Each settlement posts once, under its own id, in the month it is confirmed (or, paid out, when the EFT is made).
            case "reinsurance.SettlementReceived", "reinsurance.ProfitCommissionReceived" -> {
                Map<String, String> attrs = new HashMap<>(Map.of("refType", "RI_SETTLEMENT"));
                putIfPresent(attrs, "receiptMethod", p.get("receiptMethod"));
                yield List.of(money(type, string(p.get("settlementId")), null, p.get("amount"), today, attrs));
            }
            case "reinsurance.FundsWithheldReleased" -> List.of(money(type, string(p.get("settlementId")), null,
                p.get("amount"), today, Map.of("refType", "RI_SETTLEMENT")));
            case "reinsurance.SettlementPaid" -> List.of(money(type, string(p.get("settlementId")), null, p.get("amount"),
                today, paid("RI_SETTLEMENT", p.get("settlementId"))));
            default -> type.startsWith("unitlinked.") ? unitLinked(type, p, policy, today) : List.of();
        };
    }

    private static PostingFacts payout(String type, Map<String, Object> p, String policy, LocalDate today) {
        @SuppressWarnings("unchecked")
        Map<String, Object> paid = (Map<String, Object>) p.get("paidAmount");
        BigDecimal net = decimal(paid.get("amount"));
        BigDecimal withheld = BigDecimal.ZERO;
        if (p.get("withheldAmount") instanceof Map<?, ?> w) {
            withheld = decimal(w.get("amount")).max(BigDecimal.ZERO);
        }
        // Gross is what was paid plus what was withheld: the journal balances by construction.
        Map<String, String> attrs = paid("PAYOUT", p.get("instalmentId"));
        attrs.put("kind", string(p.get("kind")));
        return new PostingFacts(type, string(p.get("instalmentId")), policy, string(paid.get("currencyCode")), today,
            Map.of("gross", net.add(withheld), "paid", net, "withheld", withheld), attrs);
    }

    /**
     * A payout falling due (guide C-01, D-01, D-05, H-02): the gross becomes payable, the investment component share
     * (computed by benefitpayout) charged to 2124 and the rest to the insurance service expense. A free-look refund
     * (keyed on its cancellation) is kind FREE_LOOK (A-17).
     */
    private static PostingFacts payoutDue(String type, Map<String, Object> p, String policy, LocalDate today) {
        if (p.get("cancellationId") != null) {
            Map<String, String> attrs = new HashMap<>(Map.of("refType", "FREE_LOOK", "kind", "FREE_LOOK"));
            PostingFacts refund = money(type, string(p.get("cancellationId")), policy, p.get("amount"), today, attrs);
            return new PostingFacts(type, refund.sourceRef(), policy, refund.currency(), today,
                Map.of(AMOUNT, refund.amount(AMOUNT), "gross", refund.amount(AMOUNT)), attrs);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> net = (Map<String, Object>) p.get("amount");
        BigDecimal gross = p.get("grossAmount") != null ? decimal(p.get("grossAmount")) : decimal(net.get("amount"));
        BigDecimal ic = decimal(p.get("investmentComponent")).min(gross).max(BigDecimal.ZERO);
        return new PostingFacts(type, string(p.get("instalmentId")), policy, string(net.get("currencyCode")), today,
            Map.of(AMOUNT, decimal(net.get("amount")), "gross", gross, "investmentComponent", ic,
                "insured", gross.subtract(ic)),
            Map.of("refType", "PAYOUT", "kind", string(p.get("kind"))));
    }

    /** An amount split into its investment component and the rest (the insurance service expense). */
    private static PostingFacts split(String type, String ref, String policy, Object money, BigDecimal investment,
                                      LocalDate today, Map<String, String> attributes) {
        PostingFacts whole = money(type, ref, policy, money, today, attributes);
        BigDecimal amount = whole.amount(AMOUNT);
        BigDecimal ic = investment.min(amount).max(BigDecimal.ZERO);
        return new PostingFacts(type, ref, policy, whole.currency(), today,
            Map.of(AMOUNT, amount, "investmentComponent", ic, "insured", amount.subtract(ic)), attributes);
    }

    /**
     * A commission accrual or its reversal, by the agent's channel (guide A-04, A-09; a clawback reverses, A-14's
     * note). {@code movement} is the guide's tag: IACF_COM, IACF_BRK or IACF_BANC by channel, IACF_OVR for an override,
     * IACF_CLAW for a reversal. The amount is the magnitude; {@code direction} carries the sign.
     */
    private static PostingFacts commissionAccrued(String type, Map<String, Object> p, String policy, LocalDate today) {
        PostingFacts f = money(type, string(p.get("accrualId")), policy, p.get("amount"), today, Map.of());
        boolean reversal = p.get("reversesAccrualId") != null || f.amount(AMOUNT).signum() < 0;
        String channel = channel(p.get("salesChannel"));
        String movement = reversal ? "IACF_CLAW"
            : String.valueOf(p.get("tierType")).contains("OVERRIDE") ? "IACF_OVR"
            : switch (channel) {
                case "BROKER" -> "IACF_BRK";
                case "BANCASSURANCE" -> "IACF_BANC";
                default -> "IACF_COM";
            };
        return new PostingFacts(type, f.sourceRef(), policy, f.currency(), today, Map.of(AMOUNT, f.amount(AMOUNT).abs()),
            Map.of("direction", reversal ? "REVERSAL" : "ACCRUAL", "channel", channel, "movement", movement,
                "refType", "COMMISSION"));
    }

    /** A statement paid (A-05): the gross clears the payable, the net left the bank, the tax withheld is owed. */
    private static PostingFacts commissionPaid(String type, Map<String, Object> p, LocalDate today) {
        PostingFacts gross = money(type, string(p.get("statementId")), null, p.get("amount"), today, Map.of());
        BigDecimal withheld = decimal(p.get("withheldAmount")).max(BigDecimal.ZERO);
        BigDecimal paid = p.get("paidAmount") != null ? decimal(p.get("paidAmount")) : gross.amount(AMOUNT).subtract(withheld);
        Map<String, String> attrs = paid("STATEMENT", p.get("statementId"));
        attrs.put("channel", channel(p.get("salesChannel")));
        return new PostingFacts(type, gross.sourceRef(), null, gross.currency(), today,
            Map.of("gross", gross.amount(AMOUNT), "paid", paid, "withheld", withheld), attrs);
    }

    /** The guide's three commission payables: tied agents 2510, brokers 2520, bancassurance 2530. */
    private static String channel(Object salesChannel) {
        String c = salesChannel == null ? "AGENT" : salesChannel.toString();
        return "BROKER".equals(c) || "BANCASSURANCE".equals(c) ? c : "AGENT";
    }

    /** A repayment (E-03): interest accrued so far first, then principal -- as policyloan split it. */
    private static PostingFacts loanRepaid(String type, Map<String, Object> p, String policy, LocalDate today) {
        // How the money came in (money movement step 1): the RECEIPT line posts it to the account it reached.
        Map<String, String> attrs = new HashMap<>(Map.of("refType", "LOAN"));
        putIfPresent(attrs, "receiptMethod", p.get("receiptMethod"));
        PostingFacts f = money(type, string(p.get("loanTransactionId")), policy, p.get("amount"), today, attrs);
        BigDecimal interest = decimal(p.get("interestAmount"));
        BigDecimal principal = p.get("principalAmount") != null ? decimal(p.get("principalAmount"))
            : f.amount(AMOUNT).subtract(interest);
        return new PostingFacts(type, f.sourceRef(), policy, f.currency(), today,
            Map.of(AMOUNT, f.amount(AMOUNT), "interest", interest, "principal", principal), f.attributes());
    }

    /** Money above what a loan owed (money movement step 1): keyed on the payment, so a redelivery posts once. */
    private static PostingFacts loanSurplus(String type, Map<String, Object> p, String policy, LocalDate today) {
        Map<String, String> attrs = new HashMap<>(Map.of("refType", "LOAN"));
        putIfPresent(attrs, "receiptMethod", p.get("receiptMethod"));
        return money(type, "loan-surplus:" + string(p.get("paymentId")), policy, p.get("amount"), today, attrs);
    }

    /** A loan foreclosed against the surrender value (E-06): the investment component repays principal and interest. */
    private static PostingFacts forcedLapse(String type, Map<String, Object> p, String policy, LocalDate today) {
        BigDecimal principal = decimal(p.get("principalOutstanding")).max(BigDecimal.ZERO);
        BigDecimal interest = decimal(p.get("interestOutstanding")).max(BigDecimal.ZERO);
        String currency = p.get("currencyCode") != null ? string(p.get("currencyCode")) : "TZS";
        return new PostingFacts(type, p.get("loanId") + ":forced-lapse", policy, currency, today,
            Map.of("total", principal.add(interest), "principal", principal, "interest", interest),
            Map.of("refType", "LOAN"));
    }

    /**
     * An IFRS 9 account's ledger posting (guide G-05..G-08): one journal per entry, keyed postingId:seq. {@code flow}
     * groups the entry types the rules post alike; an entry the rules do not know queues, as anything unmapped does.
     */
    private static List<PostingFacts> accumulationEntries(String type, Map<String, Object> p, String policy,
                                                          LocalDate today) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) p.get("entries");
        List<PostingFacts> out = new ArrayList<>();
        for (Map<String, Object> e : entries == null ? List.<Map<String, Object>>of() : entries) {
            BigDecimal amount = decimal(e.get("amount"));
            if (amount.signum() == 0) {
                continue;
            }
            String entryType = string(e.get("type"));
            String flow = switch (entryType) {
                case "CONTRIBUTION", "TOP_UP", "TRANSFER_IN" -> "IN";
                case "ALLOCATION_CHARGE", "POLICY_FEE" -> "CHARGE";
                case "INTEREST" -> "INTEREST";
                case "WITHDRAWAL", "SURRENDER", "MATURITY", "DEATH_CLAIM", "FREE_LOOK_REFUND" -> "OUT";
                case "VESTING" -> "VESTING";
                default -> entryType + (amount.signum() > 0 ? "_UP" : "_DOWN");   // REVERSAL_UP, ADJUSTMENT_DOWN ...
            };
            Map<String, String> attrs = new HashMap<>(Map.of("flow", flow, "refType", "ACCOUNT"));
            // How a top-up came in (2026-10-28), for the RECEIPT account; absent is mobile money, as before.
            putIfPresent(attrs, "receiptMethod", p.get("receiptMethod"));
            out.add(new PostingFacts(type, p.get("postingId") + ":" + e.get("seq"), policy, "TZS", today,
                Map.of(AMOUNT, amount.abs()), attrs));
        }
        return out;
    }

    /** An IFRS 9 account surrendered: the surrender charge kept from the value is fee income (G-06's 7310). */
    private static List<PostingFacts> accumulationCharge(String type, Map<String, Object> p, String policy,
                                                         LocalDate today) {
        if (!"SURRENDER_PAYOUT".equals(p.get("purpose")) || p.get("grossAmount") == null) {
            return List.of();
        }
        PostingFacts paidOut = money(type, string(p.get("sourceRef")), policy, p.get("amount"), today, Map.of());
        PostingFacts gross = money(type, string(p.get("sourceRef")), policy, p.get("grossAmount"), today, Map.of());
        // The loans a surrender repaid (2026-11-07, E7) are not charge: E-03-SURRENDER clears them from the payable.
        BigDecimal loanRepaid = p.get("loanRepaidAmount") != null
            ? money(type, string(p.get("sourceRef")), policy, p.get("loanRepaidAmount"), today, Map.of()).amount(AMOUNT)
            : BigDecimal.ZERO;
        BigDecimal charge = gross.amount(AMOUNT).subtract(paidOut.amount(AMOUNT)).subtract(loanRepaid).max(BigDecimal.ZERO);
        return List.of(new PostingFacts(type, paidOut.sourceRef(), policy, paidOut.currency(), today,
            Map.of("charge", charge), Map.of("refType", "ACCOUNT")));
    }

    /** A paid event's attributes: its reference type and the payment's source reference, to find the rail by. */
    private static Map<String, String> paid(String refType, Object paymentRef) {
        Map<String, String> attrs = new HashMap<>(Map.of("refType", refType));
        putIfPresent(attrs, "paymentRef", paymentRef);
        return attrs;
    }

    private static List<PostingFacts> unitLinked(String type, Map<String, Object> p, String policy, LocalDate today) {
        String currency = p.get("currencyCode") != null ? string(p.get("currencyCode")) : "TZS";
        String ref = string(p.get("sourceRef"));
        Map<String, String> ul = Map.of("refType", "UL_TXN");
        return switch (type) {
            case "unitlinked.UnitsAllocated" -> List.of(facts(type, ref, policy, currency, today,
                Map.of("premium", decimal(p.get("premium")), "allocated", decimal(p.get("allocated")),
                    "allocationCharge", decimal(p.get("allocationCharge"))), ul));
            case "unitlinked.ChargesTaken" -> List.of(facts(type, ref, policy, currency, today,
                Map.of("policyFee", decimal(p.get("policyFee")), "costOfInsurance", decimal(p.get("costOfInsurance"))), ul));
            case "unitlinked.FundRevalued" -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> delta = (Map<String, Object>) p.get("delta");
                BigDecimal d = decimal(delta.get("amount"));
                String deltaCurrency = delta.get("currencyCode") != null ? string(delta.get("currencyCode")) : currency;
                Map<String, String> attrs = new HashMap<>(Map.of("sign", d.signum() >= 0 ? "POSITIVE" : "NEGATIVE",
                    "refType", "UNIT_PRICE"));
                putIfPresent(attrs, "fund", p.get("fundCode"));
                yield List.of(facts(type, p.get("priceId") + ":" + p.get("carried"), null, deltaCurrency, today,
                    Map.of("delta", d.abs()), attrs));
            }
            case "unitlinked.ExitPriced" -> List.of(facts(type, ref, policy, currency, today,
                Map.of(AMOUNT, decimal(p.get("proceeds"))), withPurpose(p, ul)));
            case "unitlinked.PremiumReturned" -> {
                BigDecimal returned = decimal(p.get("returned"));
                BigDecimal charge = decimal(p.get("allocationCharge"));
                yield List.of(facts(type, ref, policy, currency, today,
                    Map.of("returned", returned, "allocationCharge", charge, "total", returned.add(charge)), ul));
            }
            // A top-up names the account it reached (2026-11-07): mobile money, or the bank a receipt by hand went to.
            case "unitlinked.TopUpReceived" -> {
                Map<String, String> attrs = new HashMap<>(ul);
                putIfPresent(attrs, "receiptMethod", p.get("receiptMethod"));
                yield List.of(facts(type, ref, policy, currency, today, Map.of(AMOUNT, decimal(p.get("amount"))), attrs));
            }
            case "unitlinked.ChargeRefunded", "unitlinked.UnitsReinvested", "unitlinked.SurrenderCharged",
                 "unitlinked.AdjustmentCollected" ->
                List.of(facts(type, ref, policy, currency, today, Map.of(AMOUNT, decimal(p.get("amount"))), ul));
            case "unitlinked.PayoutPaid" -> {
                Map<String, String> attrs = withPurpose(p, ul);
                attrs.put("paymentRef", ref);   // payment's source reference: the rail it went by (I3b)
                yield List.of(facts(type, ref, policy, currency, today, Map.of(AMOUNT, decimal(p.get("amount"))), attrs));
            }
            case "unitlinked.SwitchExecuted" -> List.of(facts(type, ref, policy, currency, today,
                Map.of("fee", decimal(p.get("fee"))), ul));
            // Two journals, as before: the proceeds, and the surrender charge under its own ref.
            case "unitlinked.WithdrawalPriced" -> List.of(
                facts(type, ref, policy, currency, today, Map.of("proceeds", decimal(p.get("proceeds"))),
                    Map.of("part", "PROCEEDS", "refType", "UL_TXN")),
                facts(type, ref + ":surrender-charge", policy, currency, today,
                    Map.of("surrenderCharge", decimal(p.get("surrenderCharge"))),
                    Map.of("part", "SURRENDER_CHARGE", "refType", "UL_TXN")));
            case "unitlinked.AdjustmentWaived" -> List.of(facts(type, ref, policy, currency, today,
                Map.of(AMOUNT, decimal(p.get("amount"))),
                Map.of("direction", string(p.get("direction")), "refType", "UL_TXN")));
            case "unitlinked.PriceCorrected" -> corrected(type, p, currency, today);
            default -> List.of();
        };
    }

    /**
     * A price correction re-runs every exit sold at the wrong price. Per sale whose money changed, the liability's
     * difference against 5110; and where the exit was already paid, what is now owed to the customer or by them. A
     * buy or a charge moves units, not money: the true-up's revaluation carries it.
     */
    private static List<PostingFacts> corrected(String type, Map<String, Object> p, String currency, LocalDate today) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> movements = (List<Map<String, Object>>) p.get("movements");
        String ref = string(p.get("correctedPriceId"));
        List<PostingFacts> out = new ArrayList<>();
        int i = 0;
        for (Map<String, Object> m : movements) {
            i++;
            if (!string(m.get("entryType")).endsWith("_SALE")) {
                continue;
            }
            // Sale amounts are negative: what the customer is owed more (or less) is the corrected one less the original.
            BigDecimal d = decimal(m.get("originalAmount")).subtract(decimal(m.get("correctedAmount")));
            if (d.signum() == 0) {
                continue;
            }
            String policy = string(m.get("policyNumber"));
            String sign = d.signum() > 0 ? "POSITIVE" : "NEGATIVE";
            String mref = ref + ":" + i;
            out.add(facts(type, mref + ":liability", policy, currency, today, Map.of("difference", d.abs()),
                Map.of("part", "LIABILITY", "sign", sign, "refType", "UNIT_PRICE")));
            if (Boolean.TRUE.equals(m.get("paidAlready"))) {
                out.add(facts(type, mref + ":owed", policy, currency, today, Map.of("difference", d.abs()),
                    Map.of("part", "OWED", "sign", sign, "refType", "UNIT_PRICE")));
            }
        }
        return out;
    }

    private static Map<String, String> withPurpose(Map<String, Object> p, Map<String, String> base) {
        Map<String, String> attrs = new HashMap<>(base);
        attrs.put("purpose", string(p.get("purpose")));
        return attrs;
    }

    /** One {@code {amount, currencyCode}} money map. A missing one is no amount: the journal posts nothing. */
    private static PostingFacts money(String type, String ref, String policy, Object money, LocalDate today,
                                      Map<String, String> attributes) {
        BigDecimal amount = BigDecimal.ZERO;
        String currency = null;
        if (money instanceof Map<?, ?> m) {
            amount = decimal(m.get("amount"));
            currency = string(m.get("currencyCode"));
        }
        return facts(type, ref, policy, currency, today, Map.of(AMOUNT, amount), attributes);
    }

    private static BigDecimal amountOf(Object money) {
        return money instanceof Map<?, ?> m ? decimal(m.get("amount")) : BigDecimal.ZERO;
    }

    private static String currencyOf(Object money) {
        return money instanceof Map<?, ?> m ? string(m.get("currencyCode")) : null;
    }

    private static PostingFacts facts(String type, String ref, String policy, String currency, LocalDate today,
                                      Map<String, BigDecimal> amounts, Map<String, String> attributes) {
        return new PostingFacts(type, ref, policy, currency, today, amounts, attributes);
    }

    /**
     * A PAA invoice's cover as a compact string the earning schedule reads back: {@code member|from|to|amount}
     * entries joined by {@code ;}. A string, so it travels in the facts' attributes and survives the unposted-event
     * queue unchanged.
     */
    private static String covers(Object raw) {
        StringBuilder out = new StringBuilder();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> c) {
                    if (!out.isEmpty()) {
                        out.append(';');
                    }
                    out.append(c.get("memberRef") == null ? "" : c.get("memberRef")).append('|')
                        .append(c.get("coversFrom")).append('|').append(c.get("coversTo")).append('|')
                        .append(c.get("amount"));
                }
            }
        }
        return out.toString();
    }

    private static void putIfPresent(Map<String, String> attrs, String key, Object value) {
        if (value != null) {
            attrs.put(key, value.toString());
        }
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }
}
