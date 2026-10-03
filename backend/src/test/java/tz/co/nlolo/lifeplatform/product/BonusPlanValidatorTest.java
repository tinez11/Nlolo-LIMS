package tz.co.nlolo.lifeplatform.product;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.product.api.*;
import tz.co.nlolo.lifeplatform.product.domain.BonusPlanValidator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** BonusPlanValidator, rule by rule, in the exact words the console mirrors. */
class BonusPlanValidatorTest {

    private static final CashValuePlan SCALE = new CashValuePlan("ACT/1", LocalDate.of(2026, 1, 1), "PROPORTIONATE", 2,
        List.of(new CashValueRowInput(2, null, null, new BigDecimal("200"), null)));
    private static final PayoutPlan MATURITY = PayoutPlan.authored(new PayoutTerms(15, null, null, null),
        List.of(new PayoutRowInput(PayoutKind.MATURITY, null, null, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("100"), null)));
    private static final AccumulationPlan ACCOUNT = new AccumulationPlan(ValueBasis.ACCOUNT, BigDecimal.ONE, BigDecimal.ZERO,
        List.of(new AccumulationChargeRow(1, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)));

    private static BonusPlan plan(BonusSurrenderBasis basis, List<BonusSurrenderRow> rows) {
        return new BonusPlan(true, BonusMethod.COMPOUND, false, basis, rows);
    }

    private static void ok(ProductCategory c, BonusPlan p, CashValuePlan cv, PayoutPlan payout) {
        assertThatCode(() -> BonusPlanValidator.validate(c, p, cv, AccumulationPlan.none(), payout)).doesNotThrowAnyException();
    }

    private static void refused(ProductCategory c, BonusPlan p, CashValuePlan cv, AccumulationPlan acc, PayoutPlan payout, String message) {
        assertThatThrownBy(() -> BonusPlanValidator.validate(c, p, cv, acc, payout)).hasMessage(message);
    }

    @Test
    void aNonParticipatingVersionIsNotChecked() {
        ok(ProductCategory.TERM_LIFE, BonusPlan.none(), CashValuePlan.none(), PayoutPlan.none());
    }

    @Test
    void acceptsEachSurrenderBasisWhenItsInputsArePresent() {
        ok(ProductCategory.ENDOWMENT, plan(BonusSurrenderBasis.NONE, List.of()), CashValuePlan.none(), MATURITY);
        ok(ProductCategory.ENDOWMENT, plan(BonusSurrenderBasis.SUM_ASSURED_SCALE, List.of()), SCALE, MATURITY);
        ok(ProductCategory.WHOLE_LIFE, plan(BonusSurrenderBasis.OWN_SCALE,
            List.of(new BonusSurrenderRow(2, new BigDecimal("300")))), CashValuePlan.none(), PayoutPlan.none());
    }

    @Test
    void onlyEndowmentAndWholeLifeMayBeWithProfits() {
        refused(ProductCategory.TERM_LIFE, plan(BonusSurrenderBasis.NONE, List.of()), CashValuePlan.none(),
            AccumulationPlan.none(), PayoutPlan.none(), "A TERM_LIFE product cannot be with-profits");
    }

    @Test
    void aVersionIsNotBothAnAccountAndWithProfits() {
        refused(ProductCategory.ENDOWMENT, plan(BonusSurrenderBasis.NONE, List.of()), CashValuePlan.none(), ACCOUNT, MATURITY,
            "A version is valued either by an account or with profits, not both");
    }

    @Test
    void aFixedTermDepositCannotBeWithProfits() {
        // Said FIRST, in its own words: a deposit is an ACCOUNT version underneath, so without this
        // the author would be told the account rule, which is true but not what they did.
        DepositPlan deposit = new DepositPlan(List.of(new DepositRateRow(new BigDecimal("500000"), 3, new BigDecimal("3"))));
        assertThatThrownBy(() -> BonusPlanValidator.validate(ProductCategory.ENDOWMENT, plan(BonusSurrenderBasis.NONE, List.of()),
                CashValuePlan.none(), AccumulationPlan.forDeposit(), PayoutPlan.none(), deposit))
            .hasMessage("A fixed-term deposit cannot be with-profits");
    }

    @Test
    void theMethodAndTheSurrenderBasisMustBeStated() {
        refused(ProductCategory.ENDOWMENT, new BonusPlan(true, null, false, BonusSurrenderBasis.NONE, List.of()),
            CashValuePlan.none(), AccumulationPlan.none(), MATURITY,
            "A with-profits version must state its bonus method (SIMPLE or COMPOUND)");
        refused(ProductCategory.ENDOWMENT, new BonusPlan(true, BonusMethod.SIMPLE, false, null, List.of()),
            CashValuePlan.none(), AccumulationPlan.none(), MATURITY,
            "A with-profits version must state how attached bonuses count toward surrender (NONE, SUM_ASSURED_SCALE or OWN_SCALE)");
    }

    @Test
    void eachSurrenderBasisNeedsItsInputs() {
        refused(ProductCategory.ENDOWMENT, plan(BonusSurrenderBasis.SUM_ASSURED_SCALE, List.of()), CashValuePlan.none(),
            AccumulationPlan.none(), MATURITY, "SUM_ASSURED_SCALE needs the version's own cash-value scale");
        refused(ProductCategory.ENDOWMENT, plan(BonusSurrenderBasis.OWN_SCALE, List.of()), CashValuePlan.none(),
            AccumulationPlan.none(), MATURITY, "OWN_SCALE needs at least one row of bonus surrender values");
        refused(ProductCategory.ENDOWMENT, plan(BonusSurrenderBasis.OWN_SCALE, List.of(
                new BonusSurrenderRow(2, new BigDecimal("300")), new BonusSurrenderRow(2, new BigDecimal("400")))),
            CashValuePlan.none(), AccumulationPlan.none(), MATURITY, "Bonus surrender rows must each start at a different completed year");
        refused(ProductCategory.ENDOWMENT, plan(BonusSurrenderBasis.OWN_SCALE, List.of(
                new BonusSurrenderRow(2, new BigDecimal("1001")))),
            CashValuePlan.none(), AccumulationPlan.none(), MATURITY, "A bonus surrender value must be between 0 and 1000 per mille");
        refused(ProductCategory.ENDOWMENT, plan(BonusSurrenderBasis.NONE, List.of(new BonusSurrenderRow(2, BigDecimal.TEN))),
            CashValuePlan.none(), AccumulationPlan.none(), MATURITY, "Bonus surrender rows are only for OWN_SCALE");
    }

    @Test
    void aWithProfitsEndowmentMustPayAMaturity() {
        refused(ProductCategory.ENDOWMENT, plan(BonusSurrenderBasis.NONE, List.of()), CashValuePlan.none(),
            AccumulationPlan.none(), PayoutPlan.none(),
            "A with-profits ENDOWMENT needs a MATURITY payout, or its bonuses could never be paid at term end");
    }

    @Test
    void theOwnScaleIsReadByTheLatestRowStartedAndIsZeroBeforeTheFirst() {
        BonusPlan p = plan(BonusSurrenderBasis.OWN_SCALE, List.of(
            new BonusSurrenderRow(2, new BigDecimal("300")), new BonusSurrenderRow(5, new BigDecimal("600"))));
        assertThat(p.ownScalePerMille(1)).isEqualByComparingTo("0");
        assertThat(p.ownScalePerMille(2)).isEqualByComparingTo("300");
        assertThat(p.ownScalePerMille(4)).isEqualByComparingTo("300");
        assertThat(p.ownScalePerMille(9)).isEqualByComparingTo("600");
    }
}
