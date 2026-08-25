package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** {@code accountType}/{@code normalBalance} are deliberately absent here: both are derived from
 *  {@code accountCode}'s own leading digit ({@code PostingRule.accountTypeFor}/
 *  {@code normalBalanceFor}), and letting a caller set them independently would let an account
 *  disagree with its own code's block convention. */
public record CreateAccountRequestDto(
    @NotBlank @Pattern(regexp = "^[1-5]\\d{3}$",
        message = "must be 4 digits with a leading 1-5 block (1=ASSET, 2=LIABILITY, 3=EQUITY, 4=INCOME, 5=EXPENSE)")
    String accountCode,
    @NotBlank @Size(max = 200) String name) {}
