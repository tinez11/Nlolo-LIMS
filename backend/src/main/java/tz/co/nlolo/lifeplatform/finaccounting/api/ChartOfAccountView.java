package tz.co.nlolo.lifeplatform.finaccounting.api;

/** Read view of {@code finaccounting.chart_of_account}. Every account on this platform is
 * currently a PLACEHOLDER pending Finance sign-off. */
public record ChartOfAccountView(String accountCode, String name, AccountType accountType,
                                  PostingDirection normalBalance) {}
