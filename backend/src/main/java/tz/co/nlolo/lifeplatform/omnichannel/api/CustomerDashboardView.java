package tz.co.nlolo.lifeplatform.omnichannel.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The signed-in customer's dashboard (2026-10-08, the customer portal design step 2): what they hold, what they owe
 * next, what is being claimed and what their savings are worth -- every figure read from the module that owns it.
 *
 * @param nextPremium the earliest premium due or in grace across their policies; null when nothing is due
 * @param accountValue savings balances and unit-linked fund values added up; null when they hold neither, so the
 *     card is left out rather than showing nothing worth TZS 0
 */
public record CustomerDashboardView(String displayName, int activePolicies, int claimsInProgress, NextPremium nextPremium,
                                    Amount accountValue, List<CustomerPolicySummary> policies,
                                    /** Messages not yet opened in the portal (step 7). */
                                    int unreadMessages) {

    public record NextPremium(String policyNumber, String productName, BigDecimal amount, String currency,
                              LocalDate dueDate, String status) {}

    public record Amount(BigDecimal amount, String currency) {}

    /**
     * One policy as the customer reads it in a list.
     *
     * @param value what a savings account or unit-linked plan is worth today; null on cover with no value
     */
    public record CustomerPolicySummary(String policyNumber, String productName, String productCategory, String status,
                                        BigDecimal sumAssured, String currency, BigDecimal premium, String premiumFrequency,
                                        LocalDate nextDueDate, BigDecimal nextDueAmount, BigDecimal value) {}
}
