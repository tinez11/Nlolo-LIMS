package tz.co.nlolo.lifeplatform.finaccounting.api;

import java.time.Instant;

/**
 * Read view of {@code finaccounting.chart_of_account}.
 *
 * <p>A seeded account carries the IFRS 17 posting guide's type, normal balance and posting {@code mode}; an account
 * added through the API inherits them from its parent. None is ever accepted from a client, nor is {@code level},
 * derived from the parent chain.
 */
public record ChartOfAccountView(String accountCode, String name, AccountType accountType,
                                  PostingDirection normalBalance, String parentCode, short level,
                                  boolean postingAllowed, AccountStatus status, String currency,
                                  String controlOf, String description, Instant createdAt,
                                  String createdBy, PostingMode mode) {}
