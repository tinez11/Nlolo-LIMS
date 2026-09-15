package tz.co.nlolo.lifeplatform.product.application;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.product.api.EligibilityBounds;
import tz.co.nlolo.lifeplatform.product.api.InvalidProductVersionException;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.Sex;
import tz.co.nlolo.lifeplatform.product.api.SmokerStatus;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The range walk behind {@code ProductApiImpl.rejectUncoveredEntryAges}, exercised directly.
 *
 * <p>No Spring context: this is pure arithmetic that decides whether a real contract can be
 * priced, and it deserves cheap edge coverage that an integration test cannot give it without a
 * container per case. Same arrangement, and the same package placement, as
 * {@code PolicyApiImplSurrenderChargeTest}.
 */
class ProductCoverageRuleTest {

    private static ProductApi.BaseRateInput cell(int from, int to, Sex sex, SmokerStatus smoker) {
        return new ProductApi.BaseRateInput(from, to, sex, smoker, new BigDecimal("1.0000"));
    }

    private static EligibilityBounds ages(int min, int max) {
        return new EligibilityBounds(min, max, null, null, null, null);
    }

    @Test
    void oneBandCoveringTheWholeRangeIsEnough() {
        assertThatCode(() -> ProductApiImpl.rejectUncoveredEntryAges(
            List.of(cell(18, 65, Sex.FEMALE, SmokerStatus.NON_SMOKER),
                    cell(18, 65, Sex.MALE, SmokerStatus.NON_SMOKER)),
            ages(18, 65))).doesNotThrowAnyException();
    }

    @Test
    void adjacentBandsJoinUpBecauseAgeToIsInclusive() {
        assertThatCode(() -> ProductApiImpl.rejectUncoveredEntryAges(
            List.of(cell(18, 45, Sex.FEMALE, SmokerStatus.NON_SMOKER),
                    cell(46, 65, Sex.FEMALE, SmokerStatus.NON_SMOKER),
                    cell(18, 65, Sex.MALE, SmokerStatus.NON_SMOKER)),
            ages(18, 65))).doesNotThrowAnyException();
    }

    @Test
    void overlappingBandsStillCover() {
        assertThatCode(() -> ProductApiImpl.rejectUncoveredEntryAges(
            List.of(cell(18, 50, Sex.FEMALE, SmokerStatus.NON_SMOKER),
                    cell(40, 65, Sex.FEMALE, SmokerStatus.NON_SMOKER),
                    cell(18, 65, Sex.MALE, SmokerStatus.NON_SMOKER)),
            ages(18, 65))).doesNotThrowAnyException();
    }

    @Test
    void bandsMayRunPastTheDeclaredRange() {
        assertThatCode(() -> ProductApiImpl.rejectUncoveredEntryAges(
            List.of(cell(0, 120, Sex.FEMALE, SmokerStatus.NON_SMOKER),
                    cell(0, 120, Sex.MALE, SmokerStatus.NON_SMOKER)),
            ages(18, 65))).doesNotThrowAnyException();
    }

    @Test
    void aGapInTheMiddleIsNamedByItsOwnEdges() {
        assertThatThrownBy(() -> ProductApiImpl.rejectUncoveredEntryAges(
            List.of(cell(18, 30, Sex.FEMALE, SmokerStatus.NON_SMOKER),
                    cell(41, 65, Sex.FEMALE, SmokerStatus.NON_SMOKER),
                    cell(18, 65, Sex.MALE, SmokerStatus.NON_SMOKER)),
            ages(18, 65)))
            .isInstanceOf(InvalidProductVersionException.class)
            .hasMessageContaining("FEMALE/NON_SMOKER")
            .hasMessageContaining("31-40");
    }

    /** The real published version this rule exists for: no woman under 56 can be priced. */
    @Test
    void aBandStartingAboveTheMinimumLeavesTheBottomUncovered() {
        assertThatThrownBy(() -> ProductApiImpl.rejectUncoveredEntryAges(
            List.of(cell(56, 78, Sex.FEMALE, SmokerStatus.NON_SMOKER),
                    cell(18, 78, Sex.MALE, SmokerStatus.NON_SMOKER)),
            ages(18, 78)))
            .isInstanceOf(InvalidProductVersionException.class)
            .hasMessageContaining("18-55");
    }

    @Test
    void aCombinationThatIsAbsentEntirelyCoversNothing() {
        assertThatThrownBy(() -> ProductApiImpl.rejectUncoveredEntryAges(
            List.of(cell(18, 65, Sex.FEMALE, SmokerStatus.NON_SMOKER),
                    cell(18, 65, Sex.MALE, SmokerStatus.NON_SMOKER),
                    cell(18, 65, Sex.FEMALE, SmokerStatus.SMOKER)),
            ages(18, 65)))
            .isInstanceOf(InvalidProductVersionException.class)
            .hasMessageContaining("MALE/SMOKER")
            .hasMessageContaining("18-65");
    }

    @Test
    void aSmokerStatusTheTableNeverPricesIsNotDemanded() {
        // UNKNOWN is absent from the table entirely, which is a product declining to price the
        // undeclared case -- a real underwriting stance, not a hole.
        assertThatCode(() -> ProductApiImpl.rejectUncoveredEntryAges(
            List.of(cell(18, 65, Sex.FEMALE, SmokerStatus.NON_SMOKER),
                    cell(18, 65, Sex.MALE, SmokerStatus.NON_SMOKER)),
            ages(18, 65))).doesNotThrowAnyException();
    }
}
