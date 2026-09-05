package tz.co.nlolo.lifeplatform.finaccounting.api;

/**
 * Whether an account may still receive new postings.
 *
 * <p>{@code INACTIVE} is the RETIREMENT path, and is distinct in kind from deletion: historical
 * postings must stay mappable to the account they were booked to -- finaccounting/V3's own
 * comment says exactly this when it explains why {@code ON DELETE} is left at {@code NO ACTION}.
 * So an account that has ever been posted against is deactivated, never deleted, and its whole
 * history stays readable while nothing new can land on it.
 */
public enum AccountStatus { ACTIVE, INACTIVE }
