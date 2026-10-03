package tz.co.nlolo.lifeplatform.product.infrastructure;

import jakarta.validation.Valid;
import tz.co.nlolo.lifeplatform.product.api.BonusMethod;
import tz.co.nlolo.lifeplatform.product.api.BonusPlan;
import tz.co.nlolo.lifeplatform.product.api.BonusSurrenderBasis;
import tz.co.nlolo.lifeplatform.product.api.BonusSurrenderRow;

import java.math.BigDecimal;
import java.util.List;

/**
 * A with-profits version's terms on the wire. Absent means non-participating. method and
 * surrenderBasis are deliberately not @NotNull: the validator refuses their absence in the words the
 * console mirrors, and a bean-validation 400 would say something else.
 */
public record BonusRequest(BonusMethod method, boolean paidUpParticipates, BonusSurrenderBasis surrenderBasis,
                           @Valid List<SurrenderRow> surrenderRows) {

    public record SurrenderRow(int fromCompletedYears, BigDecimal perMille) {}

    public BonusPlan toPlan() {
        return new BonusPlan(true, method, paidUpParticipates, surrenderBasis,
            surrenderRows == null ? List.of() : surrenderRows.stream()
                .map(r -> new BonusSurrenderRow(r.fromCompletedYears(), r.perMille())).toList());
    }
}
