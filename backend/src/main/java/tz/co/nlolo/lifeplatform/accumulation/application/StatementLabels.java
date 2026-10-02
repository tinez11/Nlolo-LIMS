package tz.co.nlolo.lifeplatform.accumulation.application;

import tz.co.nlolo.lifeplatform.accumulation.api.EntryType;

/** A clerk's words for each entry type, shared by the PDF. The console keeps its own copy in its status maps. */
final class StatementLabels {
    private StatementLabels() {}

    static String of(EntryType type) {
        return switch (type) {
            case CONTRIBUTION -> "Premiums";
            case TOP_UP -> "Top-ups";
            case TRANSFER_IN -> "Transfers in";
            case ALLOCATION_CHARGE -> "Allocation charges";
            case POLICY_FEE -> "Policy fees";
            case INTEREST -> "Interest";
            case WITHDRAWAL -> "Withdrawals";
            case SURRENDER -> "Surrender";
            case MATURITY -> "Maturity";
            case DEATH_CLAIM -> "Death claim";
            case FREE_LOOK_REFUND -> "Free-look cancellation";
            case ADJUSTMENT -> "Adjustments";
            case REVERSAL -> "Reversals";
        };
    }
}
