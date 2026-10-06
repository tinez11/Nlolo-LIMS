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
        Map.entry("billing.PremiumCollected", amount()),
        Map.entry("billing.InvoiceWaived", amount()),
        Map.entry("billing.PremiumRefundDue", amount()),
        Map.entry("billing.PremiumInvoiceIncreased", amount()),
        Map.entry("billing.PremiumInvoiceReduced", amount()),
        Map.entry("claims.ClaimSettled", amount()),
        Map.entry("policy.SurrenderPaid", amount()),
        Map.entry("benefitpayout.PayoutPaid", new EventShape(Set.of("gross", "paid", "withheld"), Set.of())),
        Map.entry("distribution.CommissionPaid", amount()),
        Map.entry("payment.EftDisbursementAwaitingExecution", amount("purpose")),
        Map.entry("payment.EftDisbursementExecuted", amount("purpose")),
        Map.entry("policyloan.LoanDisbursed", amount()),
        Map.entry("policyloan.LoanRepaid", amount()),
        Map.entry("reinsurance.CessionRecorded", amount()),
        Map.entry("reinsurance.RecoveryConfirmed", amount()),
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
        Map.entry("unitlinked.TopUpReceived", amount()),
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
        List<PostingFacts> all = facts(type, p, today);
        if (all.stream().anyMatch(f -> f.sourceRef() == null)) {
            log.error("{} for policy {} carries no source reference; nothing can be posted or queued for it", type,
                p.get("policyNumber"));
            return all.stream().filter(f -> f.sourceRef() != null).toList();
        }
        return all;
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
            case "billing.PremiumCollected", "billing.InvoiceWaived" ->
                List.of(money(type, string(p.get("invoiceId")), policy, p.get("amount"), today, Map.of("refType", "INVOICE")));
            // Keyed on the CREDIT: one invoice carries a credit for every borrower on its file, each its own movement.
            case "billing.PremiumRefundDue" -> {
                Map<String, String> attrs = new HashMap<>(Map.of("refType", "CREDIT"));
                putIfPresent(attrs, "invoiceRef", p.get("originalInvoiceId"));
                putIfPresent(attrs, "memberRef", p.get("policyMemberId"));
                yield List.of(money(type, string(p.get("creditId")), policy, p.get("amount"), today, attrs));
            }
            // Keyed on the RESTATEMENT: one instalment can be restated more than once.
            case "billing.PremiumInvoiceIncreased", "billing.PremiumInvoiceReduced" -> {
                Map<String, String> attrs = new HashMap<>(Map.of("refType", "RESTATEMENT"));
                putIfPresent(attrs, "invoiceRef", p.get("invoiceId"));
                yield List.of(money(type, string(p.get("restatementId")), policy, p.get("amount"), today, attrs));
            }
            case "claims.ClaimSettled" ->
                List.of(money(type, string(p.get("claimId")), policy, p.get("settledAmount"), today, Map.of("refType", "CLAIM")));
            case "policy.SurrenderPaid" ->
                List.of(money(type, string(p.get("surrenderRequestId")), policy, p.get("paidAmount"), today,
                    Map.of("refType", "SURRENDER")));
            case "benefitpayout.PayoutPaid" -> List.of(payout(type, p, policy, today));
            case "distribution.CommissionPaid" ->
                List.of(money(type, string(p.get("statementId")), null, p.get("amount"), today, Map.of("refType", "STATEMENT")));
            case "payment.EftDisbursementAwaitingExecution", "payment.EftDisbursementExecuted" -> eft(type, p, today);
            case "policyloan.LoanDisbursed" ->
                List.of(money(type, string(p.get("loanId")), null, p.get("amount"), today, Map.of("refType", "LOAN")));
            case "policyloan.LoanRepaid" ->
                List.of(money(type, string(p.get("loanTransactionId")), null, p.get("amount"), today, Map.of("refType", "LOAN")));
            case "reinsurance.CessionRecorded" ->
                List.of(money(type, string(p.get("cessionId")), policy, p.get("cededAmount"), today, Map.of("refType", "CESSION")));
            case "reinsurance.RecoveryConfirmed" ->
                List.of(money(type, string(p.get("recoveryId")), null, p.get("amount"), today, Map.of("refType", "RECOVERY")));
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
        return new PostingFacts(type, string(p.get("instalmentId")), policy, string(paid.get("currencyCode")), today,
            Map.of("gross", net.add(withheld), "paid", net, "withheld", withheld), Map.of("refType", "PAYOUT"));
    }

    /**
     * Only a claim settlement posts from the EFT rail: its payable is raised when the transfer is queued and cleared
     * when it executes. A commission or surrender paid by EFT posts from its own event (CommissionPaid,
     * SurrenderPaid), so its EFT steps post nothing -- as before the rules engine.
     */
    private static List<PostingFacts> eft(String type, Map<String, Object> p, LocalDate today) {
        String purpose = string(p.get("purpose"));
        if (!"CLAIM_SETTLEMENT".equals(purpose)) {
            log.info("{} for disbursement {} has purpose {}; it posts from its own event, not the EFT rail", type,
                p.get("disbursementId"), purpose);
            return List.of();
        }
        return List.of(money(type, string(p.get("sourceRef")), null, p.get("amount"), today,
            Map.of("purpose", purpose, "refType", "DISBURSEMENT")));
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
            case "unitlinked.ChargeRefunded", "unitlinked.UnitsReinvested", "unitlinked.SurrenderCharged",
                 "unitlinked.TopUpReceived", "unitlinked.AdjustmentCollected" ->
                List.of(facts(type, ref, policy, currency, today, Map.of(AMOUNT, decimal(p.get("amount"))), ul));
            case "unitlinked.PayoutPaid" -> List.of(facts(type, ref, policy, currency, today,
                Map.of(AMOUNT, decimal(p.get("amount"))), withPurpose(p, ul)));
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
