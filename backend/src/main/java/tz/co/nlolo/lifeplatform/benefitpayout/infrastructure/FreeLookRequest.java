package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * What a service officer sends to start a free-look cancellation.
 *
 * <p>Each deduction's amount is a decimal STRING, matched by pattern, never a JSON number. A
 * number would be parsed as a double somewhere between the browser and the ledger and arrive a
 * fraction of a cent out, on a figure the customer is entitled to check to the shilling.
 */
public record FreeLookRequest(@NotBlank @Size(max = 200) String payeeRef,
                              @Valid List<Deduction> deductions) {

    public record Deduction(@NotBlank @Size(max = 200) String description,
                            @NotNull @Pattern(regexp = "^\\d+(\\.\\d{1,2})?$",
                                message = "An amount must be a decimal with at most two places")
                            String amount,
                            UUID documentId) {}
}
