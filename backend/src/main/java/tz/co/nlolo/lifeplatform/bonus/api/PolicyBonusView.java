package tz.co.nlolo.lifeplatform.bonus.api;

import java.math.BigDecimal;
import java.util.List;

/** A with-profits policy's bonuses: the ledger in sequence, every declaration's outcome, every settlement. */
public record PolicyBonusView(String policyNumber, String currency, BigDecimal attachedTotal, List<BonusEntryView> entries,
                              List<BonusOutcomeView> outcomes, List<BonusSettlementView> settlements) {}
