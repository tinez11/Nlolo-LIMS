package tz.co.nlolo.lifeplatform.finaccounting;

import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccount;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccountBlueprint;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccountBlueprint.Seed;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The blueprint is the single definition of this platform's chart of accounts -- the IFRS 17 posting guide's -- so
 * these are structural invariants rather than examples: the seeder, the posting guards and the rules all rely on them.
 */
class ChartOfAccountBlueprintTest {

    private final Map<String, Seed> byCode = ChartOfAccountBlueprint.accounts().stream()
        .collect(Collectors.toMap(Seed::code, Function.identity()));

    @Test
    void everyChildCodeSitsInsideItsParentsBlock() {
        for (Seed seed : ChartOfAccountBlueprint.accounts()) {
            if (seed.parentCode() == null) continue;
            assertThat(seed.code())
                .as("%s must sit inside %s's block", seed.code(), seed.parentCode())
                .startsWith(ChartOfAccount.significantPrefix(seed.parentCode()));
        }
    }

    @Test
    void noAccountWithChildrenAllowsPosting() {
        Set<String> parents = ChartOfAccountBlueprint.accounts().stream()
            .map(Seed::parentCode).filter(Objects::nonNull).collect(Collectors.toSet());
        for (Seed seed : ChartOfAccountBlueprint.accounts()) {
            if (parents.contains(seed.code())) {
                assertThat(seed.postingAllowed()).as("%s has children and must be a heading", seed.code()).isFalse();
            }
        }
    }
}
