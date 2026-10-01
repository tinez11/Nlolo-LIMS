package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request bodies for money moving on an account. Money arrives as a decimal STRING with the
 * server's pattern, as every money body on this platform does -- never a JSON number.
 */
final class AccountRequestBodies {
    private AccountRequestBodies() {}

    static final String AMOUNT = "^\\d+(\\.\\d{1,2})?$";
    static final String SIGNED_AMOUNT = "^-?\\d+(\\.\\d{1,2})?$";
    static final String AMOUNT_MESSAGE = "must be an amount with at most two decimals";

    record Withdrawal(@NotBlank @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE) String amount,
                      @NotBlank @Size(max = 200) String payeeRef) {}

    record TopUp(@NotBlank @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE) String amount,
                 @NotBlank @Size(max = 200) String payerRef) {}

    record TransferIn(@NotBlank @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE) String amount,
                      @NotBlank @Size(max = 200) String sourceScheme,
                      @Size(max = 200) String documentRef) {}

    /** Signed: a correction may take money out as well as put it in. */
    record Adjustment(@NotBlank @Pattern(regexp = SIGNED_AMOUNT, message = AMOUNT_MESSAGE) String amount,
                      @NotBlank @Size(max = 500) String reason) {}
}
