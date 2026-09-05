package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccountBlueprint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code accountType}/{@code normalBalance} are deliberately absent here: both are derived from
 * {@code accountCode}'s own leading digit ({@code PostingRule.accountTypeFor}/
 * {@code normalBalanceFor}), and letting a caller set them independently would let an account
 * disagree with its own code's block convention. {@code level} is absent for the same reason --
 * it is derived from the parent chain.
 *
 * <p>{@code currency} and {@code postingAllowed} default in the compact constructor rather than
 * being required, so a body written against the pre-hierarchy shape still creates a sane account.
 * {@code parentCode} is optional: omitting it creates a block root.
 */
public record CreateAccountRequestDto(
    @NotBlank @Pattern(regexp = "^[1-5]\\d{3}$",
        message = "must be 4 digits with a leading 1-5 block (1=ASSET, 2=LIABILITY, 3=EQUITY, 4=INCOME, 5=EXPENSE)")
    String accountCode,
    @Pattern(regexp = "^[1-5]\\d{3}$",
        message = "must be 4 digits with a leading 1-5 block (1=ASSET, 2=LIABILITY, 3=EQUITY, 4=INCOME, 5=EXPENSE)")
    String parentCode,
    @NotBlank @Size(max = 200) String name,
    @Size(max = 2000) String description,
    @Size(min = 3, max = 3) String currency,
    Boolean postingAllowed) {

    public CreateAccountRequestDto {
        currency = currency == null ? ChartOfAccountBlueprint.SEED_CURRENCY : currency;
        postingAllowed = postingAllowed == null ? Boolean.TRUE : postingAllowed;
    }
}
