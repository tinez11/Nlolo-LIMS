package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The account charges staff keep and choose per savings policy (2026-10-09, product V32), and the organisation's rule on
 * whether a charge may take a balance below its product's minimum.
 */
public interface AccountChargeApi {

    /** Every charge, by name; {@code activeOnly} for the ones that may still be chosen. */
    List<AccountChargeView> list(boolean activeOnly);

    /** Refused (IllegalArgumentException) for a duplicate name, an unknown "when", or an amount out of range. */
    AccountChargeView create(String name, String description, String when, String amountType, BigDecimal amount,
                             String currency, String createdBy);

    /** Take a charge out of the choice, or put it back. Policies already on it keep it. */
    AccountChargeView setActive(UUID chargeId, boolean active);

    /** The charges a case or policy may be given now: every one must exist and still be offered. */
    List<AccountChargeView> requireChoosable(Collection<UUID> chargeIds);

    /** The charges a policy carries, offered or not -- what it is charged by. Unknown ids are skipped. */
    List<AccountChargeView> resolve(Collection<UUID> chargeIds);

    /** May a charge take an account below its product's minimum balance? No, unless the organisation says so. */
    boolean mayGoBelowMinimum();

    void setMayGoBelowMinimum(boolean allowed, String updatedBy);
}
