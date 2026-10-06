package tz.co.nlolo.lifeplatform.finaccounting.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The posting rules in force (IFRS 17 I3a), as the console shows them: read-only, because the rules file is the source
 * of truth until a database editor with maker-checker exists.
 */
public record PostingRulesView(int version, String versionLabel, List<Rule> rules, List<Setting> settings) {

    /**
     * A rate the rules apply (IFRS 17 I3b): COMMISSION_WITHHOLDING_RATE, PREMIUM_LEVY_RATE. {@code value} is the
     * percentage in force today, or null when none is configured -- then nothing is withheld or levied.
     */
    public record Setting(String key, String value, LocalDate effectiveFrom) {}

    public record Rule(String id, String event, List<String> models, Map<String, String> when, LocalDate effectiveFrom,
                       LocalDate effectiveTo, String description, List<Line> lines) {}

    public record Line(PostingDirection side, String account, String accountName, String amount, String movement) {}
}
