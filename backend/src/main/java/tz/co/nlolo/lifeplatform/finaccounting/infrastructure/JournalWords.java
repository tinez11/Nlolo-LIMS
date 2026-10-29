package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import tz.co.nlolo.lifeplatform.finaccounting.api.JournalSource;

import java.util.Map;

/**
 * What a journal entry records, in a finance officer's words (2026-10-27).
 *
 * <p>The ledger listed every entry by its source event -- "billing.PremiumInvoiceGenerated" -- which says which part of
 * the code posted it and nothing about the money. These are the words the GL postings screen shows instead; the event
 * stays on the entry for whoever traces it. An event this list does not name is said from its own name
 * ("billing.SomethingNew" reads "Something new"), never hidden.
 */
final class JournalWords {

    private JournalWords() {}

    private static final Map<String, String> EVENTS = Map.ofEntries(
        Map.entry("billing.PremiumInvoiceGenerated", "Premium fell due"),
        Map.entry("billing.PremiumCollected", "Premium received"),
        Map.entry("billing.InvoiceWaived", "Premium waived"),
        Map.entry("billing.PremiumRefundDue", "Premium credited back"),
        Map.entry("billing.PremiumInvoiceIncreased", "Premium restated upward"),
        Map.entry("billing.PremiumInvoiceReduced", "Premium restated downward"),
        Map.entry("claims.ClaimApproved", "Claim approved"),
        Map.entry("claims.ClaimSettled", "Claim paid"),
        Map.entry("policy.SurrenderPayoutRequested", "Surrender approved"),
        Map.entry("policy.SurrenderPaid", "Surrender paid"),
        Map.entry("benefitpayout.PayoutRequested", "Benefit payout due"),
        Map.entry("benefitpayout.PayoutPaid", "Benefit payout paid"),
        Map.entry("benefitpayout.FreeLookRefundPaid", "Free-look refund paid"),
        Map.entry("distribution.CommissionAccrued", "Commission earned"),
        Map.entry("distribution.CommissionPaid", "Commission paid"),
        Map.entry("policyloan.LoanDisbursed", "Policy loan paid out"),
        Map.entry("policyloan.LoanInterestAccrued", "Loan interest charged"),
        Map.entry("policyloan.LoanRepaid", "Loan repayment received"),
        Map.entry("policyloan.LoanForcedLapseTriggered", "Loan closed against the policy's value"),
        Map.entry("accumulation.PostingRecorded", "Savings account movement"),
        Map.entry("accumulation.PayoutRequested", "Savings payout due"),
        Map.entry("accumulation.PayoutPaid", "Savings payout paid"),
        Map.entry("reinsurance.BordereauPosted", "Reinsurance premium ceded"),
        Map.entry("reinsurance.RecoveryCalculated", "Reinsurance recovery on a claim"),
        Map.entry("reinsurance.StatementApproved", "Reinsurance statement settled"),
        Map.entry("finaccounting.PaaRevenueEarned", "Premium earned (PAA)"),
        Map.entry("finaccounting.AutoReversal", "Automatic reversal"),
        Map.entry("finaccounting.ManualJournal", "Manual journal"),
        Map.entry("unitlinked.UnitsAllocated", "Units bought with a premium"),
        Map.entry("unitlinked.ChargesTaken", "Unit-linked charges taken"),
        Map.entry("unitlinked.FundRevalued", "Fund revalued"),
        Map.entry("unitlinked.ExitPriced", "Units sold on exit"),
        Map.entry("unitlinked.PremiumReturned", "Unit-linked premium returned"),
        Map.entry("unitlinked.ChargeRefunded", "Unit-linked charge refunded"),
        Map.entry("unitlinked.UnitsReinvested", "Units reinvested"),
        Map.entry("unitlinked.PriceCorrected", "Fund price corrected"),
        Map.entry("unitlinked.PayoutPaid", "Unit-linked payout paid"),
        Map.entry("unitlinked.SwitchExecuted", "Units switched between funds"),
        Map.entry("unitlinked.WithdrawalPriced", "Unit-linked withdrawal priced"),
        Map.entry("unitlinked.SurrenderCharged", "Surrender charge taken"),
        Map.entry("unitlinked.TopUpReceived", "Unit-linked top-up received"),
        Map.entry("unitlinked.AdjustmentCollected", "Price-correction adjustment collected"),
        Map.entry("unitlinked.AdjustmentWaived", "Price-correction adjustment waived"));

    static String describe(String sourceEvent, JournalSource source) {
        if (source == JournalSource.ENGINE_RUN) return "IFRS 17 engine results";
        if (source == JournalSource.MANUAL && (sourceEvent == null || !EVENTS.containsKey(sourceEvent))) {
            return "Manual journal";
        }
        if (sourceEvent == null || sourceEvent.isBlank()) return "Journal";
        String known = EVENTS.get(sourceEvent);
        if (known != null) return known;
        // From its own name: the part after the module, split into words.
        String name = sourceEvent.contains(".") ? sourceEvent.substring(sourceEvent.indexOf('.') + 1) : sourceEvent;
        String words = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase();
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
