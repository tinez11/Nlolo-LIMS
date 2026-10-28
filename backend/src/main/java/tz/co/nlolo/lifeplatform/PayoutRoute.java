package tz.co.nlolo.lifeplatform;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How a payout reaches the person (2026-10-28, user decision: mobile money, bank transfer or cheque -- no cash). Kept
 * beside {@link DomainEventEnvelope} because every module that pays someone -- accounts, claims, surrenders, payouts --
 * names it, and payment, which moves the money, reads it back from the request event.
 *
 * <ul>
 *   <li>MOBILE_MONEY: {@code payee} is the phone number; the gateway pays it.</li>
 *   <li>EFT: a bank transfer finance makes by hand in the bank's portal, to the account named by the four bank fields;
 *       {@code payee} is the account name.</li>
 *   <li>CHEQUE: a cheque finance writes by hand; {@code payee} is the name it is made out to.</li>
 * </ul>
 * Bank transfers and cheques wait in finance's queue of payments to make, and are complete when the person who made
 * one records its bank reference or cheque number.
 */
public record PayoutRoute(String method, String payee, String bank, String branch, String accountName,
                          String accountNumber) {

    public static final String MOBILE_MONEY = "MOBILE_MONEY";
    public static final String EFT = "EFT";
    public static final String CHEQUE = "CHEQUE";

    /** Mobile money to this number -- every payout before 2026-10-28. */
    public static PayoutRoute mobileMoney(String phone) {
        return new PayoutRoute(MOBILE_MONEY, phone, null, null, null, null);
    }

    /**
     * The route a person chose, checked. A blank method is mobile money, so a caller that has not been taught the new
     * choices keeps behaving as it always has.
     *
     * @throws IllegalArgumentException naming what is missing, in words a form can show
     */
    public static PayoutRoute of(String method, String payee, String bank, String branch, String accountName,
                                 String accountNumber) {
        String m = blank(method) ? MOBILE_MONEY : method.trim().toUpperCase(java.util.Locale.ROOT);
        switch (m) {
            case MOBILE_MONEY -> {
                if (blank(payee)) throw new IllegalArgumentException("Give the mobile-money number to pay");
                return new PayoutRoute(m, payee.trim(), null, null, null, null);
            }
            case EFT -> {
                if (blank(bank)) throw new IllegalArgumentException("Give the bank to transfer to");
                if (blank(accountName)) throw new IllegalArgumentException("Give the name on the bank account");
                if (blank(accountNumber)) throw new IllegalArgumentException("Give the bank account number");
                return new PayoutRoute(m, accountName.trim(), bank.trim(), trimOrNull(branch), accountName.trim(),
                    accountNumber.trim());
            }
            case CHEQUE -> {
                if (blank(payee)) throw new IllegalArgumentException("Give the name the cheque is made out to");
                return new PayoutRoute(m, payee.trim(), null, null, null, null);
            }
            default -> throw new IllegalArgumentException("Pay by mobile money, bank transfer or cheque");
        }
    }

    /** Read back from a request event; absent keys are a mobile-money payout to {@code payeeRef}. */
    public static PayoutRoute fromPayload(Map<String, ?> payload) {
        Object method = payload.get("disbursementMethod");
        return new PayoutRoute(method == null ? MOBILE_MONEY : method.toString(), str(payload.get("payeeRef")),
            str(payload.get("payeeBank")), str(payload.get("payeeBranch")), str(payload.get("payeeAccountName")),
            str(payload.get("payeeAccountNumber")));
    }

    /** The keys a request event carries -- {@code payeeRef} included, so a publisher puts this map and nothing else. */
    public Map<String, Object> toPayload() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("payeeRef", payee);
        out.put("disbursementMethod", method);
        if (bank != null) out.put("payeeBank", bank);
        if (branch != null) out.put("payeeBranch", branch);
        if (accountName != null) out.put("payeeAccountName", accountName);
        if (accountNumber != null) out.put("payeeAccountNumber", accountNumber);
        return out;
    }

    /** Paid by a person rather than a gateway. */
    public boolean byHand() {
        return EFT.equals(method) || CHEQUE.equals(method);
    }

    /** One line for a list: "+255712345678", "CRDB, Kariakoo -- Amina Owner, 0150...", "Cheque to Amina Owner". */
    public String describe() {
        return switch (method) {
            case EFT -> bank + (branch != null ? ", " + branch : "") + " -- " + accountName + ", " + accountNumber;
            case CHEQUE -> "Cheque to " + payee;
            default -> payee;
        };
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String trimOrNull(String s) { return blank(s) ? null : s.trim(); }
    private static String str(Object o) { return o == null ? null : o.toString(); }
}
