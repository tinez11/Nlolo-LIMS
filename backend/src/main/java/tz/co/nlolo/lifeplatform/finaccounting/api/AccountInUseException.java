package tz.co.nlolo.lifeplatform.finaccounting.api;

/** 409 at the REST boundary (FinaccountingExceptionHandler): the account has at least one real
 *  {@code gl_posting} row against it ({@code fk_gl_posting_account_code}, finaccounting/V3), so
 *  deleting it would either violate that foreign key or, worse, orphan historical postings from
 *  the account they were booked to. Retiring an in-use account is a distinct, deferred concern
 *  (V3's own comment) -- this exception only guards the delete path. */
public class AccountInUseException extends RuntimeException {
    public AccountInUseException(String message) { super(message); }
}
