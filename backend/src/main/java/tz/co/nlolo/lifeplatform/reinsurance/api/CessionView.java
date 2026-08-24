package tz.co.nlolo.lifeplatform.reinsurance.api;

import java.math.BigDecimal;
import java.util.UUID;

/** Read view of {@code reinsurance.domain.Cession}. {@code cededPremiumAmount}/{@code
 * cededPremiumCurrency} are non-null together or null together (V2's {@code
 * cession_ceded_premium_paired}); both are null for a treaty type that cedes risk without a
 * modelled premium share. */
public record CessionView(UUID cessionId, String policyNumber, UUID treatyId,
                           BigDecimal cededAmount, String cededCurrency,
                           BigDecimal cededPremiumAmount, String cededPremiumCurrency) {}
