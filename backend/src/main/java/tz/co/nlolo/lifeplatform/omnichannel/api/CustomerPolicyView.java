package tz.co.nlolo.lifeplatform.omnichannel.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One policy as its policyholder reads it in the portal (2026-10-08, the customer portal design step 2). Customer-safe:
 * names rather than ids, and none of the accounting, underwriting or IFRS 17 classification a staff member sees. The
 * blocks that do not apply to the product are null -- a funeral plan has covered lives, a savings plan a balance.
 */
public record CustomerPolicyView(CustomerDashboardView.CustomerPolicySummary summary, String lifeAssuredName,
                                 LocalDate commencementDate, LocalDate maturityDate, Integer termMonths,
                                 List<Beneficiary> beneficiaries, List<CoveredLife> coveredLives, Savings savings,
                                 Units units, Annuity annuity, List<ClaimLine> claims) {

    public record Beneficiary(String name, BigDecimal sharePercent) {}

    /** @param coveredLifeId what a funeral claim names as the life that died */
    public record CoveredLife(UUID coveredLifeId, String name, String role, BigDecimal benefit, String status, LocalDate waitingPeriodEnds) {}

    public record Savings(BigDecimal balance, String currency, String status, LocalDate openedOn) {}

    public record Units(BigDecimal totalValue, String currency, List<Holding> holdings) {}

    public record Holding(String fundName, BigDecimal units, BigDecimal price, LocalDate priceDate, BigDecimal value) {}

    public record Annuity(String status, BigDecimal annualIncome, BigDecimal instalment, String frequency,
                          LocalDate firstPaymentDate, LocalDate guaranteedUntil, BigDecimal purchasePrice, String currency) {}

    public record ClaimLine(UUID claimId, String claimType, String status, LocalDate dateOfEvent, BigDecimal approvedAmount,
                            String currency) {}
}
