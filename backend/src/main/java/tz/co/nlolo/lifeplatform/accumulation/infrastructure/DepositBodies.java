package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tz.co.nlolo.lifeplatform.accumulation.api.MaturityAction;

/** Request bodies for a fixed-term deposit's maturity: the client's choice, and finance's payee. */
final class DepositBodies {
    private DepositBodies() {}

    record Instruction(@NotNull MaturityAction action, Integer termMonths, @Size(max = 200) String payeeRef) {}

    record Payout(@NotBlank @Size(max = 200) String payeeRef) {}
}
