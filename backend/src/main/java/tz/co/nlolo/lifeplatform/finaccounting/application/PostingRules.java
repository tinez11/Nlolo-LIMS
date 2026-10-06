package tz.co.nlolo.lifeplatform.finaccounting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccountBlueprint;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PostingRuleParser;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PostingRuleSet;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PostingRuleValidator;

/**
 * The posting rules in force (IFRS 17 I3a): {@code finaccounting/posting-rules.yaml}, read and validated once when the
 * application starts. A file that fails validation stops the application, naming every rule at fault -- a mistake in
 * the rules must never reach the ledger. The file is the source of truth until a database editor with maker-checker
 * replaces it (user decision 2026-10-06).
 */
@Component
class PostingRules {

    static final String RESOURCE = "/finaccounting/posting-rules.yaml";

    private final PostingRuleSet rules;

    PostingRules() {
        this.rules = load();
    }

    PostingRuleSet ruleSet() {
        return rules;
    }

    static PostingRuleSet load() {
        var in = PostingRules.class.getResourceAsStream(RESOURCE);
        if (in == null) {
            throw new IllegalStateException(RESOURCE + " is missing from the classpath");
        }
        PostingRuleSet set = PostingRuleParser.parse(in);
        PostingRuleValidator.validate(set, ChartOfAccountBlueprint.accounts(), PostingFactsExtractor.SHAPES);
        return set;
    }
}
