package tz.co.nlolo.lifeplatform.finaccounting.api;

/** 409 at the REST boundary (FinaccountingExceptionHandler): {@code (tenant_id, account_code)}
 *  already exists. */
public class DuplicateAccountCodeException extends RuntimeException {
    public DuplicateAccountCodeException(String message) { super(message); }
}
