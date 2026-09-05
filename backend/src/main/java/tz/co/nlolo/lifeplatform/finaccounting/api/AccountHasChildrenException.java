package tz.co.nlolo.lifeplatform.finaccounting.api;

/** 409 at the REST boundary (FinaccountingExceptionHandler): the account is a parent, and
 *  {@code fk_chart_of_account_parent} (finaccounting/V5) would refuse to let it go while its
 *  children point at it. Deleting a branch is not something this endpoint does implicitly --
 *  the children must be dealt with first, deliberately, one at a time. */
public class AccountHasChildrenException extends RuntimeException {
    public AccountHasChildrenException(String message) { super(message); }
}
