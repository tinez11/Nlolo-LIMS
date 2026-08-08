package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.api.BeneficiaryType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record BeneficiaryInputDto(
    @NotNull BeneficiaryType type,
    UUID partyId,
    String freeformDesignee,
    @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal sharePercent,
    Boolean revocable) {

    public tz.co.nlolo.lifeplatform.policy.api.PolicyApi.BeneficiaryInput toApiInput() {
        return new tz.co.nlolo.lifeplatform.policy.api.PolicyApi.BeneficiaryInput(
            type, partyId, freeformDesignee, sharePercent, revocable == null || revocable);
    }
}
