package tz.co.nlolo.lifeplatform.finaccounting.api;

import java.time.Instant;

/**
 * Read view of {@code finaccounting.chart_of_account}. Every account on this platform is
 * currently a PLACEHOLDER pending Finance sign-off.
 *
 * <p>{@code accountType} and {@code normalBalance} are DERIVED from {@code accountCode}'s leading
 * digit rather than stored decisions, and {@code level} is derived from the parent chain -- none
 * of the three is ever accepted from a client.
 */
public record ChartOfAccountView(String accountCode, String name, AccountType accountType,
                                  PostingDirection normalBalance, String parentCode, short level,
                                  boolean postingAllowed, AccountStatus status, String currency,
                                  String controlOf, String description, Instant createdAt,
                                  String createdBy) {}
