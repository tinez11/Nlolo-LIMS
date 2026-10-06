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
        Map.entry("billing.InvoiceWaived", amount()),
        Map.entry("billing.PremiumRefundDue", amount()),
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
        Map.entry("policyloan.LoanRepaid", new EventShape(Set.of(AMOUNT, "interest", "principal"), Set.of())),
        Map.entry("policyloan.LoanInterestAccrued", amount()),
        Map.entry("policyloan.LoanForcedLapseTriggered", new EventShape(Set.of("total", "principal", "interest"), Set.of())),
        Map.entry("accumulation.PostingRecorded", amount("flow")),
        Map.entry("accumulation.PayoutRequested", new EventShape(Set.of("charge"), Set.of())),
        Map.entry("accumulation.PayoutPaid", amount()),
        // IFRS 17 I3c: reinsurance held from the monthly bordereau (K-01/K-02) and a recovery at claim approval (B-05).
        Map.entry("reinsurance.BordereauPosted", new EventShape(Set.of("premium", "commission"), Set.of())),
        Map.entry("reinsurance.RecoveryCalculated", amount()),
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
            case "claims.ClaimApproved" -> List.of(split(type, string(p.get("claimId")), policy, p.get("approvedAmount"),
                decimal(p.get("investmentComponent")), today, Map.of("refType", "CLAIM")));
            case "claims.ClaimSettled" -> List.of(money(type, string(p.get("claimId")), policy, p.get("settledAmount"), today,
                paid("CLAIM", p.get("claimId"))));
            // A surrender value is all investment component (C-05): it is the payable as it stands.
            case "policy.SurrenderPayoutRequested" -> List.of(money(type, string(p.get("surrenderRequestId")), policy,
                p.get("amount"), today, Map.of("refType", "SURRENDER")));
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
                        amountOf(p.get("commission"))), Map.of("refType", "BORDEREAU")));
            }
            case "reinsurance.RecoveryCalculated" ->
                List.of(money(type, string(p.get("recoveryId")), policy, p.get("recoverableAmount"), today,
                    Map.of("refType", "RECOVERY")));
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
        PostingFacts f = money(type, string(p.get("loanTransactionId")), policy, p.get("amount"), today,
            Map.of("refType", "LOAN"));
        BigDecimal interest = decimal(p.get("interestAmount"));
        BigDecimal principal = p.get("principalAmount") != null ? decimal(p.get("principalAmount"))
            : f.amount(AMOUNT).subtract(interest);
        return new PostingFacts(type, f.sourceRef(), policy, f.currency(), today,
            Map.of(AMOUNT, f.amount(AMOUNT), "interest", interest, "principal", principal), f.attributes());
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
            out.add(new PostingFacts(type, p.get("postingId") + ":" + e.get("seq"), policy, "TZS", today,
                Map.of(AMOUNT, amount.abs()), Map.of("flow", flow, "refType", "ACCOUNT")));
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
        BigDecimal charge = gross.amount(AMOUNT).subtract(paidOut.amount(AMOUNT)).max(BigDecimal.ZERO);
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
            case "unitlinked.ChargeRefunded", "unitlinked.UnitsReinvested", "unitlinked.SurrenderCharged",
                 "unitlinked.TopUpReceived", "unitlinked.AdjustmentCollected" ->
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
