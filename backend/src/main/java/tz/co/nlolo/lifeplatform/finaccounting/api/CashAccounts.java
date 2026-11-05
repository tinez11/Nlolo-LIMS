package tz.co.nlolo.lifeplatform.finaccounting.api;

/**
 * Which cash account a payment method reaches -- the one mapping the posting engine books by and the books-match check
 * (money movement step 7) reconciles by, so the two can never disagree about where money went.
 */
public final class CashAccounts {

    /** 1120 Premium collection bank account: a bank deposit, a cheque, a TANQR scan and cash (banked). */
    public static final String COLLECTION_BANK = "1120";
    /** 1130 Claims and benefits payment bank account: a bank transfer or a cheque paid out (user answer Q3). */
    public static final String PAYOUT_BANK = "1130";
    /** 1140 Mobile money wallets: mobile money and a Lipa number, in and out. */
    public static final String MOBILE_MONEY = "1140";

    private CashAccounts() {}

    /** The account money IN reached, by its receipt method; null or unknown is the gateway's mobile money. */
    public static String forReceipt(String method) {
        if (method == null) return MOBILE_MONEY;
        return switch (method) {
            case "CASH", "BANK_DEPOSIT", "CHEQUE", "TANQR" -> COLLECTION_BANK;
            default -> MOBILE_MONEY;
        };
    }

    /** The account money OUT left: a cheque is drawn on the same bank account a transfer leaves (2026-10-28). */
    public static String forPayout(String method) {
        return "EFT".equals(method) || "CHEQUE".equals(method) ? PAYOUT_BANK : MOBILE_MONEY;
    }
}
