package tz.co.nlolo.lifeplatform.unitlinked.application;

import tz.co.nlolo.lifeplatform.unitlinked.domain.PendingOrder;
import tz.co.nlolo.lifeplatform.unitlinked.domain.UnitEntry;

/**
 * Told each time an order is priced, inside the pricing run's transaction, so the thing that placed the order can
 * finish once its last order is priced: a premium's allocation posts, a charge date's run checks for exhaustion, an
 * exit pays. {@code entry} is null when there was nothing to sell (a SELL ALL on an empty holding).
 */
interface UnitsPricedListener {

    void afterPriced(PendingOrder order, UnitEntry entry);
}
