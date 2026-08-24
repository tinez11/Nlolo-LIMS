package tz.co.nlolo.lifeplatform.policy.api;

import java.util.UUID;

public record BeneficiaryView(UUID beneficiaryId, BeneficiaryType type, UUID partyId, String freeformDesignee,
                               java.math.BigDecimal sharePercent, boolean revocable) {}
