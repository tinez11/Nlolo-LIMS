package tz.co.nlolo.lifeplatform.reporting.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One policy behind an agent's sales figure (2026-11-12, analytics plan 3).
 *
 * @param premiumAYear the yearly premium; null for a single premium (D2) or a savings deposit (D3)
 * @param savingsDeposit the opening deposit when the policy is savings or a fixed deposit (D3), else null
 */
public record PolicySold(String policyNumber, String productName, String clientName, LocalDate onCoverFrom,
                         String frequency, BigDecimal premium, BigDecimal premiumAYear, BigDecimal cover,
                         BigDecimal savingsDeposit, String currency, String agentName) {}
