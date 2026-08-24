package tz.co.nlolo.lifeplatform.distribution.api;

import java.math.BigDecimal;
import java.util.UUID;

/** Read view of {@code distribution.domain.CommissionRule}. Exactly one of {@code rate}/
 * {@code flatAmount} is non-null (the domain's rate-XOR-flat invariant), and {@code flatCurrency}
 * is non-null iff {@code flatAmount} is. */
public record CommissionRuleView(UUID commissionRuleId, TierType tierType, BigDecimal rate,
                                  BigDecimal flatAmount, String flatCurrency) {}
