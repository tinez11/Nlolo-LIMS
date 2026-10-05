package tz.co.nlolo.lifeplatform.product.api;

import java.util.Optional;

/**
 * What a UNIT_LINKED version's publish must know about the insurer's fund register (plan R1): whether a fund
 * exists, is open, and what currency it prices in. Declared here and implemented by the unitlinked module, which
 * owns the register -- product cannot depend on unitlinked, since unitlinked depends on product for the terms.
 */
public interface FundDirectory {

    Optional<FundSummary> find(String fundCode);

    record FundSummary(String code, String currency, boolean open) {}
}
