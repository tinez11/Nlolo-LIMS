package tz.co.nlolo.lifeplatform.finaccounting.api;

/**
 * Matches {@code chart_of_account.account_type}'s CHECK (finaccounting V10). CLEARING is class 9: temporary accounts
 * that must return to zero. A contra account keeps its parent's type with the opposite normal balance.
 */
public enum AccountType { ASSET, LIABILITY, EQUITY, INCOME, EXPENSE, CLEARING }
