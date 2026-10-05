package tz.co.nlolo.lifeplatform.finaccounting;

import tz.co.nlolo.lifeplatform.finaccounting.api.PostingMode;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccount;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccountBlueprint;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccountBlueprint.Seed;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PostingRule;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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

    /**
     * Every account today's postings land on (the interim remap, IFRS 17 I1 R2) must be a guide posting account an
     * EVENT journal may post to: AUTO or BOTH. The database's mode guard enforces this at runtime; this is the
     * build-time half.
     */
    @Test
    void everyInterimRuleTargetsAnAccountAnEventMayPostTo() throws Exception {
        int checked = 0;
        for (Field f : PostingRule.class.getFields()) {
            if (f.getType() != String.class || !Modifier.isStatic(f.getModifiers())) continue;
            String code = (String) f.get(null);
            assertThat(byCode).as(f.getName()).containsKey(code);
            assertThat(byCode.get(code).postingAllowed()).as(f.getName()).isTrue();
            assertThat(byCode.get(code).mode()).as(f.getName()).isIn(PostingMode.AUTO, PostingMode.BOTH);
            checked++;
        }
        assertThat(checked).isGreaterThanOrEqualTo(16);
    }
}
