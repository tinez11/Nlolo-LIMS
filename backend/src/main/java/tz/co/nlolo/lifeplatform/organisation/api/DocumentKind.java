package tz.co.nlolo.lifeplatform.organisation.api;

/** The documents a signatory may be set to sign (2026-11-09). */
public enum DocumentKind {
    POLICY_SCHEDULE("Policy schedules"),
    RECEIPT("Receipts"),
    PAYOUT_ADVICE("Payout advices"),
    QUOTATION("Quotations"),
    STATEMENT("Statements and schedules");

    private final String label;

    DocumentKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
