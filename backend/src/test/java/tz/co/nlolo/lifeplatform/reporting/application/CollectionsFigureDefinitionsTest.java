package tz.co.nlolo.lifeplatform.reporting.application;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.reporting.api.FigureDefinition;

import static org.assertj.core.api.Assertions.assertThat;

/** The Collections figures explained (2026-11-11, decision K1): every figure the follow-up list and dashboard show. */
class CollectionsFigureDefinitionsTest {

    @Test
    void everyCollectionsFigureIsExplainedInFull() {
        assertThat(CollectionsFigureDefinitions.GROUP.title()).isEqualTo("Collections");
        assertThat(CollectionsFigureDefinitions.GROUP.figures()).extracting(FigureDefinition::key)
            .containsExactly("due", "paid", "rate", "overdueNow", "dueToday", "owedToday");
        assertThat(CollectionsFigureDefinitions.GROUP.figures()).allSatisfy(f -> {
            assertThat(f.name()).isNotBlank();
            assertThat(f.meaning()).isNotBlank();
            assertThat(f.counts()).isNotBlank();
            assertThat(f.leavesOut()).isNotBlank();
            assertThat(f.comesFrom()).isNotBlank();
            assertThat(f.dateBasis()).isNotBlank();
        });
    }
}
